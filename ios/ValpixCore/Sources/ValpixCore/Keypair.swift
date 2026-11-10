import CryptoKit
import Foundation

/// An Ed25519 wallet key. The 32-byte seed is the whole secret.
public struct Keypair: Sendable {
    private let key: Curve25519.Signing.PrivateKey
    public let publicKey: PublicKey

    /// A new random wallet.
    public init() {
        let key = Curve25519.Signing.PrivateKey()
        self.key = key
        self.publicKey = PublicKey(data: key.publicKey.rawRepresentation)!
    }

    public init?(seed: Data) {
        guard seed.count == 32, let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: seed) else {
            return nil
        }
        self.key = key
        self.publicKey = PublicKey(data: key.publicKey.rawRepresentation)!
    }

    public var seed: Data { key.rawRepresentation }

    /// A 64-byte signature.
    public func sign(_ message: Data) -> Data {
        // Signing with a valid key cannot fail.
        try! key.signature(for: message)
    }
}
