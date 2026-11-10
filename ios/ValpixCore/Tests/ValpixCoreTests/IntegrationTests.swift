import Foundation
import Testing
@testable import ValpixCore

/// End to end against a real validator running the real program.
///
/// Runs only when a local validator answers on port 8899 with valpix set up
/// (see program/README.md). It uses the admin script to play the admin, and
/// everything else goes through the Swift client exactly as the app does.
private let localURL = URL(string: "http://127.0.0.1:8899")!

private func validatorIsRunning() async -> Bool {
    var request = URLRequest(url: localURL, timeoutInterval: 1)
    request.httpMethod = "POST"
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.httpBody = Data(#"{"jsonrpc":"2.0","id":1,"method":"getHealth"}"#.utf8)
    guard let (data, _) = try? await URLSession.shared.data(for: request) else { return false }
    return String(decoding: data, as: UTF8.self).contains("ok")
}

private let scriptsDir = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    .deletingLastPathComponent().deletingLastPathComponent()
    .appendingPathComponent("program/scripts")

/// Runs the admin script, as the admin would from a terminal.
private func admin(_ arguments: String...) throws {
    let process = Process()
    process.executableURL = scriptsDir.appendingPathComponent("node_modules/.bin/tsx")
    process.arguments = ["admin.ts"] + arguments
    process.currentDirectoryURL = scriptsDir
    let pipe = Pipe()
    process.standardOutput = pipe
    process.standardError = pipe
    try process.run()
    let output = pipe.fileHandleForReading.readDataToEndOfFile()
    process.waitUntilExit()
    if process.terminationStatus != 0 {
        throw RPCError.transport("admin \(arguments.joined(separator: " ")) failed: \(String(decoding: output, as: UTF8.self))")
    }
}

/// Writes a keypair in the file format Solana's tools read.
private func keyFile(_ keypair: Keypair) throws -> String {
    let bytes = [UInt8](keypair.seed) + [UInt8](keypair.publicKey.data)
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("valpix-test-\(UUID().uuidString).json")
    try JSONSerialization.data(withJSONObject: bytes.map { Int($0) }).write(to: url)
    return url.path
}

@Suite(.enabled("needs a local validator with valpix set up") { await validatorIsRunning() })
struct LocalValidatorTests {
    let rpc = SolanaRPC(url: localURL)
    let program = ValpixProgram(programId: PublicKey(base58: Vectors.programId)!)
    var client: ValpixClient { ValpixClient(rpc: rpc, program: program, pollInterval: .milliseconds(200)) }

    /// A new member holding about 2,000 CLPC, bought from the pool with test SOL.
    private func fundedMember() async throws -> Keypair {
        let keypair = Keypair()
        try admin("member", keypair.publicKey.base58)
        let airdrop = try await rpc.requestAirdrop(to: keypair.publicKey, lamports: 1_000_000_000)
        while try await rpc.signatureStatus(airdrop)?.isConfirmed != true {
            try await Task.sleep(for: .milliseconds(200))
        }
        let path = try keyFile(keypair)
        defer { try? FileManager.default.removeItem(atPath: path) }
        try admin("fund", "--sol", "0.01", "--keypair", path)
        return keypair
    }

    @Test func payJoinAndHistoryAgainstTheRealProgram() async throws {
        let x = try await fundedMember()
        let y = Keypair()
        try admin("member", y.publicKey.base58)

        // Reading state.
        let before = try await client.state(for: x.publicKey)
        #expect(before.member?.wallet == x.publicKey)
        #expect(before.balance(at: Date()) > 1_900 * Money.unit)
        let fee = try #require(before.fee)
        #expect(fee < 2 * Money.unit)

        // Paying: confirmed, the money arrives, and the pool returns the fee.
        let paid = await client.pay(from: x, to: y.publicKey, amountMicro: 300 * Money.unit, name: "Prueba")
        guard case .confirmed(let signature) = paid else {
            Issue.record("payment did not confirm: \(paid)")
            return
        }
        let after = try await client.state(for: x.publicKey)
        #expect(after.walletLamports == before.walletLamports, "the pool refunded the Solana fee")
        #expect(after.member!.settled <= before.balance(at: Date()) - 300 * Money.unit - fee + Money.unit)
        let payee = try await client.state(for: y.publicKey)
        #expect(payee.balance(at: Date()) >= 300 * Money.unit)

        // History, read back from the chain as the payee sees it.
        let history = try await client.history(for: y.publicKey)
        let entry = try #require(history.first { $0.signature == signature })
        #expect(entry.kind == .received)
        #expect(entry.counterparty == x.publicKey)
        #expect(entry.name == "Prueba")
        #expect(entry.amountMicro == 300 * Money.unit)

        // Joining: a wallet that exists nowhere becomes a member with a float.
        let z = Keypair()
        #expect(try await client.state(for: z.publicKey).member == nil)
        #expect(await client.join(inviter: x, newcomer: z.publicKey) != .failed(.noRoute))
        let newcomer = try await client.state(for: z.publicKey)
        #expect(newcomer.member?.inviter == x.publicKey)
        #expect(newcomer.walletLamports == newcomer.config.floatLamports)

        // Refusals carry the program's own reason, and nothing is confirmed.
        let tooMuch = await client.pay(from: x, to: y.publicKey, amountMicro: 1_000_000 * Money.unit, name: "")
        #expect(tooMuch == .failed(.rejected("InsufficientFunds")))
        let toNobody = await client.pay(from: x, to: Keypair().publicKey, amountMicro: Money.unit, name: "")
        #expect(toNobody == .failed(.rejected("AccountNotInitialized")))
    }
}
