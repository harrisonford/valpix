import Foundation
import Testing
@testable import ValpixCore

private let rate: UInt64 = 1000 * Money.unit
private let someone = PublicKey(base58: Vectors.publicKey)!

private func member(settled: UInt64, at ts: Int64) -> MemberRecord {
    MemberRecord(wallet: someone, settled: settled, lastSettledTs: ts, createdTs: ts, inviter: someone)
}

@Suite struct MoneyTests {
    // The same numbers the program's own tests assert, so both sides agree.
    @Test func incomeMatchesTheProgram() {
        #expect(Money.accrued(dailyRate: rate, from: 0, to: 86_400) == 1000 * Money.unit)
        #expect(Money.accrued(dailyRate: rate, from: 0, to: 1) == 11_574)
        #expect(Money.accrued(dailyRate: rate, from: 50, to: 50) == 0)
        #expect(Money.accrued(dailyRate: rate, from: 50, to: 40) == 0)
        #expect(Money.accrued(dailyRate: rate, from: 0, to: 100 * 365 * 86_400) == 36_500_000 * Money.unit)
    }

    @Test func poolPriceMatchesTheProgram() {
        #expect(Money.quoteClpcIn(solReserve: 1_000_000_000, clpcReserve: 200_000 * Money.unit, lamportsOut: 5_000) == 1_000_006)
        #expect(Money.quoteClpcIn(solReserve: 500_000_000, clpcReserve: 400_000 * Money.unit, lamportsOut: 5_000) == 4_000_041)
        #expect(Money.quoteClpcIn(solReserve: 5_000, clpcReserve: 1, lamportsOut: 5_000) == nil)
        #expect(Money.quoteClpcIn(solReserve: 0, clpcReserve: 1, lamportsOut: 1) == nil)
    }

    @Test func feeIsNilWhenThePoolWouldFallBelowItsFloor() {
        #expect(Money.fee(solReserve: 14_000, clpcReserve: 200_000 * Money.unit, refundLamports: 5_000, floorLamports: 10_000) == nil)
        #expect(Money.fee(solReserve: 15_000, clpcReserve: 200_000 * Money.unit, refundLamports: 5_000, floorLamports: 10_000) != nil)
    }

    @Test func liveBalanceAddsIncomeSinceTheLastSettlement() {
        let m = member(settled: 500 * Money.unit, at: 1_000)
        #expect(Money.liveBalance(member: m, dailyRate: rate, chainNow: 1_000) == 500 * Money.unit)
        #expect(Money.liveBalance(member: m, dailyRate: rate, chainNow: 1_000 + 86_400) == 1500 * Money.unit)
        // Between whole seconds the number keeps moving.
        #expect(Money.liveBalance(member: m, dailyRate: rate, chainNow: 1_000.5) == 500 * Money.unit + 5_787)
        // A clock that reads earlier than the record never shrinks the balance.
        #expect(Money.liveBalance(member: m, dailyRate: rate, chainNow: 900) == 500 * Money.unit)
    }

    @Test func liveBalance_uses_chain_offset() {
        // The phone thinks it is 1970; the chain says otherwise.
        let wrongPhoneTime = Date(timeIntervalSince1970: 10)
        let clock = ChainClock(chainTime: 1_800_000_000, observedAt: wrongPhoneTime)
        #expect(clock.now(at: wrongPhoneTime) == 1_800_000_000)
        #expect(clock.now(at: wrongPhoneTime.addingTimeInterval(86_400)) == 1_800_086_400)
        let m = member(settled: 0, at: 1_800_000_000)
        let later = clock.now(at: wrongPhoneTime.addingTimeInterval(86_400))
        #expect(Money.liveBalance(member: m, dailyRate: rate, chainNow: later) == 1000 * Money.unit)
    }

    @Test func spendableIsWholePesosOnly() {
        #expect(Money.wholePesos(micro: 12_345_999_999) == 12_345)
        #expect(Money.wholePesos(micro: 999_999) == 0)
        #expect(Money.micro(pesos: 3_500) == 3_500_000_000)
    }
}

@Suite struct PesoFormatTests {
    @Test func splitsIntoWhitePesosAndGrayDecimals() {
        #expect(PesoFormat.parts(micro: 0) == PesoFormat.Parts(whole: "0", decimals: "0000"))
        #expect(PesoFormat.parts(micro: 999_999) == PesoFormat.Parts(whole: "0", decimals: "9999"))
        #expect(PesoFormat.parts(micro: 1_000_000) == PesoFormat.Parts(whole: "1", decimals: "0000"))
        #expect(PesoFormat.parts(micro: 12_345_287_100) == PesoFormat.Parts(whole: "12.345", decimals: "2871"))
        #expect(PesoFormat.parts(micro: 1_234_567_000_000) == PesoFormat.Parts(whole: "1.234.567", decimals: "0000"))
    }

    @Test func groupsThousandsWithDots() {
        #expect(PesoFormat.whole(0) == "0")
        #expect(PesoFormat.whole(999) == "999")
        #expect(PesoFormat.whole(1_000) == "1.000")
        #expect(PesoFormat.whole(3_500) == "3.500")
        #expect(PesoFormat.whole(1_000_000) == "1.000.000")
    }

    @Test func signsHistoryAmounts() {
        #expect(PesoFormat.signed(3_500, incoming: true) == "+3.500")
        #expect(PesoFormat.signed(1_200, incoming: false) == "−1.200")
    }
}
