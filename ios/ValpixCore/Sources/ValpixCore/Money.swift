import Foundation

/// The program's arithmetic, mirrored so the phone can show a balance and a
/// fee without asking the chain for each tick. Amounts are micro-CLPC.
public enum Money {
    /// Micro-CLPC per CLPC.
    public static let unit: UInt64 = 1_000_000
    static let secondsPerDay: UInt64 = 86_400

    /// Income between two chain timestamps, rounded down.
    public static func accrued(dailyRate: UInt64, from: Int64, to: Int64) -> UInt64 {
        guard to > from else { return 0 }
        let (earned, overflow) = UInt64(to - from).multipliedReportingOverflow(by: dailyRate)
        return overflow ? .max : earned / secondsPerDay
    }

    /// CLPC the pool charges for `lamportsOut` of SOL, rounded up.
    public static func quoteClpcIn(solReserve: UInt64, clpcReserve: UInt64, lamportsOut: UInt64) -> UInt64? {
        guard lamportsOut < solReserve else { return nil }
        let (high, low) = clpcReserve.multipliedFullWidth(by: lamportsOut)
        let divisor = solReserve - lamportsOut
        guard high < divisor else { return nil } // quotient would not fit
        let (quotient, remainder) = divisor.dividingFullWidth((high, low))
        return remainder == 0 ? quotient : quotient + 1
    }

    /// The CLPC fee of a payment right now, or `nil` when the pool will not
    /// refund it (it is below its floor, or its price is above the cap), in
    /// which case the payment costs no CLPC.
    public static func fee(solReserve: UInt64, clpcReserve: UInt64, refundLamports: UInt64, floorLamports: UInt64, maxFee: UInt64) -> UInt64? {
        guard solReserve >= refundLamports, solReserve - refundLamports >= floorLamports,
              let price = quoteClpcIn(solReserve: solReserve, clpcReserve: clpcReserve, lamportsOut: refundLamports),
              price <= maxFee
        else { return nil }
        return price
    }

    /// A member's balance at a moment of chain time. Fractions of a second
    /// count, so the number on screen moves smoothly.
    public static func liveBalance(member: MemberRecord, dailyRate: UInt64, chainNow: TimeInterval) -> UInt64 {
        let elapsed = chainNow - TimeInterval(member.lastSettledTs)
        guard elapsed > 0 else { return member.settled }
        let earned = elapsed * Double(dailyRate) / Double(secondsPerDay)
        guard earned < Double(UInt64.max - member.settled) else { return .max }
        return member.settled + UInt64(earned)
    }

    /// What can be spent: payments are made in whole pesos.
    public static func wholePesos(micro: UInt64) -> UInt64 { micro / unit }
    public static func micro(pesos: UInt64) -> UInt64 { pesos * unit }
}

/// Chain time from the phone's clock. The phone's clock may be wrong, so the
/// app measures the difference once and applies it from then on.
public struct ChainClock: Sendable, Equatable {
    private let offset: TimeInterval

    public init(chainTime: TimeInterval, observedAt: Date = Date()) {
        offset = chainTime - observedAt.timeIntervalSince1970
    }

    public func now(at date: Date = Date()) -> TimeInterval { date.timeIntervalSince1970 + offset }
}

/// Chilean number format: dots for thousands, comma for decimals.
public enum PesoFormat {
    public struct Parts: Equatable, Sendable {
        /// Whole pesos, shown white.
        public let whole: String
        /// Four decimal places, shown gray.
        public let decimals: String
    }

    public static func parts(micro: UInt64) -> Parts {
        let fraction = (micro % Money.unit) / 100
        return Parts(whole: whole(micro / Money.unit), decimals: String(format: "%04llu", fraction))
    }

    public static func whole(_ pesos: UInt64) -> String {
        var digits = String(pesos)
        var groups = [String]()
        while digits.count > 3 {
            groups.insert(String(digits.suffix(3)), at: 0)
            digits.removeLast(3)
        }
        groups.insert(digits, at: 0)
        return groups.joined(separator: ".")
    }

    /// For history rows: a plus sign, or a true minus sign.
    public static func signed(_ pesos: UInt64, incoming: Bool) -> String {
        (incoming ? "+" : "−") + whole(pesos)
    }
}
