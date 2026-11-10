package com.franky.valpix.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

class BorshException(message: String) : Exception(message)

/**
 * Writes the binary layout Anchor programs expect: little-endian integers
 * and strings prefixed by a 32-bit length.
 */
class BorshWriter {
    private val out = ByteArrayOutputStream()
    fun toByteArray(): ByteArray = out.toByteArray()

    private fun littleEndian(value: Long, size: Int) { for (i in 0 until size) out.write((value ushr (8 * i)).toInt() and 0xff) }
    fun u8(v: Int) = out.write(v and 0xff)
    fun u32(v: UInt) = littleEndian(v.toLong(), 4)
    fun u64(v: ULong) = littleEndian(v.toLong(), 8)
    fun i64(v: Long) = littleEndian(v, 8)
    fun bytes(v: ByteArray) = out.write(v, 0, v.size)
    fun publicKey(v: PublicKey) = bytes(v.bytes)
    fun string(v: String) {
        val utf8 = v.toByteArray()
        u32(utf8.size.toUInt())
        bytes(utf8)
    }
}

class BorshReader(private val data: ByteArray) {
    private var offset = 0
    val isAtEnd: Boolean get() = offset == data.size

    private fun take(count: Long): ByteArray {
        if (count < 0 || data.size - offset < count) throw BorshException("unexpected end")
        val slice = data.copyOfRange(offset, offset + count.toInt())
        offset += count.toInt()
        return slice
    }

    private fun littleEndian(size: Int): Long {
        var value = 0L
        for ((i, byte) in take(size.toLong()).withIndex()) value = value or ((byte.toLong() and 0xff) shl (8 * i))
        return value
    }

    fun u8(): Int = littleEndian(1).toInt()
    fun u32(): UInt = littleEndian(4).toUInt()
    fun u64(): ULong = littleEndian(8).toULong()
    fun i64(): Long = littleEndian(8)
    fun bytes(count: Int): ByteArray = take(count.toLong())
    fun publicKey(): PublicKey = PublicKey.of(take(32))!!
    fun string(): String {
        val raw = take(u32().toLong())
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        try {
            return decoder.decode(ByteBuffer.wrap(raw)).toString()
        } catch (e: CharacterCodingException) {
            throw BorshException("invalid string")
        }
    }
}
