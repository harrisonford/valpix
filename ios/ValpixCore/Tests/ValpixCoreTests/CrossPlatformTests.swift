import Foundation
import Testing
@testable import ValpixCore

/// The exact text and bytes the iPhone and Android apps hand each other.
/// The same fixtures are pinned in the Kotlin core
/// (`android/core/src/test/.../CrossPlatformTest.kt`): change one side only
/// and a phone stops being able to read the other's codes.
struct CrossPlatformTests {
    static let pay = "valpix:pay?to=3eSdHG44thnPEP7QETrxKDLpMbecvTA2HVeXFJJiAd2Q&amount=1500&name=pan%20%26%20caf%C3%A9%20%2B1"
    static let open = "valpix:pay?to=3eSdHG44thnPEP7QETrxKDLpMbecvTA2HVeXFJJiAd2Q"
    static let join = "valpix:join?key=8u8LCMQvMKrFxHbn326Ltcqv72HDPEC5FPMgPC3mXvxV&name=%C3%91and%C3%BA%20%3D%20100%25"
    static let wallet = "valpix:wallet?seed=4wBqpZM9xaSheZzJSMawUKKwhdpChKbZ5eu5ky4Vigw"
    /// Sealed by the Swift core.
    static let backupFromSwift = "valpix:backup?d=AQOmdWr_IqZ30ayvAIjUGkUFJhiZFOGeyntPE9MCDRUzXD7XoO9Cc8TNwJ0wpbgQjTIcaTIIAKRPops8eoDZtS-7FplJAhScmyW-sg8"
    static let password = "contraseña ñandú 1"

    let recipient = PublicKey(base58: Vectors.recipient)!
    let newcomer = PublicKey(base58: Vectors.newcomer)!
    let seed = Data(hex: Vectors.seedHex)

    @Test func writesLinksAsPinned() {
        #expect(ValpixLink.pay(to: recipient, amountPesos: 1500, name: "pan & café +1").string == Self.pay)
        #expect(ValpixLink.pay(to: recipient, amountPesos: nil, name: "").string == Self.open)
        #expect(ValpixLink.join(key: newcomer, name: "Ñandú = 100%").string == Self.join)
        #expect(ValpixLink.wallet(seed: seed).string == Self.wallet)
    }

    @Test func readsPinnedLinks() {
        #expect(ValpixLink(Self.pay) == .pay(to: recipient, amountPesos: 1500, name: "pan & café +1"))
        #expect(ValpixLink(Self.open) == .pay(to: recipient, amountPesos: nil, name: ""))
        #expect(ValpixLink(Self.join) == .join(key: newcomer, name: "Ñandú = 100%"))
        #expect(ValpixLink(Self.wallet) == .wallet(seed: seed))
    }

    @Test func opensABackupSealedBySwift() throws {
        guard case .backup(let blob)? = ValpixLink(Self.backupFromSwift) else {
            Issue.record("not a backup link")
            return
        }
        #expect(WalletBackup.open(blob, password: Self.password) == seed)
        #expect(WalletBackup.open(blob, password: "contrasena nandu 1") == nil)
    }
}
