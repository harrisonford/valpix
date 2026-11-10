package com.franky.valpix.core

import java.security.MessageDigest

/**
 * A 32-byte Solana address: a wallet's public key, or an address a program
 * derives for its own records.
 */
class PublicKey private constructor(private val raw: ByteArray) {
    val bytes: ByteArray get() = raw.copyOf()
    val base58: String by lazy { Base58.encode(raw) }

    /** True for wallet keys, false for program-derived addresses. */
    val isOnCurve: Boolean get() = Ed25519Curve.isOnCurve(raw)

    override fun equals(other: Any?) = other is PublicKey && raw.contentEquals(other.raw)
    override fun hashCode() = raw.contentHashCode()
    override fun toString() = base58

    companion object {
        fun of(bytes: ByteArray): PublicKey? = if (bytes.size == 32) PublicKey(bytes.copyOf()) else null

        fun fromBase58(text: String): PublicKey? {
            // No address is longer than 44 characters; do not decode a book.
            if (text.length > 44) return null
            return Base58.decode(text)?.let(::of)
        }

        /**
         * The address a program owns for these seeds, and the bump that makes
         * it fall off the curve. Mirrors Solana's `find_program_address`.
         */
        fun findProgramAddress(seeds: List<ByteArray>, programId: PublicKey): Pair<PublicKey, Int>? {
            for (bump in 255 downTo 0) {
                val hasher = MessageDigest.getInstance("SHA-256")
                for (seed in seeds) hasher.update(seed)
                hasher.update(bump.toByte())
                hasher.update(programId.raw)
                hasher.update("ProgramDerivedAddress".toByteArray())
                val digest = hasher.digest()
                if (!Ed25519Curve.isOnCurve(digest)) return Pair(PublicKey(digest), bump)
            }
            return null
        }

        val SYSTEM_PROGRAM = PublicKey(ByteArray(32))
        val INSTRUCTIONS_SYSVAR = fromBase58("Sysvar1nstructions1111111111111111111111111")!!
    }
}
