package com.franky.valpix.core

import java.io.IOException

// --- Which program wrote a log line ---

/**
 * The valpix events in a transaction's log, counting only lines written
 * while valpix itself was the running program. Any program can log the same
 * bytes; only valpix's own are evidence that money moved.
 */
fun ValpixEvent.Companion.events(logs: List<String>, programId: PublicKey): List<ValpixEvent> {
    val stack = ArrayList<String>()
    val found = ArrayList<ValpixEvent>()
    for (line in logs) {
        if (line.startsWith("Program data: ")) {
            if (stack.lastOrNull() == programId.base58) fromLogLine(line)?.let(found::add)
            continue
        }
        // "Program <id> invoke [n]" enters a program; "success" or "failed"
        // leaves it. Programs cannot forge these: the runtime prefixes
        // everything a program logs with "Program log: ".
        val parts = line.split(' ', limit = 4)
        if (parts.size < 3 || parts[0] != "Program") continue
        if (parts[2] == "invoke") {
            stack.add(parts[1])
        } else if (parts[2] == "success" || parts[2].startsWith("failed")) {
            if (stack.lastOrNull() == parts[1]) stack.removeAt(stack.size - 1)
        }
    }
    return found
}

// --- Did the payment a seller is waiting for arrive ---

/**
 * Whether this entry is the payment a QR on screen was asking for: an
 * incoming payment, no older than the QR, and at least the amount asked.
 */
fun HistoryEntry.settles(requestedPesos: ULong?, after: Double): Boolean {
    if (kind != HistoryEntry.Kind.RECEIVED || ts.toDouble() < after) return false
    if (requestedPesos == null) return true
    if (requestedPesos > ULong.MAX_VALUE / Money.UNIT) return false
    return amountMicro >= requestedPesos * Money.UNIT
}

// --- What a link may make the app do ---

enum class LinkSource {
    /** Read by the camera, or pasted, inside the app. */
    SCANNED,

    /** Opened from outside: a link tapped in another app. */
    EXTERNAL,
}

class WalletSituation(
    val own: PublicKey,
    val isMember: Boolean,
    /**
     * The chain has answered that this phone's key has no account. Without
     * that answer the key may be funded and merely out of sight.
     */
    val chainConfirmedNoAccount: Boolean,
)

/** What to offer the person next. Every action still needs their approval. */
sealed class LinkAction {
    data class Pay(val to: PublicKey, val amountPesos: ULong?, val name: String) : LinkAction()
    data class Invite(val key: PublicKey, val name: String) : LinkAction()

    class Adopt(val seed: ByteArray) : LinkAction() {
        override fun equals(other: Any?) = other is Adopt && seed.contentEquals(other.seed)
        override fun hashCode() = seed.contentHashCode()
    }

    class Unlock(val blob: ByteArray) : LinkAction() {
        override fun equals(other: Any?) = other is Unlock && blob.contentEquals(other.blob)
        override fun hashCode() = blob.contentHashCode()
    }

    data object Ignore : LinkAction()
}

object LinkPolicy {
    fun action(link: ValpixLink, source: LinkSource, situation: WalletSituation): LinkAction = when (link) {
        is ValpixLink.Pay ->
            if (situation.isMember && link.to != situation.own) LinkAction.Pay(link.to, link.amountPesos, link.name) else LinkAction.Ignore
        is ValpixLink.Join ->
            if (situation.isMember && link.key != situation.own) LinkAction.Invite(link.key, link.name) else LinkAction.Ignore
        is ValpixLink.Wallet ->
            if (mayReceiveWallet(source, situation) && Keypair.fromSeed(link.seed)?.publicKey != situation.own) LinkAction.Adopt(link.seed) else LinkAction.Ignore
        is ValpixLink.Backup ->
            if (mayReceiveWallet(source, situation)) LinkAction.Unlock(link.blob) else LinkAction.Ignore
    }

    /**
     * A wallet replaces the one on this phone, so it is offered only from the
     * in-app scanner and only once the chain says there is nothing here to lose.
     */
    private fun mayReceiveWallet(source: LinkSource, situation: WalletSituation) =
        source == LinkSource.SCANNED && !situation.isMember && situation.chainConfirmedNoAccount
}

// --- Keeping the wallet key ---

sealed class StoredSecret {
    class Found(val secret: ByteArray) : StoredSecret() {
        override fun equals(other: Any?) = other is Found && secret.contentEquals(other.secret)
        override fun hashCode() = secret.contentHashCode()
    }

    /** The store answered: nothing is there. */
    data object Missing : StoredSecret()

    /**
     * The store could not be read right now (a locked phone, a lost key).
     * This says nothing about whether a wallet exists.
     */
    data object Unavailable : StoredSecret()
}

/** Where the wallet's secret lives. The app backs this with the Android Keystore. */
interface SecretStore {
    fun read(): StoredSecret

    /** Stores a secret where there was none. Must not overwrite. */
    fun add(secret: ByteArray): Boolean

    /** Replaces the stored secret in place. */
    fun replace(secret: ByteArray): Boolean
}

/**
 * The only code that decides to create or replace a wallet. It never
 * deletes, and it creates only when the store has positively said there is
 * no wallet: a failed read is not an empty store.
 */
object WalletKeeper {
    /**
     * The stored wallet, or a new one stored just now. `null` when the store
     * cannot be relied on, in which case the app must not run on any key.
     */
    fun loadOrCreate(store: SecretStore): Keypair? = when (val stored = store.read()) {
        is StoredSecret.Found -> Keypair.fromSeed(stored.secret)
        StoredSecret.Unavailable -> null
        StoredSecret.Missing -> {
            val fresh = Keypair.generate()
            if (store.add(fresh.seed) && store.read() == StoredSecret.Found(fresh.seed)) fresh else null
        }
    }

    /** Puts another wallet on this phone. `null` when it was not kept. */
    fun replace(seed: ByteArray, store: SecretStore): Keypair? {
        val incoming = Keypair.fromSeed(seed) ?: return null
        val stored = when (store.read()) {
            is StoredSecret.Found -> store.replace(seed)
            StoredSecret.Missing -> store.add(seed)
            StoredSecret.Unavailable -> return null
        }
        return if (stored && store.read() == StoredSecret.Found(seed)) incoming else null
    }
}

// --- More than one access point ---

/**
 * Tries each access point in turn until one answers. An answer that is a
 * JSON-RPC error still counts as an answer.
 */
class FallbackTransport(private val transports: List<RpcTransport>) : RpcTransport {
    override fun post(body: String): String {
        var last = IOException("no access points configured")
        for (transport in transports) {
            try {
                return transport.post(body)
            } catch (e: IOException) {
                last = e
            }
        }
        throw last
    }
}
