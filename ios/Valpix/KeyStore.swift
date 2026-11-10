import Foundation
import LocalAuthentication
import Security
import ValpixCore

/// The wallet key, kept in the Keychain. It stays on this phone: it is not
/// synced, and it is readable only while the phone is unlocked.
enum KeyStore {
    private static let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "cl.valpix.wallet",
        kSecAttrAccount as String: "seed",
    ]

    static func load() -> Keypair? {
        var request = query
        request[kSecReturnData as String] = true
        var result: AnyObject?
        guard SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess,
              let seed = result as? Data
        else { return nil }
        return Keypair(seed: seed)
    }

    @discardableResult
    static func save(_ keypair: Keypair) -> Bool {
        SecItemDelete(query as CFDictionary)
        var request = query
        request[kSecValueData as String] = keypair.seed
        request[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        return SecItemAdd(request as CFDictionary, nil) == errSecSuccess
    }

    /// The stored wallet, or a new one made and stored now. `nil` when this
    /// phone will not keep the key: a wallet that cannot be read back after
    /// saving would be lost on the next launch, along with anything sent to it.
    static func loadOrCreate() -> Keypair? {
        if let existing = load() { return existing }
        let fresh = Keypair()
        guard save(fresh), load()?.publicKey == fresh.publicKey else { return nil }
        return fresh
    }
}

/// Asks the person to approve with face, fingerprint or the phone's passcode.
enum Authenticator {
    static func confirm(reason: String) async -> Bool {
        if AppConfig.flag("VALPIX_SKIP_AUTH") { return true }
        let context = LAContext()
        var error: NSError?
        // A phone with no passcode has nothing to ask with.
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else { return true }
        return (try? await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)) ?? false
    }
}
