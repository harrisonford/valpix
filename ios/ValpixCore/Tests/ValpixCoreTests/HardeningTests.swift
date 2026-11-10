import Foundation
import Testing
@testable import ValpixCore

private let valpix = PublicKey(base58: Vectors.programId)!
private let other = Vectors.recipient // any other program's address
private let me = PublicKey(base58: Vectors.publicKey)!
private let seed = Data(hex: Vectors.seedHex)

@Suite struct EventAttributionTests {
    @Test func acceptsEventsLoggedByValpix() {
        let logs = ["Program \(Vectors.programId) invoke [1]", "Program log: Instruction: Pay", Vectors.paidLogLine,
                    "Program \(Vectors.programId) consumed 14118 of 200000 compute units", "Program \(Vectors.programId) success"]
        #expect(ValpixEvent.events(in: logs, programId: valpix).count == 1)
    }

    // Anyone can deploy a program that logs the same bytes. A seller's tick
    // must never come from one.
    @Test func ignoresTheSameBytesLoggedByAnotherProgram() {
        let logs = ["Program \(other) invoke [1]", Vectors.paidLogLine, "Program \(other) success"]
        #expect(ValpixEvent.events(in: logs, programId: valpix).isEmpty)
    }

    @Test func ignoresEventsLoggedAfterValpixReturned() {
        let logs = ["Program \(Vectors.programId) invoke [1]", "Program \(Vectors.programId) success",
                    "Program \(other) invoke [1]", Vectors.paidLogLine, "Program \(other) success"]
        #expect(ValpixEvent.events(in: logs, programId: valpix).isEmpty)
    }

    @Test func tracksNestedCalls() {
        let system = "11111111111111111111111111111111"
        let logs = ["Program \(Vectors.programId) invoke [1]", "Program \(system) invoke [2]", "Program \(system) success",
                    Vectors.paidLogLine, "Program \(Vectors.programId) success"]
        #expect(ValpixEvent.events(in: logs, programId: valpix).count == 1)
        let inner = ["Program \(Vectors.programId) invoke [1]", "Program \(other) invoke [2]", Vectors.paidLogLine,
                     "Program \(other) success", "Program \(Vectors.programId) success"]
        #expect(ValpixEvent.events(in: inner, programId: valpix).isEmpty)
    }

    @Test func aLineWithNoProgramRunningIsIgnored() {
        #expect(ValpixEvent.events(in: [Vectors.paidLogLine], programId: valpix).isEmpty)
    }
}

@Suite struct ChargeMatchTests {
    private func received(_ pesos: UInt64, at ts: Int64) -> HistoryEntry {
        HistoryEntry(signature: "s", ts: ts, kind: .received, counterparty: me, name: "", amountMicro: pesos * Money.unit)
    }

    @Test func aFixedChargeNeedsAtLeastItsAmount() {
        #expect(received(5_000, at: 100).settles(requestedPesos: 5_000, after: 100))
        #expect(received(6_000, at: 100).settles(requestedPesos: 5_000, after: 100))
        #expect(!received(1, at: 100).settles(requestedPesos: 5_000, after: 100))
    }

    @Test func anOpenChargeAcceptsAnyAmount() {
        #expect(received(1, at: 100).settles(requestedPesos: nil, after: 100))
    }

    @Test func olderPaymentsAndOtherKindsDoNotCount() {
        #expect(!received(5_000, at: 90).settles(requestedPesos: 5_000, after: 100))
        let sent = HistoryEntry(signature: "s", ts: 100, kind: .sent, counterparty: me, name: "", amountMicro: 5_000 * Money.unit)
        #expect(!sent.settles(requestedPesos: nil, after: 100))
    }
}

@Suite struct LinkPolicyTests {
    private let someone = PublicKey(base58: Vectors.recipient)!
    private var member: WalletSituation { WalletSituation(own: me, isMember: true, chainConfirmedNoAccount: false) }
    private var newcomer: WalletSituation { WalletSituation(own: me, isMember: false, chainConfirmedNoAccount: true) }
    private var unknown: WalletSituation { WalletSituation(own: me, isMember: false, chainConfirmedNoAccount: false) }

    @Test func membersCanBeAskedToPayOrInviteFromAnywhere() {
        for source in [LinkSource.scanned, .external] {
            #expect(LinkPolicy.action(for: .pay(to: someone, amountPesos: 5, name: "x"), from: source, situation: member)
                == .pay(to: someone, amountPesos: 5, name: "x"))
            #expect(LinkPolicy.action(for: .join(key: someone, name: "x"), from: source, situation: member)
                == .invite(key: someone, name: "x"))
        }
    }

    @Test func nobodyPaysOrInvitesThemselvesOrWithoutAnAccount() {
        #expect(LinkPolicy.action(for: .pay(to: me, amountPesos: 5, name: ""), from: .scanned, situation: member) == .ignore)
        #expect(LinkPolicy.action(for: .join(key: me, name: ""), from: .scanned, situation: member) == .ignore)
        #expect(LinkPolicy.action(for: .pay(to: someone, amountPesos: 5, name: ""), from: .scanned, situation: newcomer) == .ignore)
    }

    // A wallet link IS a wallet. Tapping one someone sent you would put you
    // on a key they also hold, so only the in-app scanner may offer it.
    @Test func aWalletNeverArrivesThroughATappedLink() {
        #expect(LinkPolicy.action(for: .wallet(seed: Data(repeating: 9, count: 32)), from: .external, situation: newcomer) == .ignore)
        #expect(LinkPolicy.action(for: .backup(blob: Data([1])), from: .external, situation: newcomer) == .ignore)
    }

    @Test func aScannedWalletIsOfferedOnlyOnceTheChainSaysThisPhoneHasNoAccount() {
        let incoming = Data(repeating: 9, count: 32)
        #expect(LinkPolicy.action(for: .wallet(seed: incoming), from: .scanned, situation: newcomer) == .adopt(seed: incoming))
        #expect(LinkPolicy.action(for: .backup(blob: Data([1])), from: .scanned, situation: newcomer) == .unlock(blob: Data([1])))
        // A member, or a phone that has not heard from the chain (it may hold
        // a funded key it cannot see yet), keeps the wallet it has.
        #expect(LinkPolicy.action(for: .wallet(seed: incoming), from: .scanned, situation: member) == .ignore)
        #expect(LinkPolicy.action(for: .wallet(seed: incoming), from: .scanned, situation: unknown) == .ignore)
        #expect(LinkPolicy.action(for: .backup(blob: Data([1])), from: .scanned, situation: unknown) == .ignore)
    }

    @Test func scanningYourOwnWalletDoesNothing() {
        #expect(LinkPolicy.action(for: .wallet(seed: seed), from: .scanned, situation: newcomer) == .ignore)
    }

    @Test func veryLongLinksAreRejectedBeforeAnyWork() {
        let long = "valpix:pay?to=" + String(repeating: "1", count: 200_000)
        #expect(ValpixLink(long) == nil)
        #expect(ValpixLink("valpix:pay?to=\(Vectors.publicKey)&name=" + String(repeating: "x", count: 5_000)) == nil)
    }
}

/// Stands in for the Keychain.
private final class FakeStore: SecretStore, @unchecked Sendable {
    var content: StoredSecret
    var addSucceeds = true
    var keepsWhatItIsGiven = true
    private(set) var adds = 0
    private(set) var replaces = 0
    init(_ content: StoredSecret) { self.content = content }

    func read() -> StoredSecret { content }
    func add(_ secret: Data) -> Bool {
        adds += 1
        guard addSucceeds else { return false }
        if keepsWhatItIsGiven { content = .found(secret) }
        return true
    }
    func replace(with secret: Data) -> Bool {
        replaces += 1
        content = .found(secret)
        return true
    }
}

@Suite struct WalletKeeperTests {
    @Test func makesAWalletOnlyWhenThereIsNone() throws {
        let store = FakeStore(.missing)
        let keypair = try #require(WalletKeeper.loadOrCreate(in: store))
        #expect(store.content == .found(keypair.seed))
        #expect(store.adds == 1)
    }

    @Test func returnsTheStoredWalletUntouched() throws {
        let store = FakeStore(.found(seed))
        #expect(try #require(WalletKeeper.loadOrCreate(in: store)).publicKey == me)
        #expect(store.adds == 0 && store.replaces == 0)
    }

    // The phone may be locked, or the Keychain busy. That is not "no wallet":
    // creating one here would overwrite a funded key.
    @Test func neverCreatesOrOverwritesWhenTheStoreCannotBeRead() {
        let store = FakeStore(.unavailable)
        #expect(WalletKeeper.loadOrCreate(in: store) == nil)
        #expect(store.adds == 0 && store.replaces == 0)
        #expect(WalletKeeper.replace(with: Data(repeating: 9, count: 32), in: store) == nil)
        #expect(store.adds == 0 && store.replaces == 0)
    }

    @Test func leavesDamagedContentsAlone() {
        let store = FakeStore(.found(Data([1, 2, 3])))
        #expect(WalletKeeper.loadOrCreate(in: store) == nil)
        #expect(store.content == .found(Data([1, 2, 3])))
        #expect(store.adds == 0 && store.replaces == 0)
    }

    @Test func refusesAWalletThatWasNotKept() {
        let failing = FakeStore(.missing)
        failing.addSucceeds = false
        #expect(WalletKeeper.loadOrCreate(in: failing) == nil)
        let forgetful = FakeStore(.missing)
        forgetful.keepsWhatItIsGiven = false
        #expect(WalletKeeper.loadOrCreate(in: forgetful) == nil)
    }

    @Test func replacingUpdatesInPlace() throws {
        let store = FakeStore(.found(Data(repeating: 9, count: 32)))
        let adopted = try #require(WalletKeeper.replace(with: seed, in: store))
        #expect(adopted.publicKey == me)
        #expect(store.replaces == 1 && store.adds == 0)
        #expect(WalletKeeper.replace(with: Data([1]), in: store) == nil)
        #expect(store.content == .found(seed))
    }
}

private struct Failing: RPCTransport {
    func post(_ body: Data) async throws -> Data { throw RPCError.transport("down") }
}

private final class Answering: RPCTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    var calls: Int { lock.withLock { count } }
    func post(_ body: Data) async throws -> Data {
        lock.withLock { count += 1 }
        return Data(#"{"jsonrpc":"2.0","id":1,"result":7}"#.utf8)
    }
}

@Suite struct FallbackTransportTests {
    @Test func usesTheNextAccessPointWhenOneIsDown() async throws {
        let second = Answering()
        let rpc = SolanaRPC(transport: FallbackTransport([Failing(), second]))
        #expect(try await rpc.blockHeight() == 7)
        #expect(second.calls == 1)
    }

    @Test func doesNotBotherTheOthersWhileTheFirstAnswers() async throws {
        let first = Answering(), second = Answering()
        _ = try await SolanaRPC(transport: FallbackTransport([first, second])).blockHeight()
        #expect(first.calls == 1 && second.calls == 0)
    }

    @Test func failsOnlyWhenAllAreDown() async {
        let rpc = SolanaRPC(transport: FallbackTransport([Failing(), Failing()]))
        await #expect(throws: RPCError.self) { try await rpc.blockHeight() }
    }
}
