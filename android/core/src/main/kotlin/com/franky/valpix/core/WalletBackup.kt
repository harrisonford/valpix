package com.franky.valpix.core

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom

/**
 * Seals a wallet seed with a password, for a backup QR that can be saved or
 * printed. Layout: version (1) | salt (16) | nonce (12) | ciphertext (32) | tag (16).
 *
 * The key comes from PBKDF2-HMAC-SHA256, so guessing a password costs an
 * attacker real time per try. A weak password is still a weak lock.
 */
object WalletBackup {
    private const val VERSION: Byte = 1
    private const val ITERATIONS = 600_000
    private const val SIZE = 1 + 16 + 12 + 32 + 16

    /** Throws `IllegalArgumentException` for an empty password or a seed that is not 32 bytes. */
    fun seal(seed: ByteArray, password: String): ByteArray {
        require(password.isNotEmpty()) { "empty password" }
        require(seed.size == 32) { "bad seed" }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        // Sealing with a fresh random nonce cannot fail.
        return byteArrayOf(VERSION) + salt + nonce + crypt(true, derive(password, salt), nonce, seed)
    }

    /** The seed, or `null` when the password is wrong or the blob was altered. */
    fun open(blob: ByteArray, password: String): ByteArray? {
        if (blob.size != SIZE || blob[0] != VERSION || password.isEmpty()) return null
        val key = derive(password, blob.copyOfRange(1, 17))
        return try {
            crypt(false, key, blob.copyOfRange(17, 29), blob.copyOfRange(29, SIZE))
        } catch (e: InvalidCipherTextException) {
            null
        }
    }

    private fun crypt(sealing: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(sealing, AEADParameters(KeyParameter(key), 128, nonce))
        val out = ByteArray(cipher.getOutputSize(input.size))
        val written = cipher.processBytes(input, 0, input.size, out, 0)
        cipher.doFinal(out, written)
        return out
    }

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val generator = PKCS5S2ParametersGenerator(SHA256Digest())
        generator.init(password.toByteArray(Charsets.UTF_8), salt, ITERATIONS)
        return (generator.generateDerivedParameters(256) as KeyParameter).key
    }
}
