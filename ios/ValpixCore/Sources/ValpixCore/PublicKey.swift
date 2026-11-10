import CryptoKit
import Foundation

/// A 32-byte Solana address: a wallet's public key, or an address a program
/// derives for its own records.
public struct PublicKey: Hashable, Sendable, CustomStringConvertible {
    public let data: Data

    public init?(data: Data) {
        guard data.count == 32 else { return nil }
        self.data = Data(data)
    }

    public init?(base58: String) {
        guard let data = Base58.decode(base58) else { return nil }
        self.init(data: data)
    }

    public var base58: String { Base58.encode(data) }
    public var description: String { base58 }

    /// True for wallet keys, false for program-derived addresses.
    public var isOnCurve: Bool { Field25519.isOnCurve([UInt8](data)) }

    /// The address a program owns for these seeds, and the bump that makes it
    /// fall off the curve. Mirrors Solana's `find_program_address`.
    public static func findProgramAddress(seeds: [Data], programId: PublicKey) -> (PublicKey, UInt8)? {
        for bump in (0...UInt8.max).reversed() {
            var hasher = SHA256()
            for seed in seeds { hasher.update(data: seed) }
            hasher.update(data: Data([bump]))
            hasher.update(data: programId.data)
            hasher.update(data: Data("ProgramDerivedAddress".utf8))
            let digest = Data(hasher.finalize())
            if !Field25519.isOnCurve([UInt8](digest)), let key = PublicKey(data: digest) {
                return (key, bump)
            }
        }
        return nil
    }

    public static let systemProgram = PublicKey(data: Data(repeating: 0, count: 32))!
    public static let instructionsSysvar = PublicKey(base58: "Sysvar1nstructions1111111111111111111111111")!
}
