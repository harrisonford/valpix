package com.franky.valpix.core

/** The program's own records are missing: wrong network or not set up. */
class ProgramNotFoundException : Exception("the valpix program is not set up on this network")

/** Everything the app needs to draw the home screen, from one round trip. */
data class WalletState(
    /** `null` until an existing member has brought this wallet in. */
    val member: MemberRecord?,
    val config: ConfigRecord,
    val pool: PoolRecord,
    /** The pool's SOL, in lamports. */
    val solReserve: ULong,
    val walletLamports: ULong,
    val clock: ChainClock,
) {
    /** Micro-CLPC at a moment on the phone's clock, corrected to chain time. */
    fun balance(atMillis: Long): ULong =
        member?.let { Money.liveBalance(it, config.dailyRate, clock.now(atMillis)) } ?: 0uL

    /**
     * What the next payment will be charged, or `null` when the pool will
     * not refund it and the payment costs no CLPC.
     */
    val fee: ULong?
        get() = Money.fee(solReserve, pool.clpcReserve, config.feeRefundLamports, config.poolFloorLamports, config.maxFee)

    /**
     * The state as bytes, so the phone can show the last known balance,
     * still ticking, when it opens without a route to the chain.
     */
    fun snapshot(): ByteArray {
        val w = BorshWriter()
        w.u8(SNAPSHOT_VERSION)
        w.u8(if (member == null) 0 else 1)
        member?.let {
            w.publicKey(it.wallet); w.u64(it.settled); w.i64(it.lastSettledTs)
            w.i64(it.createdTs); w.publicKey(it.inviter); w.i64(it.lastReviveTs)
        }
        w.publicKey(config.admin); w.u64(config.dailyRate); w.u64(config.feeRefundLamports)
        w.u64(config.floatLamports); w.u64(config.joinSurcharge); w.u64(config.reviveThresholdLamports)
        w.i64(config.revivePeriodSecs); w.u64(config.poolFloorLamports); w.u64(config.maxFee)
        w.u64(pool.clpcReserve); w.u64(solReserve); w.u64(walletLamports)
        w.i64(clock.offset.toRawBits())
        return w.toByteArray()
    }

    companion object {
        private const val SNAPSHOT_VERSION = 1

        fun fromSnapshot(bytes: ByteArray): WalletState? = try {
            val r = BorshReader(bytes)
            if (r.u8() != SNAPSHOT_VERSION) throw BorshException("version")
            val member = when (r.u8()) {
                0 -> null
                1 -> MemberRecord(r.publicKey(), r.u64(), r.i64(), r.i64(), r.publicKey(), r.i64())
                else -> throw BorshException("flag")
            }
            val config = ConfigRecord(r.publicKey(), r.u64(), r.u64(), r.u64(), r.u64(), r.u64(), r.i64(), r.u64(), r.u64())
            val state = WalletState(member, config, PoolRecord(r.u64()), r.u64(), r.u64(), ChainClock(Double.fromBits(r.i64())))
            if (r.isAtEnd && state.clock.offset.isFinite()) state else null
        } catch (e: BorshException) {
            null
        }
    }
}

sealed class FailureReason {
    /** No access point could be reached. */
    data object NoRoute : FailureReason()

    /** The program or the chain refused it; carries the error's name or number. */
    data class Rejected(val reason: String) : FailureReason()

    /** It did not confirm before the transaction's time ran out. Not paid. */
    data object Expired : FailureReason()

    /** It was sent, and the chain could not be reached to learn what happened. It may have been paid. */
    data object Unconfirmed : FailureReason()
}

sealed class Outcome {
    data class Confirmed(val signature: String) : Outcome()
    data class Failed(val reason: FailureReason) : Outcome()
}

data class HistoryEntry(
    val signature: String,
    val ts: Long,
    val kind: Kind,
    val counterparty: PublicKey,
    /** The payer's chosen name, for payments received. */
    val name: String,
    val amountMicro: ULong,
) {
    enum class Kind { RECEIVED, SENT, INVITED, JOINED }
}

/** The valpix program, as a set of things a wallet can do. Every call blocks. */
class ValpixClient(
    val rpc: SolanaRpc,
    val program: ValpixProgram,
    private val pollIntervalMs: Long = 500,
    private val timeoutMs: Long = 90_000,
) {
    fun state(wallet: PublicKey, observedAtMillis: Long = System.currentTimeMillis()): WalletState {
        val found = rpc.accounts(listOf(program.member(wallet), program.config, program.pool, program.vault, wallet))
        val config = found[1]?.let { ConfigRecord.decode(it.data) } ?: throw ProgramNotFoundException()
        val pool = found[2]?.let { PoolRecord.decode(it.data) } ?: throw ProgramNotFoundException()
        val rentMin = rpc.minimumBalanceForRentExemption(0)
        val vaultLamports = found[3]?.lamports ?: 0uL
        return WalletState(
            member = found[0]?.let { MemberRecord.decode(it.data) },
            config = config,
            pool = pool,
            solReserve = if (vaultLamports > rentMin) vaultLamports - rentMin else 0uL,
            walletLamports = found[4]?.lamports ?: 0uL,
            clock = ChainClock.observed(rpc.chainTime().toDouble(), observedAtMillis),
        )
    }

    /** Pays another member and waits until the chain confirms or refuses. */
    fun pay(from: Keypair, to: PublicKey, amountMicro: ULong, name: String): Outcome =
        submit(program.pay(from.publicKey, to, amountMicro, name), from)

    /** Brings a newcomer in, paid for by the inviter. */
    fun join(inviter: Keypair, newcomer: PublicKey): Outcome = submit(program.join(inviter.publicKey, newcomer), inviter)

    /** Tops the wallet's SOL back up from the pool. */
    fun reviveSelf(keypair: Keypair): Outcome = submit(program.reviveSelf(keypair.publicKey), keypair)

    /**
     * Signs, sends and follows one instruction to its end.
     *
     * It reports `Confirmed` only when the chain says so, and it reports a
     * failure that means "not paid" only when that is certain. Once the
     * transaction may have left the phone, route trouble is retried, and if
     * the answer never comes the outcome is `Unconfirmed`, never "failed":
     * telling someone a payment failed when it landed makes them pay twice.
     */
    internal fun submit(instruction: Instruction, signer: Keypair): Outcome {
        // Nothing has been signed yet: failing here is a clean "no route".
        val latest = try {
            rpc.latestBlockhash()
        } catch (e: RpcException) {
            return Outcome.Failed(FailureReason.NoRoute)
        }
        val message = Transaction.compile(listOf(instruction), signer.publicKey, latest.hash)
        val wire = try {
            Transaction.sign(message, listOf(signer))
        } catch (e: MissingSignerException) {
            return Outcome.Failed(FailureReason.Rejected("signing"))
        }
        // The transaction's own signature, so it can be followed whatever
        // the access point answers, or fails to answer.
        val signature = Base58.encode(wire.copyOfRange(1, 65))

        try {
            rpc.send(wire)
        } catch (e: RpcException.Rpc) {
            // The access point refused it before passing it on.
            return Outcome.Failed(FailureReason.Rejected(errorName(e.logs) ?: e.text))
        } catch (e: RpcException) {
            // No answer. It may have been passed on all the same: go and look.
        }

        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            try {
                settledOutcome(signature)?.let { return it }
                if (rpc.blockHeight() > latest.lastValidBlockHeight) {
                    // Too late to land now. One last look, in case it made
                    // one of the final blocks.
                    return settledOutcome(signature) ?: Outcome.Failed(FailureReason.Expired)
                }
            } catch (e: RpcException) {
                // Route trouble: keep trying until the deadline.
            }
            if (System.nanoTime() - deadline >= 0) return Outcome.Failed(FailureReason.Unconfirmed)
            Thread.sleep(pollIntervalMs)
        }
    }

    /** The outcome, if the chain has one for this signature yet. */
    private fun settledOutcome(signature: String): Outcome? {
        val status = rpc.signatureStatus(signature) ?: return null
        status.error?.let { return Outcome.Failed(FailureReason.Rejected(it)) }
        return if (status.isConfirmed) Outcome.Confirmed(signature) else null
    }

    /**
     * Recent payments and joins, newest first, as this wallet saw them.
     * Best-effort: an access point may have dropped older transactions.
     */
    fun history(wallet: PublicKey, limit: Int = 20): List<HistoryEntry> {
        val entries = ArrayList<HistoryEntry>()
        for (info in rpc.signatures(program.member(wallet), limit)) {
            if (info.failed) continue
            for (event in events(info.signature) ?: continue) {
                when (event) {
                    is ValpixEvent.Paid -> when (wallet) {
                        event.recipient -> entries.add(HistoryEntry(info.signature, event.ts, HistoryEntry.Kind.RECEIVED, event.payer, event.name, event.amount))
                        event.payer -> entries.add(HistoryEntry(info.signature, event.ts, HistoryEntry.Kind.SENT, event.recipient, "", event.amount))
                    }
                    is ValpixEvent.Joined -> when (wallet) {
                        event.inviter -> entries.add(HistoryEntry(info.signature, event.ts, HistoryEntry.Kind.INVITED, event.newcomer, "", event.cost))
                        event.newcomer -> entries.add(HistoryEntry(info.signature, event.ts, HistoryEntry.Kind.JOINED, event.inviter, "", 0uL))
                    }
                }
            }
        }
        return entries
    }

    /**
     * The events of transactions already read. A confirmed transaction does
     * not change, and a phone waiting to be paid asks about the same few
     * every second or two: public access points allow far fewer transaction
     * lookups than that, and refuse them all once the limit is passed.
     */
    private val seen = object : LinkedHashMap<String, List<ValpixEvent>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ValpixEvent>>?) = size > 200
    }

    /** A transaction's valpix events, or `null` when the access point does not have it (yet, or any more). */
    private fun events(signature: String): List<ValpixEvent>? {
        synchronized(seen) { seen[signature] }?.let { return it }
        val logs = rpc.transactionLogs(signature) ?: return null
        val found = ValpixEvent.events(logs, program.programId)
        synchronized(seen) { seen[signature] = found }
        return found
    }

    companion object {
        /** Finds "Error Code: Name." in a program log. */
        internal fun errorName(logs: List<String>): String? {
            for (line in logs) {
                val at = line.indexOf("Error Code: ")
                if (at < 0) continue
                return line.substring(at + "Error Code: ".length).takeWhile { it != '.' && it != ' ' }
            }
            return null
        }
    }
}
