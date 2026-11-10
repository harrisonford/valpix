package com.franky.valpix

import android.app.Application
import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.franky.valpix.core.FailureReason
import com.franky.valpix.core.HistoryEntry
import com.franky.valpix.core.Keypair
import com.franky.valpix.core.LinkAction
import com.franky.valpix.core.LinkPolicy
import com.franky.valpix.core.LinkSource
import com.franky.valpix.core.Money
import com.franky.valpix.core.Outcome
import com.franky.valpix.core.PesoFormat
import com.franky.valpix.core.PublicKey
import com.franky.valpix.core.SolanaRpc
import com.franky.valpix.core.ValpixClient
import com.franky.valpix.core.ValpixLink
import com.franky.valpix.core.ValpixProgram
import com.franky.valpix.core.WalletBackup
import com.franky.valpix.core.WalletKeeper
import com.franky.valpix.core.WalletSituation
import com.franky.valpix.core.WalletState
import com.franky.valpix.core.settles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext

/** The app's state and everything a person can do in it. */
class AppModel(private val app: Application) {
    enum class Phase { LOADING, NEEDS_JOIN, READY, STORAGE_FAILED }

    sealed class Flow {
        data object Charge : Flow()
        data object Scan : Flow()
        data class Confirm(val to: PublicKey, val amountPesos: ULong?, val name: String) : Flow()
        data class Invite(val key: PublicKey, val name: String) : Flow()
        class Adopt(val seed: ByteArray) : Flow()
        class Unlock(val blob: ByteArray) : Flow()
        data class Sending(val amountPesos: ULong, val name: String) : Flow()
        data class Done(val amountPesos: ULong?, val name: String, val incoming: Boolean) : Flow()
        data class Failed(val amountPesos: ULong?, val reason: FailureReason) : Flow()
        data object History : Flow()
        data object Wallet : Flow()
    }

    private val prefs = app.getSharedPreferences("valpix", Context.MODE_PRIVATE)
    private val store = KeyStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var phase by mutableStateOf(Phase.LOADING)
        private set
    var state by mutableStateOf<WalletState?>(null)
        private set

    /** Whether an access point answered the last time we asked. */
    var hasRoute by mutableStateOf(true)
        private set
    var history by mutableStateOf<List<HistoryEntry>>(emptyList())
        private set

    /** Until the person has unlocked the app with fingerprint, face or screen lock. */
    var locked by mutableStateOf(true)
        private set

    /** A payment, invitation or wallet change is being approved or sent. */
    var busy by mutableStateOf(false)
        private set
    var flow by mutableStateOf<Flow?>(null)
    var publicKey: PublicKey
        private set

    private var nameState by mutableStateOf(prefs.getString("name", "") ?: "")

    /** The person's own name, kept only on this phone. */
    var name: String
        get() = nameState
        set(value) {
            nameState = value
            prefs.edit().putString("name", value).apply()
        }

    /** How the app asks for fingerprint, face or screen lock. Set by the screen that is showing. */
    var authenticate: (suspend (reason: String) -> Boolean)? = null

    private var keypair: Keypair

    /** The chain has answered that this phone's key has no account. */
    private var chainConfirmedNoAccount = false
    private var client: ValpixClient
    private val names = HashMap<String, String>()
    private var refreshJob: Job? = null
    private var authenticating = false
    private var visible = false
    private var pendingLink: Pair<String, LinkSource>? = null

    init {
        val stored = WalletKeeper.loadOrCreate(store)
        if (stored != null) {
            keypair = stored
        } else {
            // The key store could not be read or would not keep a key. Never
            // operate on a key that is not safely stored.
            keypair = Keypair.generate()
            phase = Phase.STORAGE_FAILED
        }
        publicKey = keypair.publicKey
        client = makeClient()
        try {
            val saved = JSONObject(prefs.getString("names", "{}") ?: "{}")
            for (key in saved.keys()) names[key] = saved.getString(key)
        } catch (e: Exception) {
            // Remembered names are a convenience; start again without them.
        }
        // Start from what this phone last knew, so a member who opens the app
        // with no route still sees their number ticking.
        if (phase != Phase.STORAGE_FAILED) {
            val saved = prefs.getString("state", null)
                ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
                ?.let(WalletState::fromSnapshot)
            if (saved?.member?.wallet == publicKey) {
                state = saved
                phase = Phase.READY
            }
        }
        writeDebugKey()
    }

    private fun makeClient() =
        ValpixClient(SolanaRpc(AppConfig.transport(accessPoint)), ValpixProgram(AppConfig.programId))

    /** The name sent with payments and shown in QR codes: at most 32 bytes. */
    val sharedName: String get() = ValpixLink.shareableName(name)

    /** The person's own access point, tried before the built-in ones. */
    var accessPoint: String
        get() = prefs.getString("accessPoint", "") ?: ""
        set(value) {
            prefs.edit().putString("accessPoint", value.trim()).apply()
            client = makeClient()
        }

    // --- Lock, and coming and going ---

    /** The app is on screen: ask to unlock, and keep up with the chain. */
    fun foreground() {
        visible = true
        if (phase == Phase.STORAGE_FAILED) return
        requestUnlock()
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            while (isActive) {
                refresh()
                delay(if (phase != Phase.READY) 2_000 else 5_000)
            }
        }
    }

    /** The app left the screen: stop asking the chain, and lock. */
    fun background() {
        visible = false
        // The system's own unlock screen covers the app while it asks.
        if (authenticating) return
        lock()
    }

    private fun lock() {
        refreshJob?.cancel()
        refreshJob = null
        locked = true
        flow = null
    }

    fun requestUnlock() {
        if (!locked || authenticating) return
        scope.launch {
            if (confirm("Abrir valpix")) {
                locked = false
                openPending()
            }
        }
    }

    private suspend fun confirm(reason: String): Boolean {
        val ask = authenticate ?: return false
        authenticating = true
        try {
            return ask(reason)
        } finally {
            authenticating = false
            // The person walked away while being asked.
            if (!visible) lock()
        }
    }

    // --- Keeping up with the chain ---

    suspend fun refresh() {
        val wallet = publicKey
        val asking = client
        try {
            val fresh = withContext(Dispatchers.IO) { asking.state(wallet) }
            if (wallet != publicKey) return
            state = fresh
            prefs.edit().putString("state", Base64.encodeToString(fresh.snapshot(), Base64.NO_WRAP)).apply()
            hasRoute = true
            phase = if (fresh.member == null) Phase.NEEDS_JOIN else Phase.READY
            chainConfirmedNoAccount = fresh.member == null
            openPending()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (wallet != publicKey) return
            hasRoute = false
            // Without a route we cannot tell a member from a newcomer; keep
            // whatever we last knew, and show the join code only if nothing.
            if (phase == Phase.LOADING && state == null) phase = Phase.NEEDS_JOIN
        }
    }

    suspend fun loadHistory() {
        val wallet = publicKey
        val asking = client
        val entries = try {
            withContext(Dispatchers.IO) { asking.history(wallet, 25) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        history = entries
        for (entry in entries) {
            if (entry.kind == HistoryEntry.Kind.RECEIVED) remember(entry.name, entry.counterparty)
        }
    }

    // --- Money ---

    fun balance(atMillis: Long): ULong = state?.balance(atMillis) ?: 0uL

    fun canPay(amountPesos: ULong): Boolean {
        val current = state ?: return false
        return hasRoute && Money.canPay(amountPesos, current.balance(System.currentTimeMillis()), current.fee)
    }

    fun pay(to: PublicKey, amountPesos: ULong, recipientName: String) {
        if (busy || !canPay(amountPesos)) return
        busy = true
        scope.launch {
            try {
                if (!confirm("Aprobar pago de ${PesoFormat.whole(amountPesos)}")) return@launch
                remember(recipientName, to)
                flow = Flow.Sending(amountPesos, recipientName)
                val signer = keypair
                val sending = client
                val sentName = sharedName
                val outcome = withContext(Dispatchers.IO) { sending.pay(signer, to, Money.micro(amountPesos), sentName) }
                flow = when (outcome) {
                    is Outcome.Confirmed -> Flow.Done(amountPesos, recipientName, incoming = false)
                    is Outcome.Failed -> Flow.Failed(amountPesos, outcome.reason)
                }
                refresh()
            } finally {
                busy = false
            }
        }
    }

    fun invite(newcomer: PublicKey, newcomerName: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                if (!confirm("Invitar a ${newcomerName.ifEmpty { "una persona" }}")) return@launch
                remember(newcomerName, newcomer)
                flow = Flow.Sending(0uL, newcomerName)
                val signer = keypair
                val sending = client
                flow = when (val outcome = withContext(Dispatchers.IO) { sending.join(signer, newcomer) }) {
                    is Outcome.Confirmed -> Flow.Done(null, newcomerName, incoming = false)
                    is Outcome.Failed -> Flow.Failed(null, outcome.reason)
                }
                refresh()
            } finally {
                busy = false
            }
        }
    }

    /** What bringing someone in costs right now, in whole pesos, if the pool can cover it. */
    val inviteCostPesos: ULong?
        get() {
            val current = state ?: return null
            // Record rent is not known to the phone exactly; the float plus the
            // refund plus a typical record deposit is a close estimate.
            val lamports = current.config.floatLamports + current.config.feeRefundLamports + 1_700_000uL
            val price = Money.quoteClpcIn(current.solReserve, current.pool.clpcReserve, lamports) ?: return null
            return (price + current.config.joinSurcharge + Money.UNIT - 1uL) / Money.UNIT
        }

    /**
     * Waits for a payment to arrive after `start` (chain time). Used while a
     * QR is on screen.
     */
    suspend fun nextIncomingPayment(requestedPesos: ULong?, start: Double): HistoryEntry? {
        val wallet = publicKey
        val asking = client
        while (coroutineContext.isActive) {
            val entries = try {
                withContext(Dispatchers.IO) { asking.history(wallet, 5) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            entries.firstOrNull { it.settles(requestedPesos, start) }?.let { return it }
            delay(1_500)
        }
        return null
    }

    val chainNow: Double get() = state?.clock?.now() ?: (System.currentTimeMillis() / 1000.0)

    // --- Links (scanned QR codes and opened valpix: links) ---

    /**
     * Handles a scanned code or an opened link. Returns false when it is not
     * a valpix link or there is nothing to do with it. With `whenReady`, a
     * link that arrives while the app is still locked or loading is kept
     * until it can be offered.
     */
    fun open(text: String, source: LinkSource, whenReady: Boolean = false): Boolean {
        if (phase == Phase.STORAGE_FAILED) return false
        val link = ValpixLink.parse(text) ?: return false
        if (locked || phase == Phase.LOADING) {
            if (whenReady) pendingLink = Pair(text, source)
            return false
        }
        val situation = WalletSituation(publicKey, phase == Phase.READY, chainConfirmedNoAccount)
        flow = when (val action = LinkPolicy.action(link, source, situation)) {
            is LinkAction.Pay -> Flow.Confirm(action.to, action.amountPesos, action.name)
            is LinkAction.Invite -> Flow.Invite(action.key, action.name)
            is LinkAction.Adopt -> Flow.Adopt(action.seed)
            is LinkAction.Unlock -> Flow.Unlock(action.blob)
            LinkAction.Ignore -> return false
        }
        return true
    }

    private fun openPending() {
        if (locked || phase == Phase.LOADING) return
        pendingLink?.let { (text, source) ->
            pendingLink = null
            open(text, source)
        }
        openDebugLinkOnce()
    }

    /**
     * Replaces this phone's wallet with a scanned one, after the person
     * approves. `LinkPolicy` only offers this while the chain says this
     * phone has no account, so it can never overwrite a funded wallet.
     */
    suspend fun adopt(seed: ByteArray): Boolean {
        if (busy || phase != Phase.NEEDS_JOIN || !chainConfirmedNoAccount) return false
        busy = true
        try {
            if (!confirm("Usar esta billetera en este teléfono")) return false
            val imported = WalletKeeper.replace(seed, store) ?: return false
            keypair = imported
            publicKey = imported.publicKey
            prefs.edit().remove("state").apply()
            state = null
            chainConfirmedNoAccount = false
            phase = Phase.LOADING
            flow = null
            writeDebugKey()
        } finally {
            busy = false
        }
        refresh()
        return true
    }

    suspend fun unlock(blob: ByteArray, password: String): Boolean {
        val seed = withContext(Dispatchers.Default) { WalletBackup.open(blob, password) } ?: return false
        return adopt(seed)
    }

    /** Asks for approval, then gives the QR contents that ARE the wallet. */
    suspend fun walletLink(): String? =
        if (confirm("Mostrar tu billetera")) ValpixLink.Wallet(keypair.seed).string else null

    /** Asks for approval, then gives the wallet sealed with a password. */
    suspend fun backupLink(password: String): String? {
        if (password.isEmpty() || !confirm("Crear respaldo")) return null
        val seed = keypair.seed
        return withContext(Dispatchers.Default) { ValpixLink.Backup(WalletBackup.seal(seed, password)).string }
    }

    // --- Names seen on this phone ---

    fun remember(name: String, key: PublicKey) {
        if (name.isEmpty()) return
        names[key.base58] = name
        prefs.edit().putString("names", JSONObject(names as Map<*, *>).toString()).apply()
    }

    fun displayName(key: PublicKey, fallback: String = ""): String {
        names[key.base58]?.let { return it }
        if (fallback.isNotEmpty()) return fallback
        return key.base58.take(4) + "…" + key.base58.takeLast(4)
    }

    // --- Debug builds only ---

    private var debugLinkOpened = false

    /**
     * `VALPIX_OPEN=<valpix: link>` in the debug launch file is treated as a
     * scanned code once the app is ready, so a script can stand in for the
     * camera.
     */
    private fun openDebugLinkOnce() {
        if (!BuildConfig.DEBUG || debugLinkOpened) return
        val text = AppConfig.value("VALPIX_OPEN") ?: return
        debugLinkOpened = true
        when (text) {
            "history" -> flow = Flow.History
            "charge" -> flow = Flow.Charge
            "wallet" -> flow = Flow.Wallet
            else -> open(text, LinkSource.SCANNED)
        }
    }

    /** The debug settings changed while the app was running. */
    fun debugRelaunched() {
        if (!BuildConfig.DEBUG) return
        debugLinkOpened = false
        client = makeClient()
        flow = null
        openPending()
    }

    /** Lets a script on the Mac learn this wallet's address. */
    private fun writeDebugKey() {
        if (!BuildConfig.DEBUG) return
        runCatching { File(app.filesDir, "debug-pubkey.txt").writeText(publicKey.base58) }
        Log.i("valpix", "wallet ${publicKey.base58}")
    }
}
