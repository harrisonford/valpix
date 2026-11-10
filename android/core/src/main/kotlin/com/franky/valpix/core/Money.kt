package com.franky.valpix.core

import java.math.BigInteger

/**
 * The program's arithmetic, mirrored so the phone can show a balance and a
 * fee without asking the chain for each tick. Amounts are micro-CLPC.
 */
object Money {
    /** Micro-CLPC per CLPC. */
    const val UNIT: ULong = 1_000_000uL
    private const val SECONDS_PER_DAY: ULong = 86_400uL
    private val maxU64 = BigInteger(ULong.MAX_VALUE.toString())

    /** Income between two chain timestamps, rounded down. */
    fun accrued(dailyRate: ULong, from: Long, to: Long): ULong {
        if (to <= from) return 0uL
        val elapsed = (to - from).toULong()
        if (dailyRate != 0uL && elapsed > ULong.MAX_VALUE / dailyRate) return ULong.MAX_VALUE
        return elapsed * dailyRate / SECONDS_PER_DAY
    }

    /** CLPC the pool charges for `lamportsOut` of SOL, rounded up. */
    fun quoteClpcIn(solReserve: ULong, clpcReserve: ULong, lamportsOut: ULong): ULong? {
        if (lamportsOut >= solReserve) return null
        val divisor = BigInteger((solReserve - lamportsOut).toString())
        val product = BigInteger(clpcReserve.toString()) * BigInteger(lamportsOut.toString())
        val (quotient, remainder) = product.divideAndRemainder(divisor)
        val price = if (remainder.signum() == 0) quotient else quotient + BigInteger.ONE
        return if (price > maxU64) null else price.toString().toULong()
    }

    /**
     * The CLPC fee of a payment right now, or `null` when the pool will not
     * refund it (it is below its floor, or its price is above the cap), in
     * which case the payment costs no CLPC.
     */
    fun fee(solReserve: ULong, clpcReserve: ULong, refundLamports: ULong, floorLamports: ULong, maxFee: ULong): ULong? {
        if (solReserve < refundLamports || solReserve - refundLamports < floorLamports) return null
        val price = quoteClpcIn(solReserve, clpcReserve, refundLamports) ?: return null
        return if (price <= maxFee) price else null
    }

    /**
     * A member's balance at a moment of chain time. Fractions of a second
     * count, so the number on screen moves smoothly.
     */
    fun liveBalance(member: MemberRecord, dailyRate: ULong, chainNow: Double): ULong {
        val elapsed = chainNow - member.lastSettledTs.toDouble()
        if (!(elapsed > 0)) return member.settled
        val earned = elapsed * dailyRate.toDouble() / SECONDS_PER_DAY.toDouble()
        if (!(earned < (ULong.MAX_VALUE - member.settled).toDouble())) return ULong.MAX_VALUE
        return member.settled + earned.toULong()
    }

    /**
     * Whether a payment of `amountPesos` can be sent: it must be at least one
     * peso and, with its fee, fit inside the balance.
     */
    fun canPay(amountPesos: ULong, balance: ULong, fee: ULong?): Boolean {
        if (amountPesos == 0uL || amountPesos > ULong.MAX_VALUE / UNIT) return false
        val amount = amountPesos * UNIT
        val charge = fee ?: 0uL
        if (amount > ULong.MAX_VALUE - charge) return false
        return amount + charge <= balance
    }

    /** What can be spent: payments are made in whole pesos. */
    fun wholePesos(micro: ULong): ULong = micro / UNIT
    fun micro(pesos: ULong): ULong = pesos * UNIT
}

/**
 * Chain time from the phone's clock. The phone's clock may be wrong, so the
 * app measures the difference once and applies it from then on.
 */
data class ChainClock(
    /** Seconds to add to the phone's clock to get chain time. */
    val offset: Double,
) {
    fun now(atMillis: Long = System.currentTimeMillis()): Double = atMillis / 1000.0 + offset

    companion object {
        fun observed(chainTime: Double, atMillis: Long = System.currentTimeMillis()) = ChainClock(chainTime - atMillis / 1000.0)
    }
}

/** Chilean number format: dots for thousands, comma for decimals. */
object PesoFormat {
    data class Parts(
        /** Whole pesos, shown white. */
        val whole: String,
        /** Four decimal places, shown gray. */
        val decimals: String,
    )

    fun parts(micro: ULong): Parts {
        val fraction = (micro % Money.UNIT) / 100uL
        return Parts(whole(micro / Money.UNIT), fraction.toString().padStart(4, '0'))
    }

    fun whole(pesos: ULong): String = pesos.toString().reversed().chunked(3).joinToString(".").reversed()

    /** For history rows: a plus sign, or a true minus sign. */
    fun signed(pesos: ULong, incoming: Boolean): String = (if (incoming) "+" else "−") + whole(pesos)
}
