import CryptoKit
import Foundation
import Testing
@testable import ValpixCore

private let program = ValpixProgram(programId: PublicKey(base58: Vectors.programId)!)
private let keypair = Keypair(seed: Data(hex: Vectors.seedHex))!
private let recipient = PublicKey(base58: Vectors.recipient)!
private let rentMin: UInt64 = 890_880

private func account(_ base64: String, lamports: UInt64 = 2_000_000) -> [String: Any] {
    ["lamports": lamports, "data": [base64, "base64"], "owner": Vectors.programId]
}

/// A transport that knows the chain as the vectors captured it.
private func chain(member: Bool = true, vaultLamports: UInt64 = 10 * 1_000_000_000 + rentMin) -> FakeTransport {
    let fake = FakeTransport()
    fake.on("getMultipleAccounts") { _ in
        ["context": ["slot": 100], "value": [
            member ? account(Vectors.memberData) : NSNull(),
            account(Vectors.configData),
            account(Vectors.poolData),
            ["lamports": vaultLamports, "data": ["", "base64"], "owner": "11111111111111111111111111111111"],
            ["lamports": 1_390_880, "data": ["", "base64"], "owner": "11111111111111111111111111111111"],
        ] as [Any]]
    }
    fake.on("getMinimumBalanceForRentExemption") { _ in rentMin }
    fake.on("getSlot") { _ in 100 }
    fake.on("getBlockTime") { _ in Vectors.memberLastSettled + 86_400 }
    fake.on("getLatestBlockhash") { _ in
        ["context": ["slot": 100], "value": ["blockhash": Vectors.blockhash, "lastValidBlockHeight": 500]]
    }
    fake.on("getBlockHeight") { _ in 400 }
    return fake
}

private func client(_ transport: RPCTransport, timeout: Duration = .seconds(5)) -> ValpixClient {
    ValpixClient(rpc: SolanaRPC(transport: transport), program: program, pollInterval: .milliseconds(1), timeout: timeout)
}

/// Counts calls across the fake's handlers.
private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0
    func next() -> Int { lock.withLock { value += 1; return value } }
    func peek() -> Int { lock.withLock { value } }
}

private var confirmedStatus: [String: Any] { ["context": ["slot": 101], "value": [["confirmationStatus": "confirmed", "err": NSNull()]]] }
private var unknownStatus: [String: Any] { ["context": ["slot": 101], "value": [NSNull()]] }

/// The signature the client must derive from what it sent.
private func sentSignature(_ fake: FakeTransport) -> String {
    let wire = Data(base64Encoded: fake.calls.first { $0.method == "sendTransaction" }!.params[0] as! String)!
    return Base58.encode(wire.dropFirst().prefix(64))
}

@Suite struct ValpixClientTests {
    @Test func readsAMembersStateInOneRoundTrip() async throws {
        let fake = chain()
        let now = Date()
        let state = try await client(fake).state(for: keypair.publicKey, observedAt: now)

        #expect(state.member?.wallet.base58 == Vectors.memberWallet)
        #expect(state.config.dailyRate == Vectors.configDailyRate)
        #expect(state.pool.clpcReserve == Vectors.poolClpcReserve)
        #expect(state.solReserve == 10 * 1_000_000_000)
        #expect(state.walletLamports == 1_390_880)
        // One day after the last settlement on the chain's clock.
        #expect(state.balance(at: now) == Vectors.memberSettled + 1000 * Money.unit)
        #expect(state.fee != nil)
        let asked = fake.calls.first { $0.method == "getMultipleAccounts" }!.params[0] as! [String]
        #expect(asked == [
            program.member(of: keypair.publicKey).base58, program.config.base58, program.pool.base58,
            program.vault.base58, keypair.publicKey.base58,
        ])
    }

    @Test func aWalletWithoutAnAccountHasNoMemberAndNoBalance() async throws {
        let state = try await client(chain(member: false)).state(for: keypair.publicKey)
        #expect(state.member == nil)
        #expect(state.balance(at: Date()) == 0)
    }

    @Test func stateThrowsWhenTheProgramIsNotSetUp() async {
        let fake = chain()
        fake.on("getMultipleAccounts") { _ in
            ["context": ["slot": 1], "value": [NSNull(), NSNull(), NSNull(), NSNull(), NSNull()]]
        }
        await #expect(throws: ClientError.self) { try await client(fake).state(for: keypair.publicKey) }
    }

    @Test func paySendsASignedPaymentAndWaitsForConfirmation() async throws {
        let fake = chain()
        fake.on("sendTransaction") { _ in "sig111" }
        fake.on("getSignatureStatuses") { _ in
            ["context": ["slot": 101], "value": [["confirmationStatus": "confirmed", "err": NSNull()]]]
        }
        let outcome = await client(fake).pay(from: keypair, to: recipient, amountMicro: 300_000_000, name: "Camila")
        // The signature is the transaction's own, not whatever the access point says.
        #expect(outcome == .confirmed(signature: sentSignature(fake)))

        let sent = fake.calls.first { $0.method == "sendTransaction" }!.params
        let wire = try #require(Data(base64Encoded: sent[0] as! String))
        #expect((sent[1] as! [String: Any])["encoding"] as? String == "base64")
        let body = wire.dropFirst(65)
        let expected = Transaction.compile(
            instructions: [program.pay(payer: keypair.publicKey, recipient: recipient, amountMicro: 300_000_000, name: "Camila")],
            feePayer: keypair.publicKey, recentBlockhash: Base58.decode(Vectors.blockhash)!
        ).serialize()
        #expect(Data(body) == expected)
        let verifier = try Curve25519.Signing.PublicKey(rawRepresentation: keypair.publicKey.data)
        #expect(verifier.isValidSignature(wire.dropFirst().prefix(64), for: body))
    }

    @Test func pay_reports_failure_when_rpc_is_down() async {
        let outcome = await client(DeadTransport()).pay(from: keypair, to: recipient, amountMicro: 1, name: "")
        #expect(outcome == .failed(.noRoute))
    }

    @Test func payReportsTheProgramsOwnReasonWhenRejected() async {
        let fake = chain()
        fake.on("sendTransaction") { _ in
            throw FakeRPCFailure(code: -32002, message: "Transaction simulation failed", logs: [
                "Program log: AnchorError occurred. Error Code: InsufficientFunds. Error Number: 6006. Error Message: Not enough CLPC for the amount and its fee.",
            ])
        }
        let outcome = await client(fake).pay(from: keypair, to: recipient, amountMicro: 1, name: "")
        #expect(outcome == .failed(.rejected("InsufficientFunds")))
    }

    @Test func payFailsWhenTheChainRecordsAnError() async {
        let fake = chain()
        fake.on("sendTransaction") { _ in "sig222" }
        fake.on("getSignatureStatuses") { _ in
            ["context": ["slot": 101], "value": [["confirmationStatus": "confirmed", "err": ["InstructionError": [0, ["Custom": 6006]]]]]]
        }
        let outcome = await client(fake).pay(from: keypair, to: recipient, amountMicro: 1, name: "")
        #expect(outcome == .failed(.rejected("6006")))
    }

    @Test func payExpiresWhenTheTransactionNeverLands() async {
        let fake = chain()
        fake.on("sendTransaction") { _ in "sig333" }
        fake.on("getSignatureStatuses") { _ in ["context": ["slot": 101], "value": [NSNull()]] }
        fake.on("getBlockHeight") { _ in 501 } // past lastValidBlockHeight
        let outcome = await client(fake).pay(from: keypair, to: recipient, amountMicro: 1, name: "")
        #expect(outcome == .failed(.expired))
    }

    // Once a payment has been sent, "failed" would invite paying twice. The
    // client keeps looking until it knows.
    @Test func aBriefLossOfRouteAfterSendingIsNotAFailure() async {
        let fake = chain()
        let polls = Counter()
        fake.on("sendTransaction") { _ in "ignored" }
        fake.on("getSignatureStatuses") { _ in
            if polls.next() < 3 { throw RPCError.transport("gone") }
            return confirmedStatus
        }
        let outcome = await client(fake).pay(from: keypair, to: recipient, amountMicro: 1, name: "")
        #expect(outcome == .confirmed(signature: sentSignature(fake)))
    }

    @Test func aSendThatTimedOutButLandedIsFoundByItsSignature() async {
        let fake = chain()
        fake.on("sendTransaction") { _ in throw RPCError.transport("timed out") }
        fake.on("getSignatureStatuses") { _ in confirmedStatus }
        let outcome = await client(fake).pay(from: keypair, to: recipient, amountMicro: 1, name: "")
        #expect(outcome == .confirmed(signature: sentSignature(fake)))
        let asked = fake.calls.first { $0.method == "getSignatureStatuses" }!.params[0] as! [String]
        #expect(asked == [sentSignature(fake)])
    }

    @Test func aPaymentThatLandsInTheLastBlocksIsNotCalledExpired() async {
        let fake = chain()
        let polls = Counter()
        fake.on("sendTransaction") { _ in "ignored" }
        fake.on("getSignatureStatuses") { _ in polls.next() == 1 ? unknownStatus : confirmedStatus }
        fake.on("getBlockHeight") { _ in 501 }
        let outcome = await client(fake).pay(from: keypair, to: recipient, amountMicro: 1, name: "")
        #expect(outcome == .confirmed(signature: sentSignature(fake)))
    }

    @Test func whenItCannotFindOutItSaysSoInsteadOfClaimingFailure() async {
        let fake = chain()
        fake.on("sendTransaction") { _ in "ignored" }
        fake.on("getSignatureStatuses") { _ in throw RPCError.transport("gone") }
        let outcome = await client(fake, timeout: .milliseconds(30)).pay(from: keypair, to: recipient, amountMicro: 1, name: "")
        #expect(outcome == .failed(.unconfirmed))
    }

    @Test func historyTurnsEventsIntoEntriesSeenFromOneWallet() async throws {
        let fake = chain()
        let payer = PublicKey(base58: Vectors.paidPayer)!
        let payee = PublicKey(base58: Vectors.paidRecipient)!
        fake.on("getSignaturesForAddress") { _ in
            [["signature": "s1", "blockTime": Vectors.paidTs, "err": NSNull()],
             ["signature": "s2", "blockTime": Vectors.paidTs, "err": ["x": 1]]] as [Any]
        }
        fake.on("getTransaction") { _ in
            ["meta": ["logMessages": [
                "Program \(Vectors.programId) invoke [1]", "Program log: Instruction: Pay", Vectors.paidLogLine,
                "Program \(Vectors.programId) success",
            ]]]
        }
        let received = try await client(fake).history(for: payee, limit: 10)
        #expect(received == [HistoryEntry(
            signature: "s1", ts: Vectors.paidTs, kind: .received, counterparty: payer,
            name: Vectors.paidName, amountMicro: Vectors.paidAmount
        )])
        let sent = try await client(fake).history(for: payer, limit: 10)
        #expect(sent.first?.kind == .sent)
        #expect(sent.first?.counterparty == payee)
        // Failed transactions are not shown, so only one lookup was made each time.
        #expect(fake.calls.filter { $0.method == "getTransaction" }.count == 2)
    }

    @Test func watchingForAPaymentDoesNotReadTheSameTransactionTwice() async throws {
        // A seller's phone asks again every second or two while a code is on
        // screen. Public access points allow few transaction lookups, so each
        // transaction is read once and remembered.
        let fake = chain()
        let payee = PublicKey(base58: Vectors.paidRecipient)!
        let arrived = Counter()
        let invoke = "Program \(Vectors.programId) invoke [1]"
        let success = "Program \(Vectors.programId) success"
        fake.on("getSignaturesForAddress") { _ in
            let old: [Any] = [["signature": "old2", "blockTime": 2, "err": NSNull()],
                              ["signature": "old1", "blockTime": 1, "err": NSNull()]]
            return arrived.peek() > 0 ? [["signature": "new", "blockTime": 3, "err": NSNull()]] + old : old
        }
        fake.on("getTransaction") { params in
            switch params[0] as? String {
            case "new": return ["meta": ["logMessages": [invoke, Vectors.paidLogLine, success]]]
            case "old1": return nil // not available yet: must be asked for again
            default: return ["meta": ["logMessages": [invoke, success]]]
            }
        }
        func lookups(_ signature: String) -> Int {
            fake.calls.filter { $0.method == "getTransaction" && $0.params[0] as? String == signature }.count
        }
        let watching = client(fake)
        for _ in 0..<4 { #expect(try await watching.history(for: payee, limit: 5).isEmpty) }
        #expect(lookups("old2") == 1)
        #expect(lookups("old1") == 4)

        _ = arrived.next()
        for _ in 0..<3 { #expect(try await watching.history(for: payee, limit: 5).map(\.signature) == ["new"]) }
        #expect(lookups("new") == 1)
        #expect(lookups("old2") == 1)
    }
}

@Suite struct WalletStateSnapshotTests {
    private func sample(at observed: Date) async throws -> WalletState {
        try await client(chain()).state(for: keypair.publicKey, observedAt: observed)
    }

    @Test func survivesBeingSavedAndRestored() async throws {
        let observed = Date(timeIntervalSince1970: 2_000_000_000)
        let state = try await sample(at: observed)
        let restored = try #require(WalletState(snapshot: state.snapshot))
        #expect(restored == state)
    }

    @Test func aRestoredStateKeepsTickingWithoutTheChain() async throws {
        let observed = Date(timeIntervalSince1970: 2_000_000_000)
        let state = try await sample(at: observed)
        let restored = try #require(WalletState(snapshot: state.snapshot))
        let later = observed.addingTimeInterval(86_400)
        #expect(restored.balance(at: later) == state.balance(at: observed) + 1000 * Money.unit)
    }

    @Test func rejectsGarbage() {
        #expect(WalletState(snapshot: Data()) == nil)
        #expect(WalletState(snapshot: Data("{}".utf8)) == nil)
        #expect(WalletState(snapshot: Data("not json".utf8)) == nil)
    }
}
