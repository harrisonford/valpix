package com.franky.valpix.core

import java.math.BigInteger

/**
 * Just enough of the Ed25519 curve to tell a wallet key from an address a
 * program derives: those are chosen to lie off the curve.
 */
internal object Ed25519Curve {
    private val p = BigInteger.TWO.pow(255) - BigInteger.valueOf(19)
    private val d = (BigInteger.valueOf(-121665) * BigInteger.valueOf(121666).modInverse(p)).mod(p)
    private val halfOrder = (p - BigInteger.ONE) / BigInteger.TWO

    /**
     * Whether 32 bytes decode to a point, as Solana's own check does: y is
     * the low 255 bits, and a point exists when x² = (y² − 1) / (d·y² + 1)
     * has a square root.
     */
    fun isOnCurve(bytes: ByteArray): Boolean {
        if (bytes.size != 32) return false
        val bigEndian = bytes.reversedArray()
        bigEndian[0] = (bigEndian[0].toInt() and 0x7f).toByte()
        val y = BigInteger(1, bigEndian).mod(p)
        val y2 = (y * y).mod(p)
        val u = (y2 - BigInteger.ONE).mod(p)
        val v = (d * y2 + BigInteger.ONE).mod(p) // never zero: −1/d is not a square
        val x2 = (u * v.modInverse(p)).mod(p)
        return x2.signum() == 0 || x2.modPow(halfOrder, p) == BigInteger.ONE
    }
}
