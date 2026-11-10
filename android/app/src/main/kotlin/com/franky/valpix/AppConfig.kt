package com.franky.valpix

import android.content.Intent
import com.franky.valpix.core.FallbackTransport
import com.franky.valpix.core.HttpTransport
import com.franky.valpix.core.PublicKey
import com.franky.valpix.core.RpcTransport

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

    // Debug builds only: launch extras that let a script on the Mac drive the
    // app over adb, standing in for the camera and the fingerprint.
    //   adb shell am start -n com.franky.valpix/.MainActivity --es VALPIX_OPEN charge --es VALPIX_CHARGE 100 ...
    private val debug = HashMap<String, String>()
    private val names = listOf("VALPIX_RPC", "VALPIX_OPEN", "VALPIX_CHARGE", "VALPIX_AUTOCONFIRM", "VALPIX_SKIP_AUTH", "VALPIX_SELFTEST")

    /** Returns true when the launch carried any debug extra. */
    fun readDebugExtras(intent: Intent?): Boolean {
        if (!BuildConfig.DEBUG || intent == null) return false
        var found = false
        for (name in names) {
            val value = intent.getStringExtra(name) ?: continue
            debug[name] = value
            found = true
        }
        return found
    }

    fun flag(name: String): Boolean = debug[name] == "1"
    fun value(name: String): String? = debug[name]
}
