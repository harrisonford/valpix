package com.franky.valpix

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.franky.valpix.core.SecretStore
import com.franky.valpix.core.StoredSecret
import java.io.File
import java.io.FileOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the wallet key lives: sealed in a file in the app's private storage
 * by a key that never leaves the Android Keystore. It stays on this phone:
 * the app is excluded from backups and transfers.
 *
 * All decisions about creating or replacing a wallet are made by
 * `WalletKeeper` in the core, which is tested; this type only reads and
 * writes, and it never deletes.
 */
class KeyStore(context: Context) : SecretStore {
    private val file = File(context.filesDir, "wallet.bin")
    private val temp = File(context.filesDir, "wallet.tmp")

    private fun sealingKey(create: Boolean): SecretKey? {
        val keystore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun read(): StoredSecret = try {
        if (!file.exists()) {
            StoredSecret.Missing
        } else {
            val sealed = file.readBytes()
            // A sealed wallet with no key to open it: we cannot tell what
            // was there, so this is "unavailable", never "missing".
            val key = sealingKey(create = false)
            if (key == null || sealed.size <= IV_SIZE) {
                StoredSecret.Unavailable
            } else {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, IV_SIZE))
                StoredSecret.Found(cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE))
            }
        }
    } catch (e: Exception) {
        // Keystore trouble, a damaged file, anything else: we do not know
        // whether a wallet exists.
        StoredSecret.Unavailable
    }

    override fun add(secret: ByteArray): Boolean = !file.exists() && write(secret)

    override fun replace(secret: ByteArray): Boolean = file.exists() && write(secret)

    /** Writes beside the wallet and swaps it in, so a crash cannot leave half a file. */
    private fun write(secret: ByteArray): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, sealingKey(create = true))
        val sealed = cipher.iv + cipher.doFinal(secret)
        FileOutputStream(temp).use {
            it.write(sealed)
            it.fd.sync()
        }
        cipher.iv.size == IV_SIZE && temp.renameTo(file)
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val ALIAS = "valpix.wallet"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
