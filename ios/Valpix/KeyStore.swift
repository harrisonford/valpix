import Foundation
import LocalAuthentication
import Security
import ValpixCore

/// The Keychain as the place the wallet key lives. It stays on this phone:
/// not synced, and readable only while the phone is unlocked.
///
/// All decisions about creating or replacing a wallet are made by
/// `WalletKeeper` in ValpixCore, which is tested; this type only reads and
/// writes, and it never deletes.
struct KeychainStore: SecretStore {
    private let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "com.franky.valpix.wallet",
        kSecAttrAccount as String: "seed",
    ]

    func read() -> StoredSecret {
        var request = query
        request[kSecReturnData as String] = true
        var result: AnyObject?
        switch SecItemCopyMatching(request as CFDictionary, &result) {
        case errSecSuccess:
            guard let data = result as? Data else { return .unavailable }
            return .found(data)
        case errSecItemNotFound:
            return .missing
        default:
            // Locked phone, missing entitlement, anything else: we do not
            // know whether a wallet exists.
            return .unavailable
        }
    }

    func add(_ secret: Data) -> Bool {
        var request = query
        request[kSecValueData as String] = secret
        request[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        return SecItemAdd(request as CFDictionary, nil) == errSecSuccess
    }

    func replace(with secret: Data) -> Bool {
        SecItemUpdate(query as CFDictionary, [kSecValueData as String: secret] as CFDictionary) == errSecSuccess
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
