import Foundation

/// Arithmetic modulo 2^255 - 19, just enough to tell whether 32 bytes are a
/// point on the Ed25519 curve. Solana program addresses are, by definition,
/// the hashes that are NOT on the curve, so deriving one needs this check.
///
/// Not constant-time: it only ever handles public values.
struct Field25519: Equatable {
    /// Four 64-bit limbs, least significant first. Always less than 2^256;
    /// `normalized` brings it below p.
    private var limbs: [UInt64]

    private static let p: [UInt64] = [0xffff_ffff_ffff_ffed, .max, .max, 0x7fff_ffff_ffff_ffff]
    static let zero = Field25519(limbs: [0, 0, 0, 0])
    static let one = Field25519(limbs: [1, 0, 0, 0])
    /// The curve constant d = -121665/121666.
    static let d = Field25519(limbs: [
        0x75eb_4dca_1359_78a3, 0x0070_0a4d_4141_d8ab, 0x8cc7_4079_7779_e898, 0x5203_6cee_2b6f_fe73,
    ])
    /// p - 2, the exponent that inverts.
    private static let inverseExponent: [UInt64] = [0xffff_ffff_ffff_ffeb, .max, .max, 0x7fff_ffff_ffff_ffff]
    /// (p - 1) / 2, the exponent of Euler's criterion.
    private static let eulerExponent: [UInt64] = [0xffff_ffff_ffff_fff6, .max, .max, 0x3fff_ffff_ffff_ffff]

    private init(limbs: [UInt64]) { self.limbs = limbs }

    /// From 32 little-endian bytes, ignoring the top bit (the sign of x).
    init(littleEndian bytes: [UInt8]) {
        precondition(bytes.count == 32)
        var limbs = [UInt64](repeating: 0, count: 4)
        for i in 0..<32 { limbs[i / 8] |= UInt64(bytes[i]) << (8 * UInt64(i % 8)) }
        limbs[3] &= 0x7fff_ffff_ffff_ffff
        self.limbs = limbs
    }

    /// Adds `carry * 2^256`, which is `carry * 38` modulo p, back in.
    private static func fold(_ limbs: inout [UInt64], carry: UInt64) {
        var carry = carry
        while carry != 0 {
            var add = carry &* 38
            carry = 0
            for i in 0..<4 {
                let (sum, overflow) = limbs[i].addingReportingOverflow(add)
                limbs[i] = sum
                add = overflow ? 1 : 0
                if add == 0 { break }
                if i == 3 { carry = 1 }
            }
        }
    }

    private var normalized: Field25519 {
        var r = limbs
        while !Self.less(r, Self.p) {
            var borrow: UInt64 = 0
            for i in 0..<4 {
                let (d1, b1) = r[i].subtractingReportingOverflow(Self.p[i])
                let (d2, b2) = d1.subtractingReportingOverflow(borrow)
                r[i] = d2
                borrow = (b1 || b2) ? 1 : 0
            }
        }
        return Field25519(limbs: r)
    }

    private static func less(_ a: [UInt64], _ b: [UInt64]) -> Bool {
        for i in (0..<4).reversed() where a[i] != b[i] { return a[i] < b[i] }
        return false
    }

    static func == (a: Field25519, b: Field25519) -> Bool { a.normalized.limbs == b.normalized.limbs }

    static func + (a: Field25519, b: Field25519) -> Field25519 {
        var r = [UInt64](repeating: 0, count: 4)
        var carry: UInt64 = 0
        for i in 0..<4 {
            let (s1, o1) = a.limbs[i].addingReportingOverflow(b.limbs[i])
            let (s2, o2) = s1.addingReportingOverflow(carry)
            r[i] = s2
            carry = (o1 || o2) ? 1 : 0
        }
        fold(&r, carry: carry)
        return Field25519(limbs: r)
    }

    static func - (a: Field25519, b: Field25519) -> Field25519 {
        // a + (p - b), with b brought below p first.
        let b = b.normalized.limbs
        var negated = [UInt64](repeating: 0, count: 4)
        var borrow: UInt64 = 0
        for i in 0..<4 {
            let (d1, b1) = p[i].subtractingReportingOverflow(b[i])
            let (d2, b2) = d1.subtractingReportingOverflow(borrow)
            negated[i] = d2
            borrow = (b1 || b2) ? 1 : 0
        }
        return a + Field25519(limbs: negated)
    }

    static func * (a: Field25519, b: Field25519) -> Field25519 {
        var wide = [UInt64](repeating: 0, count: 8)
        for i in 0..<4 {
            var carry: UInt64 = 0
            for j in 0..<4 {
                let (high, low) = a.limbs[i].multipliedFullWidth(by: b.limbs[j])
                let (s1, o1) = wide[i + j].addingReportingOverflow(low)
                let (s2, o2) = s1.addingReportingOverflow(carry)
                wide[i + j] = s2
                carry = high &+ (o1 ? 1 : 0) &+ (o2 ? 1 : 0)
            }
            wide[i + 4] = carry
        }
        // low + 38 * high, since 2^256 = 38 (mod p).
        var r = Array(wide[0..<4])
        var carry: UInt64 = 0
        for i in 0..<4 {
            let (high, low) = wide[i + 4].multipliedFullWidth(by: 38)
            let (s1, o1) = r[i].addingReportingOverflow(low)
            let (s2, o2) = s1.addingReportingOverflow(carry)
            r[i] = s2
            carry = high &+ (o1 ? 1 : 0) &+ (o2 ? 1 : 0)
        }
        fold(&r, carry: carry)
        return Field25519(limbs: r)
    }

    private func power(_ exponent: [UInt64]) -> Field25519 {
        var result = Field25519.one
        for limb in exponent.reversed() {
            for bit in (0..<64).reversed() {
                result = result * result
                if (limb >> UInt64(bit)) & 1 == 1 { result = result * self }
            }
        }
        return result
    }

    /// Whether these 32 bytes decode to a point on the Ed25519 curve:
    /// x^2 = (y^2 - 1) / (d y^2 + 1) must have a solution.
    static func isOnCurve(_ bytes: [UInt8]) -> Bool {
        let y = Field25519(littleEndian: bytes)
        let y2 = y * y
        let u = y2 - .one
        let v = d * y2 + .one
        let x2 = u * v.power(inverseExponent)
        return x2 == .zero || x2.power(eulerExponent) == .one
    }
}
