import Foundation

public enum BorshError: Error, Equatable {
    case unexpectedEnd
    case invalidString
}

/// Writes the binary layout Anchor programs expect: little-endian integers
/// and strings prefixed by a 32-bit length.
public struct BorshWriter {
    public private(set) var data = Data()
    public init() {}

    public mutating func u8(_ v: UInt8) { data.append(v) }
    public mutating func u32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { data.append(contentsOf: $0) } }
    public mutating func u64(_ v: UInt64) { withUnsafeBytes(of: v.littleEndian) { data.append(contentsOf: $0) } }
    public mutating func i64(_ v: Int64) { withUnsafeBytes(of: v.littleEndian) { data.append(contentsOf: $0) } }
    public mutating func bytes(_ v: Data) { data.append(v) }
    public mutating func publicKey(_ v: PublicKey) { data.append(v.data) }
    public mutating func string(_ v: String) {
        let utf8 = Data(v.utf8)
        u32(UInt32(utf8.count))
        data.append(utf8)
    }
}

public struct BorshReader {
    private let bytes: [UInt8]
    private var offset = 0

    public init(_ data: Data) { bytes = [UInt8](data) }
    public var isAtEnd: Bool { offset == bytes.count }

    private mutating func take(_ count: Int) throws -> ArraySlice<UInt8> {
        guard count >= 0, bytes.count - offset >= count else { throw BorshError.unexpectedEnd }
        defer { offset += count }
        return bytes[offset..<offset + count]
    }

    private mutating func integer<T: FixedWidthInteger>(_: T.Type) throws -> T {
        var value: T = 0
        for (i, byte) in try take(MemoryLayout<T>.size).enumerated() {
            value |= T(truncatingIfNeeded: byte) << T(8 * i)
        }
        return value
    }

    public mutating func u8() throws -> UInt8 { try integer(UInt8.self) }
    public mutating func u32() throws -> UInt32 { try integer(UInt32.self) }
    public mutating func u64() throws -> UInt64 { try integer(UInt64.self) }
    public mutating func i64() throws -> Int64 { Int64(bitPattern: try integer(UInt64.self)) }
    public mutating func bytes(_ count: Int) throws -> Data { Data(try take(count)) }
    public mutating func publicKey() throws -> PublicKey { PublicKey(data: Data(try take(32)))! }
    public mutating func string() throws -> String {
        let length = Int(try u32())
        guard let text = String(bytes: try take(length), encoding: .utf8) else { throw BorshError.invalidString }
        return text
    }
}
