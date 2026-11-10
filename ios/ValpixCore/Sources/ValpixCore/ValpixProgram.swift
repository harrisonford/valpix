import CryptoKit
import Foundation

/// The valpix program as the app sees it: where its records live, how to
/// ask it to do things, and how to read what it stores and logs.
public struct ValpixProgram: Sendable {
    public let programId: PublicKey
    public let config: PublicKey
    public let pool: PublicKey
    public let vault: PublicKey

    public init(programId: PublicKey) {
        self.programId = programId
        func address(_ seed: String) -> PublicKey {
            PublicKey.findProgramAddress(seeds: [Data(seed.utf8)], programId: programId)!.0
        }
        config = address("config")
        pool = address("pool")
        vault = address("vault")
    }

    /// The address of a wallet's member record.
    public func member(of wallet: PublicKey) -> PublicKey {
        PublicKey.findProgramAddress(seeds: [Data("member".utf8), wallet.data], programId: programId)!.0
    }

    /// Anchor names each instruction by the first 8 bytes of a hash.
    static func discriminator(_ namespace: String, _ name: String) -> Data {
        Data(SHA256.hash(data: Data("\(namespace):\(name)".utf8)).prefix(8))
    }

    /// The accounts every pool-funded instruction ends with.
    private var poolAccounts: [AccountMeta] {
        [
            AccountMeta(config),
            AccountMeta(pool, writable: true),
            AccountMeta(vault, writable: true),
            AccountMeta(.systemProgram),
            AccountMeta(.instructionsSysvar),
        ]
    }

    /// Moves `amountMicro` micro-CLPC to another member.
    public func pay(payer: PublicKey, recipient: PublicKey, amountMicro: UInt64, name: String) -> Instruction {
        var data = BorshWriter()
        data.bytes(Self.discriminator("global", "pay"))
        data.u64(amountMicro)
        data.string(name)
        return Instruction(
            programId: programId,
            accounts: [
                AccountMeta(payer, signer: true, writable: true),
                AccountMeta(member(of: payer), writable: true),
                AccountMeta(member(of: recipient), writable: true),
            ] + poolAccounts,
            data: data.data
        )
    }

    /// Creates an account for a newcomer, paid for by the inviter.
    public func join(inviter: PublicKey, newcomer: PublicKey) -> Instruction {
        Instruction(
            programId: programId,
            accounts: [
                AccountMeta(inviter, signer: true, writable: true),
                AccountMeta(member(of: inviter), writable: true),
                AccountMeta(newcomer, writable: true),
                AccountMeta(member(of: newcomer), writable: true),
            ] + poolAccounts,
            data: Self.discriminator("global", "join")
        )
    }

    /// Tops the caller's own wallet back up to the float.
    public func reviveSelf(payer: PublicKey) -> Instruction {
        Instruction(
            programId: programId,
            accounts: [
                AccountMeta(payer, signer: true, writable: true),
                AccountMeta(member(of: payer), writable: true),
            ] + poolAccounts,
            data: Self.discriminator("global", "revive_self")
        )
    }
}

/// Reads a record, returning `nil` unless it is the expected kind and whole.
private func decode<T>(_ accountData: Data, as name: String, _ body: (inout BorshReader) throws -> T) -> T? {
    let tag = ValpixProgram.discriminator("account", name)
    guard accountData.count >= 8, accountData.prefix(8) == tag else { return nil }
    var reader = BorshReader(accountData.dropFirst(8))
    return try? body(&reader)
}

public struct ConfigRecord: Equatable, Sendable, Codable {
    public let admin: PublicKey
    public let dailyRate: UInt64
    public let feeRefundLamports: UInt64
    public let floatLamports: UInt64
    public let joinSurcharge: UInt64
    public let reviveThresholdLamports: UInt64
    public let revivePeriodSecs: Int64
    public let poolFloorLamports: UInt64
    /// The most a payment may be charged; above it the pool sells no refund.
    public let maxFee: UInt64

    public init?(accountData: Data) {
        guard let value = decode(accountData, as: "Config", { r in
            try (r.publicKey(), r.u64(), r.u64(), r.u64(), r.u64(), r.u64(), r.i64(), r.u64(), r.u64())
        }) else { return nil }
        (admin, dailyRate, feeRefundLamports, floatLamports, joinSurcharge,
         reviveThresholdLamports, revivePeriodSecs, poolFloorLamports, maxFee) = value
    }
}

public struct PoolRecord: Equatable, Sendable, Codable {
    public let clpcReserve: UInt64

    public init?(accountData: Data) {
        guard let value = decode(accountData, as: "Pool", { r in try r.u64() }) else { return nil }
        clpcReserve = value
    }

    public init(clpcReserve: UInt64) { self.clpcReserve = clpcReserve }
}

public struct MemberRecord: Equatable, Sendable, Codable {
    public let wallet: PublicKey
    /// Micro-CLPC as of `lastSettledTs`; income since then is added on top.
    public let settled: UInt64
    public let lastSettledTs: Int64
    public let createdTs: Int64
    public let inviter: PublicKey
    public let lastReviveTs: Int64

    public init?(accountData: Data) {
        guard let value = decode(accountData, as: "Member", { r in
            try (r.publicKey(), r.u64(), r.i64(), r.i64(), r.publicKey(), r.i64())
        }) else { return nil }
        (wallet, settled, lastSettledTs, createdTs, inviter, lastReviveTs) = value
    }

    public init(wallet: PublicKey, settled: UInt64, lastSettledTs: Int64, createdTs: Int64, inviter: PublicKey, lastReviveTs: Int64 = 0) {
        self.wallet = wallet
        self.settled = settled
        self.lastSettledTs = lastSettledTs
        self.createdTs = createdTs
        self.inviter = inviter
        self.lastReviveTs = lastReviveTs
    }
}

/// What the program writes to a transaction's log.
public enum ValpixEvent: Equatable, Sendable {
    public struct Paid: Equatable, Sendable {
        public let payer: PublicKey
        public let recipient: PublicKey
        public let amount: UInt64
        public let fee: UInt64
        public let name: String
        public let ts: Int64
    }

    public struct Joined: Equatable, Sendable {
        public let inviter: PublicKey
        public let newcomer: PublicKey
        public let cost: UInt64
        public let ts: Int64
    }

    case paid(Paid)
    case joined(Joined)

    /// Decodes one log line, or `nil` when it is not a valpix event.
    public init?(logLine: String) {
        let prefix = "Program data: "
        guard logLine.hasPrefix(prefix),
              let bytes = Data(base64Encoded: String(logLine.dropFirst(prefix.count))),
              bytes.count >= 8
        else { return nil }
        var r = BorshReader(bytes.dropFirst(8))
        do {
            switch bytes.prefix(8) {
            case ValpixProgram.discriminator("event", "Paid"):
                self = .paid(Paid(
                    payer: try r.publicKey(), recipient: try r.publicKey(), amount: try r.u64(),
                    fee: try r.u64(), name: try r.string(), ts: try r.i64()
                ))
            case ValpixProgram.discriminator("event", "Joined"):
                self = .joined(Joined(
                    inviter: try r.publicKey(), newcomer: try r.publicKey(), cost: try r.u64(), ts: try r.i64()
                ))
            default:
                return nil
            }
        } catch {
            return nil
        }
    }
}
