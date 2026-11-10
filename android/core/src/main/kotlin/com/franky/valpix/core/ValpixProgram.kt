package com.franky.valpix.core

import java.security.MessageDigest
import java.util.Base64

/**
 * The valpix program as the app sees it: where its records live, how to ask
 * it to do things, and how to read what it stores and logs.
 */
class ValpixProgram(val programId: PublicKey) {
    private fun address(vararg seeds: ByteArray) = PublicKey.findProgramAddress(seeds.toList(), programId)!!.first

    val config: PublicKey = address("config".toByteArray())
    val pool: PublicKey = address("pool".toByteArray())
    val vault: PublicKey = address("vault".toByteArray())

    /** The address of a wallet's member record. */
    fun member(wallet: PublicKey): PublicKey = address("member".toByteArray(), wallet.bytes)

    /** The accounts every pool-funded instruction ends with. */
    private val poolAccounts: List<AccountMeta>
        get() = listOf(
            AccountMeta(config),
            AccountMeta(pool, isWritable = true),
            AccountMeta(vault, isWritable = true),
            AccountMeta(PublicKey.SYSTEM_PROGRAM),
            AccountMeta(PublicKey.INSTRUCTIONS_SYSVAR),
        )

    /** Moves `amountMicro` micro-CLPC to another member. */
    fun pay(payer: PublicKey, recipient: PublicKey, amountMicro: ULong, name: String): Instruction {
        val data = BorshWriter()
        data.bytes(discriminator("global", "pay"))
        data.u64(amountMicro)
        data.string(name)
        return Instruction(
            programId,
            listOf(
                AccountMeta(payer, isSigner = true, isWritable = true),
                AccountMeta(member(payer), isWritable = true),
                AccountMeta(member(recipient), isWritable = true),
            ) + poolAccounts,
            data.toByteArray(),
        )
    }

    /** Creates an account for a newcomer, paid for by the inviter. */
    fun join(inviter: PublicKey, newcomer: PublicKey): Instruction = Instruction(
        programId,
        listOf(
            AccountMeta(inviter, isSigner = true, isWritable = true),
            AccountMeta(member(inviter), isWritable = true),
            AccountMeta(newcomer, isWritable = true),
            AccountMeta(member(newcomer), isWritable = true),
        ) + poolAccounts,
        discriminator("global", "join"),
    )

    /** Tops the caller's own wallet back up to the float. */
    fun reviveSelf(payer: PublicKey): Instruction = Instruction(
        programId,
        listOf(
            AccountMeta(payer, isSigner = true, isWritable = true),
            AccountMeta(member(payer), isWritable = true),
        ) + poolAccounts,
        discriminator("global", "revive_self"),
    )

    companion object {
        /** Anchor names each instruction by the first 8 bytes of a hash. */
        internal fun discriminator(namespace: String, name: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest("$namespace:$name".toByteArray()).copyOf(8)
    }
}

/** Reads a record, returning `null` unless it is the expected kind and whole. */
private fun <T> decodeRecord(accountData: ByteArray, name: String, body: (BorshReader) -> T): T? {
    val tag = ValpixProgram.discriminator("account", name)
    if (accountData.size < 8 || !accountData.copyOf(8).contentEquals(tag)) return null
    return try {
        body(BorshReader(accountData.copyOfRange(8, accountData.size)))
    } catch (e: BorshException) {
        null
    }
}

data class ConfigRecord(
    val admin: PublicKey,
    val dailyRate: ULong,
    val feeRefundLamports: ULong,
    val floatLamports: ULong,
    val joinSurcharge: ULong,
    val reviveThresholdLamports: ULong,
    val revivePeriodSecs: Long,
    val poolFloorLamports: ULong,
    /** The most a payment may be charged; above it the pool sells no refund. */
    val maxFee: ULong,
) {
    companion object {
        fun decode(accountData: ByteArray): ConfigRecord? = decodeRecord(accountData, "Config") { r ->
            ConfigRecord(r.publicKey(), r.u64(), r.u64(), r.u64(), r.u64(), r.u64(), r.i64(), r.u64(), r.u64())
        }
    }
}

data class PoolRecord(val clpcReserve: ULong) {
    companion object {
        fun decode(accountData: ByteArray): PoolRecord? = decodeRecord(accountData, "Pool") { r -> PoolRecord(r.u64()) }
    }
}

data class MemberRecord(
    val wallet: PublicKey,
    /** Micro-CLPC as of `lastSettledTs`; income since then is added on top. */
    val settled: ULong,
    val lastSettledTs: Long,
    val createdTs: Long,
    val inviter: PublicKey,
    val lastReviveTs: Long = 0,
) {
    companion object {
        fun decode(accountData: ByteArray): MemberRecord? = decodeRecord(accountData, "Member") { r ->
            MemberRecord(r.publicKey(), r.u64(), r.i64(), r.i64(), r.publicKey(), r.i64())
        }
    }
}

/** What the program writes to a transaction's log. */
sealed class ValpixEvent {
    data class Paid(
        val payer: PublicKey, val recipient: PublicKey, val amount: ULong,
        val fee: ULong, val name: String, val ts: Long,
    ) : ValpixEvent()

    data class Joined(val inviter: PublicKey, val newcomer: PublicKey, val cost: ULong, val ts: Long) : ValpixEvent()

    companion object {
        private const val PREFIX = "Program data: "

        /** Decodes one log line, or `null` when it is not a valpix event. */
        fun fromLogLine(line: String): ValpixEvent? {
            if (!line.startsWith(PREFIX)) return null
            val bytes = try {
                Base64.getDecoder().decode(line.substring(PREFIX.length))
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (bytes.size < 8) return null
            val tag = bytes.copyOf(8)
            val r = BorshReader(bytes.copyOfRange(8, bytes.size))
            return try {
                when {
                    tag.contentEquals(ValpixProgram.discriminator("event", "Paid")) ->
                        Paid(r.publicKey(), r.publicKey(), r.u64(), r.u64(), r.string(), r.i64())
                    tag.contentEquals(ValpixProgram.discriminator("event", "Joined")) ->
                        Joined(r.publicKey(), r.publicKey(), r.u64(), r.i64())
                    else -> null
                }
            } catch (e: BorshException) {
                null
            }
        }
    }
}
