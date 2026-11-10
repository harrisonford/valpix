package com.franky.valpix.core

import java.io.ByteArrayOutputStream

data class AccountMeta(val publicKey: PublicKey, val isSigner: Boolean = false, val isWritable: Boolean = false)

class Instruction(val programId: PublicKey, val accounts: List<AccountMeta>, val data: ByteArray)

class MissingSignerException(val key: PublicKey) : Exception("no signer for $key")

/** A compiled legacy Solana message: what gets signed. */
class Message internal constructor(
    val requiredSignatures: Int,
    private val readonlySigned: Int,
    private val readonlyUnsigned: Int,
    val accountKeys: List<PublicKey>,
    val recentBlockhash: ByteArray,
    private val instructions: List<Compiled>,
) {
    internal class Compiled(val programIndex: Int, val accountIndexes: List<Int>, val data: ByteArray)

    fun serialize(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(requiredSignatures)
        out.write(readonlySigned)
        out.write(readonlyUnsigned)
        out.write(Transaction.compactU16(accountKeys.size))
        for (key in accountKeys) out.write(key.bytes)
        out.write(recentBlockhash)
        out.write(Transaction.compactU16(instructions.size))
        for (ix in instructions) {
            out.write(ix.programIndex)
            out.write(Transaction.compactU16(ix.accountIndexes.size))
            for (index in ix.accountIndexes) out.write(index)
            out.write(Transaction.compactU16(ix.data.size))
            out.write(ix.data)
        }
        return out.toByteArray()
    }
}

object Transaction {
    /** Solana's variable-length encoding for small counts. */
    fun compactU16(value: Int): ByteArray {
        var remaining = value
        val out = ByteArrayOutputStream()
        do {
            var byte = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining > 0) byte = byte or 0x80
            out.write(byte)
        } while (remaining > 0)
        return out.toByteArray()
    }

    /**
     * Gathers every account the instructions touch into the single ordered
     * list Solana wants: the fee payer first, then other signers, then the
     * rest, with writable accounts ahead of read-only ones in each group.
     */
    fun compile(instructions: List<Instruction>, feePayer: PublicKey, recentBlockhash: ByteArray): Message {
        // Insertion order is kept; the pair is (signer, writable).
        val flags = LinkedHashMap<PublicKey, Pair<Boolean, Boolean>>()
        fun add(key: PublicKey, signer: Boolean, writable: Boolean) {
            val existing = flags[key]
            flags[key] = if (existing == null) Pair(signer, writable) else Pair(existing.first || signer, existing.second || writable)
        }
        add(feePayer, signer = true, writable = true)
        for (ix in instructions) {
            for (meta in ix.accounts) add(meta.publicKey, meta.isSigner, meta.isWritable)
            add(ix.programId, signer = false, writable = false)
        }

        fun group(signer: Boolean, writable: Boolean) = flags.filterValues { it == Pair(signer, writable) }.keys.toList()
        val keys = group(true, true) + group(true, false) + group(false, true) + group(false, false)
        val index = keys.withIndex().associate { it.value to it.index }

        return Message(
            requiredSignatures = flags.values.count { it.first },
            readonlySigned = group(true, false).size,
            readonlyUnsigned = group(false, false).size,
            accountKeys = keys,
            recentBlockhash = recentBlockhash.copyOf(),
            instructions = instructions.map { ix ->
                Message.Compiled(index.getValue(ix.programId), ix.accounts.map { index.getValue(it.publicKey) }, ix.data)
            },
        )
    }

    /** The bytes to hand to `sendTransaction`: signatures, then the message. */
    fun sign(message: Message, signers: List<Keypair>): ByteArray {
        val body = message.serialize()
        val out = ByteArrayOutputStream()
        out.write(compactU16(message.requiredSignatures))
        for (key in message.accountKeys.take(message.requiredSignatures)) {
            val signer = signers.firstOrNull { it.publicKey == key } ?: throw MissingSignerException(key)
            out.write(signer.sign(body))
        }
        out.write(body)
        return out.toByteArray()
    }
}
