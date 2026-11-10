package com.franky.valpix

import android.content.Context
import com.franky.valpix.core.FallbackTransport
import com.franky.valpix.core.HttpTransport
import com.franky.valpix.core.PublicKey
import com.franky.valpix.core.RpcTransport
import java.io.File

/** Where the app reaches Solana and which program it talks to. */
object AppConfig {
    val programId: PublicKey = PublicKey.fromBase58(BuildConfig.PROGRAM_ID)!!

    /**
     * Access points, tried in order: the person's own (if they set one),
     * then the list the app ships with.
     */
    fun accessPoints(own: String): List<String> {
        debug["VALPIX_RPC"]?.let { return listOf(it) }
        return (listOf(own) + BuildConfig.ACCESS_POINTS.split(","))
            .map(String::trim)
            .filter { it.startsWith("https://") || it.startsWith("http://") }
    }

    fun transport(own: String): RpcTransport = FallbackTransport(accessPoints(own).map { HttpTransport(it) })

    // Debug builds only: settings that let a script on the Mac drive the app,
    // standing in for the camera and the fingerprint. They are read from a
    // file in the app's private storage, which only `adb run-as` can write:
    // never from the launch intent, which any app or web page could forge to
    // make the app pay without approval. See android/scripts/launch.sh.
    private val debug = HashMap<String, String>()
    private val names = setOf("VALPIX_RPC", "VALPIX_OPEN", "VALPIX_CHARGE", "VALPIX_AUTOCONFIRM", "VALPIX_SKIP_AUTH", "VALPIX_SELFTEST")

    /**
     * Takes the settings left for this launch, once: the file is removed as
     * it is read. Returns true when there were any.
     */
    fun readDebugLaunch(context: Context): Boolean {
        if (!BuildConfig.DEBUG) return false
        val file = File(context.filesDir, "debug-launch")
        val text = runCatching { file.readText() }.getOrNull() ?: return false
        file.delete()
        debug.clear()
        for (line in text.lines()) {
            val name = line.substringBefore('=', "")
            if (name in names) debug[name] = line.substringAfter('=')
        }
        return debug.isNotEmpty()
    }

    /** Forgets the settings, so they never outlive the run they were for. Returns true when there were any. */
    fun clearDebug(): Boolean = debug.isNotEmpty().also { debug.clear() }

    fun flag(name: String): Boolean = debug[name] == "1"
    fun value(name: String): String? = debug[name]
}
