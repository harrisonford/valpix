package com.franky.valpix.core

/** Bitcoin-alphabet Base58, the text form of every Solana address. */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val indexes = IntArray(128) { -1 }.also { for ((i, c) in ALPHABET.withIndex()) it[c.code] = i }

    fun encode(data: ByteArray): String {
        val zeros = data.indexOfFirst { it != 0.toByte() }.let { if (it < 0) data.size else it }
        // Repeated division of the big-endian number by 58.
        val digits = ArrayList<Int>()
        for (i in zeros until data.size) {
            var carry = data[i].toInt() and 0xff
            for (j in digits.indices) {
                carry += digits[j] shl 8
                digits[j] = carry % 58
                carry /= 58
            }
            while (carry > 0) {
                digits.add(carry % 58)
                carry /= 58
            }
        }
        val out = StringBuilder(zeros + digits.size)
        repeat(zeros) { out.append(ALPHABET[0]) }
        for (digit in digits.asReversed()) out.append(ALPHABET[digit])
        return out.toString()
    }

    /** `null` when the text holds a character outside the alphabet. */
    fun decode(text: String): ByteArray? {
        val zeros = text.indexOfFirst { it != ALPHABET[0] }.let { if (it < 0) text.length else it }
        val bytes = ArrayList<Int>()
        for (i in zeros until text.length) {
            val code = text[i].code
            val value = if (code < 128) indexes[code] else -1
            if (value < 0) return null
            var carry = value
            for (j in bytes.indices) {
                carry += bytes[j] * 58
                bytes[j] = carry and 0xff
                carry = carry shr 8
            }
            while (carry > 0) {
                bytes.add(carry and 0xff)
                carry = carry shr 8
            }
        }
        return ByteArray(zeros + bytes.size) { if (it < zeros) 0 else bytes[bytes.size - 1 - (it - zeros)].toByte() }
    }
}
