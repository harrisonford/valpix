import CommonCrypto
import CryptoKit
import Foundation

public enum WalletBackupError: Error, Equatable {
    case emptyPassword
    case badSeed
    case keyDerivationFailed
}

/// Seals a wallet seed with a password, for a backup QR that can be saved or
/// printed. Layout: version (1) | salt (16) | nonce (12) | ciphertext (32) | tag (16).
///
/// The key comes from PBKDF2-HMAC-SHA256, so guessing a password costs an
/// attacker real time per try. A weak password is still a weak lock.
public enum WalletBackup {
    static let version: UInt8 = 1
    static let iterations: UInt32 = 600_000

    public static func seal(seed: Data, password: String) throws -> Data {
        guard !password.isEmpty else { throw WalletBackupError.emptyPassword }
        guard seed.count == 32 else { throw WalletBackupError.badSeed }
        var salt = Data(count: 16)
        salt.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        let key = try derive(password: password, salt: salt)
        // Sealing with a fresh random nonce cannot fail.
        let box = try! ChaChaPoly.seal(seed, using: key)
        return Data([version]) + salt + box.combined
    }

    /// The seed, or `nil` when the password is wrong or the blob was altered.
    public static func open(_ blob: Data, password: String) -> Data? {
        let blob = Data(blob)
        guard blob.count == 1 + 16 + 12 + 32 + 16, blob[0] == version, !password.isEmpty,
              let key = try? derive(password: password, salt: blob.subdata(in: 1..<17)),
              let box = try? ChaChaPoly.SealedBox(combined: blob.subdata(in: 17..<blob.count))
        else { return nil }
        return try? ChaChaPoly.open(box, using: key)
    }

    private static func derive(password: String, salt: Data) throws -> SymmetricKey {
        var derived = [UInt8](repeating: 0, count: 32)
        let passwordBytes = Array(password.utf8)
        let status = salt.withUnsafeBytes { saltBytes in
            passwordBytes.withUnsafeBufferPointer { pw in
                pw.withMemoryRebound(to: CChar.self) { pwChars in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        pwChars.baseAddress, pwChars.count,
                        saltBytes.bindMemory(to: UInt8.self).baseAddress, salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                        iterations,
                        &derived, derived.count
                    )
                }
            }
        }
        guard status == kCCSuccess else { throw WalletBackupError.keyDerivationFailed }
        return SymmetricKey(data: derived)
    }
}
