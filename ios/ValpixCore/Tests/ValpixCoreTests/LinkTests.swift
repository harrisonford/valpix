import Foundation
import Testing
@testable import ValpixCore

private let key = PublicKey(base58: Vectors.publicKey)!

@Suite struct PaymentLinkTests {
    @Test func roundTripsAPaymentRequest() {
        let link = ValpixLink.pay(to: key, amountPesos: 3_500, name: "Feria Av. Argentina")
        #expect(ValpixLink(link.string) == link)
        #expect(link.string.hasPrefix("valpix:pay?"))
    }

    @Test func roundTripsAnOpenRequestAndAccents() {
        let link = ValpixLink.pay(to: key, amountPesos: nil, name: "Ñandú & café ☕")
        #expect(ValpixLink(link.string) == link)
    }

    @Test func aNameIsMadeSafeBeforeItGoesIntoACode() {
        #expect(ValpixLink.shareableName("  Ana \n") == "Ana")
        #expect(ValpixLink.shareableName("a\u{0}b") == "ab")
        // Many emoji are joined by an invisible character that scanners
        // refuse in a name; left in, nobody could read this person's codes.
        let safe = ValpixLink.shareableName("Ana \u{1F937}\u{200D}\u{2640}\u{FE0F}")
        #expect(!safe.unicodeScalars.contains("\u{200D}"))
        #expect(safe.hasPrefix("Ana "))
        #expect(ValpixLink(ValpixLink.join(key: key, name: safe).string) == .join(key: key, name: safe))
        #expect(ValpixLink(ValpixLink.pay(to: key, amountPesos: 5, name: safe).string) == .pay(to: key, amountPesos: 5, name: safe))
        // At most 32 bytes, never cutting a character in half.
        #expect(ValpixLink.shareableName(String(repeating: "ñ", count: 40)) == String(repeating: "ñ", count: 16))
        #expect(ValpixLink.shareableName(String(repeating: "😀", count: 20)) == String(repeating: "😀", count: 8))
        #expect(ValpixLink.shareableName("a" + String(repeating: "😀", count: 20)) == "a" + String(repeating: "😀", count: 7))
    }

    @Test func roundTripsJoinWalletAndBackup() {
        let join = ValpixLink.join(key: key, name: "Tomás")
        #expect(ValpixLink(join.string) == join)
        let wallet = ValpixLink.wallet(seed: Data(hex: Vectors.seedHex))
        #expect(ValpixLink(wallet.string) == wallet)
        let backup = ValpixLink.backup(blob: Data((0..<77).map { UInt8($0) }))
        #expect(ValpixLink(backup.string) == backup)
    }

    @Test func acceptsTheDoubleSlashForm() {
        #expect(ValpixLink("valpix://pay?to=\(Vectors.publicKey)&amount=10") == .pay(to: key, amountPesos: 10, name: ""))
        #expect(ValpixLink("VALPIX:pay?to=\(Vectors.publicKey)") == .pay(to: key, amountPesos: nil, name: ""))
    }

    @Test func rejectsHostileOrBrokenContent() {
        let to = Vectors.publicKey
        let bad = [
            "",
            "hello",
            "https://example.com/pay?to=\(to)",
            "bitcoin:\(to)?amount=1",
            "valpix:pay",
            "valpix:pay?to=notakey",
            "valpix:pay?to=\(to)x",
            "valpix:pay?to=\(to)&amount=0",
            "valpix:pay?to=\(to)&amount=-5",
            "valpix:pay?to=\(to)&amount=1.5",
            "valpix:pay?to=\(to)&amount=1e3",
            "valpix:pay?to=\(to)&amount=99999999999999999999",
            "valpix:pay?to=\(to)&amount=1000000001",
            "valpix:pay?to=\(to)&name=" + String(repeating: "x", count: 33),
            "valpix:pay?to=\(to)&name=" + String(repeating: "ñ", count: 17),
            "valpix:pay?to=\(to)&name=line%0Abreak",
            "valpix:steal?to=\(to)",
            "valpix:join?key=short",
            "valpix:wallet?seed=\(to)x",
            "valpix:backup?d=!!!",
        ]
        for text in bad {
            #expect(ValpixLink(text) == nil, "should reject: \(text)")
        }
    }

    @Test func acceptsTheLongestAllowedName() {
        let name = String(repeating: "ñ", count: 16) // 32 bytes
        #expect(ValpixLink(ValpixLink.pay(to: key, amountPesos: 1, name: name).string) == .pay(to: key, amountPesos: 1, name: name))
    }
}

@Suite struct WalletBackupTests {
    private let seed = Data(hex: Vectors.seedHex)

    @Test func opensWithTheRightPassword() throws {
        let blob = try WalletBackup.seal(seed: seed, password: "cerro alegre 42")
        #expect(blob.count == 77)
        #expect(WalletBackup.open(blob, password: "cerro alegre 42") == seed)
    }

    @Test func staysShutWithTheWrongPassword() throws {
        let blob = try WalletBackup.seal(seed: seed, password: "cerro alegre 42")
        #expect(WalletBackup.open(blob, password: "cerro alegre 43") == nil)
        #expect(WalletBackup.open(blob, password: "") == nil)
    }

    @Test func detectsTampering() throws {
        var blob = try WalletBackup.seal(seed: seed, password: "pw")
        blob[40] ^= 1
        #expect(WalletBackup.open(blob, password: "pw") == nil)
        #expect(WalletBackup.open(blob.prefix(50), password: "pw") == nil)
        #expect(WalletBackup.open(Data(), password: "pw") == nil)
    }

    @Test func neverProducesTheSameBlobTwice() throws {
        #expect(try WalletBackup.seal(seed: seed, password: "pw") != WalletBackup.seal(seed: seed, password: "pw"))
    }

    @Test func refusesAnEmptyPasswordOrABadSeed() {
        #expect(throws: WalletBackupError.self) { try WalletBackup.seal(seed: seed, password: "") }
        #expect(throws: WalletBackupError.self) { try WalletBackup.seal(seed: Data([1, 2, 3]), password: "pw") }
    }
}
