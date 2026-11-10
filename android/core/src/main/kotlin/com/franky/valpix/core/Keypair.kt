package com.franky.valpix.core

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/** An Ed25519 wallet key. The 32-byte seed is the whole secret. */
class Keypair private constructor(private val key: Ed25519PrivateKeyParameters) {
    val publicKey: PublicKey = PublicKey.of(key.generatePublicKey().encoded)!!
    val seed: ByteArray get() = key.encoded

    /** A 64-byte signature. */
    fun sign(message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, key)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    companion object {
        /** A new random wallet. */
        fun generate(): Keypair = Keypair(Ed25519PrivateKeyParameters(SecureRandom()))

        fun fromSeed(seed: ByteArray): Keypair? =
            if (seed.size == 32) Keypair(Ed25519PrivateKeyParameters(seed, 0)) else null
    }
}
