package com.franky.valpix.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyTest {
    private val unit = Money.UNIT
    private val rate = 1000uL * Money.UNIT
    private val sol = 1_000_000_000uL
    private val someone = key(Vectors.publicKey)
    private fun member(settled: ULong, at: Long) = MemberRecord(someone, settled, at, at, someone)

    @Test fun incomeMatchesTheProgram() {
        assertEquals(1000uL * unit, Money.accrued(rate, 0, 86_400))
        assertEquals(11_574uL, Money.accrued(rate, 100, 101))
        assertEquals(0uL, Money.accrued(rate, 500, 500))
        assertEquals(0uL, Money.accrued(rate, 500, 400))
        assertEquals(ULong.MAX_VALUE, Money.accrued(ULong.MAX_VALUE, 0, 2))
    }

    @Test fun poolPriceMatchesTheProgram() {
        assertEquals(1_000_006uL, Money.quoteClpcIn(sol, 200_000uL * unit, 5_000uL))
        assertNull(Money.quoteClpcIn(sol, 200_000uL * unit, sol))
        assertNull(Money.quoteClpcIn(sol, 200_000uL * unit, sol + 1uL))
        // A price that does not fit in 64 bits is no price.
        assertNull(Money.quoteClpcIn(3uL, ULong.MAX_VALUE, 2uL))
        assertEquals(ULong.MAX_VALUE, Money.quoteClpcIn(2uL, ULong.MAX_VALUE, 1uL))
    }

    @Test fun feeIsNilWhenThePoolWouldFallBelowItsFloor() {
        val clpc = 200_000uL * unit
        assertNull(Money.fee(1_004_000uL, clpc, 5_000uL, 1_000_000uL, ULong.MAX_VALUE))
        assertNull(Money.fee(4_000uL, clpc, 5_000uL, 0uL, ULong.MAX_VALUE))
        assertEquals(1_000_006uL, Money.fee(sol, clpc, 5_000uL, 1_000_000uL, 20uL * unit))
    }

    @Test fun feeIsNilWhenThePoolAsksMoreThanTheCap() {
        val clpc = 200_000uL * unit
        assertNull(Money.fee(1_010_000uL, clpc, 5_000uL, 1_000_000uL, 20uL * unit))
        assertEquals(1_000_006uL, Money.fee(sol, clpc, 5_000uL, 1_000_000uL, 1_000_006uL))
        assertNull(Money.fee(sol, clpc, 5_000uL, 1_000_000uL, 1_000_005uL))
    }

    @Test fun liveBalanceAddsIncomeSinceTheLastSettlement() {
        val m = member(5uL * unit, 1_000)
        assertEquals(505uL * unit, Money.liveBalance(m, rate, 1_000.0 + 43_200))
        assertEquals(5uL * unit + 5_787uL, Money.liveBalance(m, rate, 1_000.5))
        assertEquals(5uL * unit, Money.liveBalance(m, rate, 990.0))
        assertEquals(ULong.MAX_VALUE, Money.liveBalance(member(ULong.MAX_VALUE - 10uL, 0), rate, 86_400.0))
    }

    @Test fun liveBalance_uses_chain_offset() {
        // The phone's clock is an hour fast; the chain's time still rules.
        val phoneMillis = 10_000_000_000L
        val clock = ChainClock.observed(chainTime = 2_000.0, atMillis = phoneMillis)
        assertEquals(2_003.0, clock.now(phoneMillis + 3_000))
        assertEquals(unit * 1000uL, Money.liveBalance(member(0uL, 2_000), rate, clock.now(phoneMillis + 86_400_000)))
    }

    @Test fun refusesAmountsTheBalanceCannotCover() {
        val balance = 1000uL * unit
        assertFalse(Money.canPay(0uL, balance, null))
        assertTrue(Money.canPay(1000uL, balance, null))
        assertFalse(Money.canPay(1001uL, balance, null))
        assertTrue(Money.canPay(998uL, balance, 1_000_006uL))
        assertFalse(Money.canPay(999uL, balance, 1_000_006uL))
        assertFalse(Money.canPay(ULong.MAX_VALUE, ULong.MAX_VALUE, null))
        assertFalse(Money.canPay(18_446_744_073_709uL, ULong.MAX_VALUE, ULong.MAX_VALUE))
    }

    @Test fun spendableIsWholePesosOnly() {
        assertEquals(1uL, Money.wholePesos(1_999_999uL))
        assertEquals(0uL, Money.wholePesos(999_999uL))
        assertEquals(300_000_000uL, Money.micro(300uL))
    }

    @Test fun splitsIntoWhitePesosAndGrayDecimals() {
        assertEquals(PesoFormat.Parts("12.345", "2871"), PesoFormat.parts(12_345_287_199uL))
        assertEquals(PesoFormat.Parts("0", "0000"), PesoFormat.parts(0uL))
        assertEquals(PesoFormat.Parts("0", "9999"), PesoFormat.parts(999_999uL))
        assertEquals(PesoFormat.Parts("1", "0001"), PesoFormat.parts(1_000_100uL))
    }

    @Test fun groupsThousandsWithDots() {
        assertEquals("0", PesoFormat.whole(0uL))
        assertEquals("999", PesoFormat.whole(999uL))
        assertEquals("1.000", PesoFormat.whole(1_000uL))
        assertEquals("1.234.567", PesoFormat.whole(1_234_567uL))
        assertEquals("18.446.744.073.709.551.615", PesoFormat.whole(ULong.MAX_VALUE))
    }

    @Test fun signsHistoryAmounts() {
        assertEquals("+1.500", PesoFormat.signed(1_500uL, incoming = true))
        assertEquals("−300", PesoFormat.signed(300uL, incoming = false))
    }
}
