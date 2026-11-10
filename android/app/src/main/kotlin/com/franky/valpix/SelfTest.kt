package com.franky.valpix

import android.util.Log
import com.franky.valpix.core.Base58
import com.franky.valpix.core.Keypair
import com.franky.valpix.core.ValpixLink
import com.franky.valpix.core.ValpixProgram
import com.franky.valpix.core.WalletBackup
import kotlin.concurrent.thread

/**
 * Debug builds only: `VALPIX_SELFTEST=1` in the debug launch file checks, on the phone itself, that
 * this Android's cryptography gives the same bytes as the iPhone's and
 * Solana's. The unit tests prove it on the Mac; this proves it on the device.
 * Read the result with `adb logcat -s valpix`.
 */
object SelfTest {
    private val seed = ByteArray(32) { (it + 1).toByte() }
    private const val PUBLIC_KEY = "9C6hybhQ6Aycep9jaUnP6uL9ZYvDjUp1aSkFWPUFJtpj"
    private const val SIGNATURE = "65d8a9a4cd4f0519634902a9c10e601b524b13a742aa9d8ce6e8cadded9d688dadb4efcfd4b2d1414530b7c9cee813da6d0d77346ca96a02e4c7c3bbe61da006"
    private const val CONFIG = "2YaReRAV33hBCp5ud4iNE1bnXg9hfXFBTS3vX842YCmq"
    /** Sealed by the Swift core; the same fixture the unit tests pin. */
    private const val BACKUP_FROM_SWIFT = "valpix:backup?d=AQOmdWr_IqZ30ayvAIjUGkUFJhiZFOGeyntPE9MCDRUzXD7XoO9Cc8TNwJ0wpbgQjTIcaTIIAKRPops8eoDZtS-7FplJAhScmyW-sg8"
    private const val PASSWORD = "contraseña ñandú 1"

    fun runIfAsked() {
        if (!BuildConfig.DEBUG || !AppConfig.flag("VALPIX_SELFTEST")) return
        thread(name = "valpix-selftest") {
            val failures = ArrayList<String>()
            fun check(name: String, ok: Boolean) { if (!ok) failures.add(name) }
            var backupMillis = -1L
            try {
                val pair = Keypair.fromSeed(seed)!!
                check("public key", pair.publicKey.base58 == PUBLIC_KEY)
                check("signature", pair.sign("valpix test message".toByteArray()).joinToString("") { "%02x".format(it) } == SIGNATURE)
                check("program address", ValpixProgram(AppConfig.programId).config.base58 == CONFIG)
                val blob = (ValpixLink.parse(BACKUP_FROM_SWIFT) as ValpixLink.Backup).blob
                val started = System.nanoTime()
                check("swift backup opens", WalletBackup.open(blob, PASSWORD)?.contentEquals(seed) == true)
                backupMillis = (System.nanoTime() - started) / 1_000_000
                check("wrong password stays shut", WalletBackup.open(blob, "contrasena nandu 1") == null)
                check("own backup round trip", WalletBackup.open(WalletBackup.seal(seed, PASSWORD), PASSWORD)?.contentEquals(seed) == true)
                check("base58", Base58.encode(seed) == "4wBqpZM9xaSheZzJSMawUKKwhdpChKbZ5eu5ky4Vigw")
            } catch (e: Throwable) {
                failures.add("threw $e")
            }
            if (failures.isEmpty()) Log.i("valpix", "selftest ok, backup opened in $backupMillis ms")
            else Log.e("valpix", "selftest FAILED: $failures")
        }
    }
}
