import Foundation

public enum ClientError: Error, Equatable {
    /// The program's own records are missing: wrong network or not set up.
    case programNotFound
}

/// Everything the app needs to draw the home screen, from one round trip.
public struct WalletState: Equatable, Sendable, Codable {
    /// `nil` until an existing member has brought this wallet in.
    public let member: MemberRecord?
    public let config: ConfigRecord
    public let pool: PoolRecord
    /// The pool's SOL, in lamports.
    public let solReserve: UInt64
    public let walletLamports: UInt64
    public let clock: ChainClock

    /// Micro-CLPC at a moment on the phone's clock, corrected to chain time.
    public func balance(at date: Date) -> UInt64 {
        guard let member else { return 0 }
        return Money.liveBalance(member: member, dailyRate: config.dailyRate, chainNow: clock.now(at: date))
    }

    /// What the next payment will be charged, or `nil` when the pool will
    /// not refund it and the payment costs no CLPC.
    public var fee: UInt64? {
        Money.fee(
            solReserve: solReserve, clpcReserve: pool.clpcReserve,
            refundLamports: config.feeRefundLamports, floorLamports: config.poolFloorLamports,
            maxFee: config.maxFee
        )
    }
}

extension WalletState {
    /// The state as bytes, so the phone can show the last known balance,
    /// still ticking, when it opens without a route to the chain.
    public var snapshot: Data { (try? JSONEncoder().encode(self)) ?? Data() }

    public init?(snapshot: Data) {
        guard let state = try? JSONDecoder().decode(WalletState.self, from: snapshot) else { return nil }
        self = state
    }
}

public enum FailureReason: Equatable, Sendable {
    /// No access point could be reached.
    case noRoute
    /// The program or the chain refused it; carries the error's name or number.
    case rejected(String)
    /// It did not confirm before the transaction's time ran out. Not paid.
    case expired
    /// It was sent, and the chain could not be reached to learn what
    /// happened. It may have been paid.
    case unconfirmed
}

public enum Outcome: Equatable, Sendable {
    case confirmed(signature: String)
    case failed(FailureReason)
}

public struct HistoryEntry: Equatable, Sendable, Identifiable {
    public enum Kind: Equatable, Sendable { case received, sent, invited, joined }
    public let signature: String
    public let ts: Int64
    public let kind: Kind
    public let counterparty: PublicKey
    /// The payer's chosen name, for payments received.
    public let name: String
    public let amountMicro: UInt64
    public var id: String { signature }
}

/// The events of transactions already read. A confirmed transaction does not
/// change, and a phone waiting to be paid asks about the same few every
/// second or two: public access points allow far fewer transaction lookups
/// than that, and refuse them all once the limit is passed.
final class EventMemory: @unchecked Sendable {
    private let lock = NSLock()
    private var events = [String: [ValpixEvent]]()
    private var order = [String]()

    func get(_ signature: String) -> [ValpixEvent]? { lock.withLock { events[signature] } }

    func set(_ signature: String, _ found: [ValpixEvent]) {
        lock.withLock {
            if events[signature] == nil { order.append(signature) }
            events[signature] = found
            if order.count > 200 { events[order.removeFirst()] = nil }
        }
    }
}

/// The valpix program, as a set of things a wallet can do.
public struct ValpixClient: Sendable {
    public let rpc: SolanaRPC
    public let program: ValpixProgram
    private let pollInterval: Duration
    private let timeout: Duration
    private let seen = EventMemory()

    public init(rpc: SolanaRPC, program: ValpixProgram, pollInterval: Duration = .milliseconds(500), timeout: Duration = .seconds(90)) {
        self.rpc = rpc
        self.program = program
        self.pollInterval = pollInterval
        self.timeout = timeout
    }

    public func state(for wallet: PublicKey, observedAt: Date = Date()) async throws -> WalletState {
        async let accounts = rpc.accounts([program.member(of: wallet), program.config, program.pool, program.vault, wallet])
        async let rentMin = rpc.minimumBalanceForRentExemption(0)
        async let chainTime = rpc.chainTime()
        let found = try await accounts
        guard let config = found[1].flatMap({ ConfigRecord(accountData: $0.data) }),
              let pool = found[2].flatMap({ PoolRecord(accountData: $0.data) })
        else { throw ClientError.programNotFound }
        let vaultLamports = found[3]?.lamports ?? 0
        return WalletState(
            member: found[0].flatMap { MemberRecord(accountData: $0.data) },
            config: config,
            pool: pool,
            solReserve: vaultLamports.subtractingReportingOverflow(try await rentMin).overflow ? 0 : vaultLamports - (try await rentMin),
            walletLamports: found[4]?.lamports ?? 0,
            clock: ChainClock(chainTime: TimeInterval(try await chainTime), observedAt: observedAt)
        )
    }

    /// Pays another member and waits until the chain confirms or refuses.
    public func pay(from keypair: Keypair, to recipient: PublicKey, amountMicro: UInt64, name: String) async -> Outcome {
        await submit(program.pay(payer: keypair.publicKey, recipient: recipient, amountMicro: amountMicro, name: name), signer: keypair)
    }

    /// Brings a newcomer in, paid for by the inviter.
    public func join(inviter: Keypair, newcomer: PublicKey) async -> Outcome {
        await submit(program.join(inviter: inviter.publicKey, newcomer: newcomer), signer: inviter)
    }

    /// Tops the wallet's SOL back up from the pool.
    public func reviveSelf(_ keypair: Keypair) async -> Outcome {
        await submit(program.reviveSelf(payer: keypair.publicKey), signer: keypair)
    }

    /// Signs, sends and follows one instruction to its end.
    ///
    /// It reports `confirmed` only when the chain says so, and it reports a
    /// failure that means "not paid" only when that is certain. Once the
    /// transaction may have left the phone, route trouble is retried, and if
    /// the answer never comes the outcome is `unconfirmed`, never "failed":
    /// telling someone a payment failed when it landed makes them pay twice.
    func submit(_ instruction: Instruction, signer: Keypair) async -> Outcome {
        // Nothing has been signed yet: failing here is a clean "no route".
        guard let latest = try? await rpc.latestBlockhash() else { return .failed(.noRoute) }
        let message = Transaction.compile(instructions: [instruction], feePayer: signer.publicKey, recentBlockhash: latest.blockhash)
        guard let wire = try? Transaction.sign(message: message, signers: [signer]) else {
            return .failed(.rejected("signing"))
        }
        // The transaction's own signature, so it can be followed whatever
        // the access point answers, or fails to answer.
        let signature = Base58.encode(wire.dropFirst().prefix(64))

        do {
            _ = try await rpc.send(wire)
        } catch let RPCError.rpc(_, message, logs) {
            // The access point refused it. Usually that means it never left;
            // but when an earlier access point passed it on and its answer
            // was lost, the next one refuses it as already seen. Look once
            // before telling anyone they have not paid.
            if let landed = try? await settledOutcome(signature, searchHistory: true) { return landed }
            return .failed(.rejected(Self.errorName(in: logs) ?? message))
        } catch {
            // No answer. It may have been passed on all the same: go and look.
        }

        let deadline = Date().addingTimeInterval(TimeInterval(timeout.components.seconds) + Double(timeout.components.attoseconds) / 1e18)
        while true {
            do {
                if let settled = try await settledOutcome(signature) { return settled }
                if try await rpc.blockHeight() > latest.lastValidBlockHeight {
                    // Too late to land now. One last look, in case it made
                    // one of the final blocks: and a thorough one, because a
                    // phone that slept since sending is asking about a
                    // transaction too old for the quick answer.
                    return try await settledOutcome(signature, searchHistory: true) ?? .failed(.expired)
                }
            } catch {
                // Route trouble: keep trying until the deadline.
            }
            if Date() >= deadline { return .failed(.unconfirmed) }
            try? await Task.sleep(for: pollInterval)
        }
    }

    /// The outcome, if the chain has one for this signature yet.
    private func settledOutcome(_ signature: String, searchHistory: Bool = false) async throws -> Outcome? {
        guard let status = try await rpc.signatureStatus(signature, searchHistory: searchHistory) else { return nil }
        if let error = status.error { return .failed(.rejected(error)) }
        return status.isConfirmed ? .confirmed(signature: signature) : nil
    }

    /// A transaction's valpix events, or `nil` when the access point does not
    /// have it (yet, or any more).
    private func events(of signature: String) async throws -> [ValpixEvent]? {
        if let known = seen.get(signature) { return known }
        guard let logs = try await rpc.transactionLogs(signature) else { return nil }
        let found = ValpixEvent.events(in: logs, programId: program.programId)
        seen.set(signature, found)
        return found
    }

    /// Finds "Error Code: Name." in a program log.
    static func errorName(in logs: [String]) -> String? {
        for line in logs {
            guard let range = line.range(of: "Error Code: ") else { continue }
            let rest = line[range.upperBound...]
            return String(rest.prefix { $0 != "." && $0 != " " })
        }
        return nil
    }

    /// Recent payments and joins, newest first, as this wallet saw them.
    /// Best-effort: an access point may have dropped older transactions.
    public func history(for wallet: PublicKey, limit: Int = 20) async throws -> [HistoryEntry] {
        let signatures = try await rpc.signatures(for: program.member(of: wallet), limit: limit)
        var entries = [HistoryEntry]()
        for info in signatures where !info.failed {
            guard let events = try await events(of: info.signature) else { continue }
            for event in events {
                switch event {
                case .paid(let paid) where paid.recipient == wallet:
                    entries.append(HistoryEntry(signature: info.signature, ts: paid.ts, kind: .received, counterparty: paid.payer, name: paid.name, amountMicro: paid.amount))
                case .paid(let paid) where paid.payer == wallet:
                    entries.append(HistoryEntry(signature: info.signature, ts: paid.ts, kind: .sent, counterparty: paid.recipient, name: "", amountMicro: paid.amount))
                case .joined(let joined) where joined.inviter == wallet:
                    entries.append(HistoryEntry(signature: info.signature, ts: joined.ts, kind: .invited, counterparty: joined.newcomer, name: "", amountMicro: joined.cost))
                case .joined(let joined) where joined.newcomer == wallet:
                    entries.append(HistoryEntry(signature: info.signature, ts: joined.ts, kind: .joined, counterparty: joined.inviter, name: "", amountMicro: 0))
                default:
                    break
                }
            }
        }
        return entries
    }
}
