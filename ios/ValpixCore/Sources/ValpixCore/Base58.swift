import Foundation

/// Bitcoin-alphabet Base58, the text form of every Solana address.
public enum Base58 {
    private static let alphabet = Array("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".utf8)
    private static let indexes: [UInt8: UInt8] = {
        var map = [UInt8: UInt8]()
        for (i, c) in alphabet.enumerated() { map[c] = UInt8(i) }
        return map
    }()

    public static func encode(_ data: Data) -> String {
        let bytes = [UInt8](data)
        let zeros = bytes.prefix { $0 == 0 }.count
        // Repeated division of the big-endian number by 58.
        var digits = [UInt8]()
        for byte in bytes.dropFirst(zeros) {
            var carry = Int(byte)
            for i in digits.indices {
                carry += Int(digits[i]) << 8
                digits[i] = UInt8(carry % 58)
                carry /= 58
            }
            while carry > 0 {
                digits.append(UInt8(carry % 58))
                carry /= 58
            }
        }
        var out = [UInt8](repeating: alphabet[0], count: zeros)
        out.append(contentsOf: digits.reversed().map { alphabet[Int($0)] })
        return String(decoding: out, as: UTF8.self)
    }

    /// `nil` when the text holds a character outside the alphabet.
    public static func decode(_ text: String) -> Data? {
        let chars = Array(text.utf8)
        let zeros = chars.prefix { $0 == alphabet[0] }.count
        var bytes = [UInt8]()
        for char in chars.dropFirst(zeros) {
            guard let value = indexes[char] else { return nil }
            var carry = Int(value)
            for i in bytes.indices {
                carry += Int(bytes[i]) * 58
                bytes[i] = UInt8(carry & 0xff)
                carry >>= 8
            }
            while carry > 0 {
                bytes.append(UInt8(carry & 0xff))
                carry >>= 8
            }
        }
        return Data([UInt8](repeating: 0, count: zeros) + bytes.reversed())
    }
}
