import Foundation

public enum ClientError: Error, Equatable {
    /// The program's own records are missing: wrong network or not set up.
    case programNotFound
}

/// Everything the app needs to draw the home screen, from one round trip.
public struct WalletState: Equatable, Sendable {
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

public enum FailureReason: Equatable, Sendable {
    /// No access point could be reached.
    case noRoute
    /// The program or the chain refused it; carries the error's name or number.
    case rejected(String)
    /// It did not confirm before the transaction's time ran out.
    case expired
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

/// The valpix program, as a set of things a wallet can do.
public struct ValpixClient: Sendable {
    public let rpc: SolanaRPC
    public let program: ValpixProgram
    private let pollInterval: Duration
    private let timeout: Duration

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

    /// Signs, sends and follows one instruction to a definite end. It never
    /// reports success unless the chain confirmed the transaction.
    func submit(_ instruction: Instruction, signer: Keypair) async -> Outcome {
        let signature: String
        let lastValidBlockHeight: UInt64
        do {
            let latest = try await rpc.latestBlockhash()
            lastValidBlockHeight = latest.lastValidBlockHeight
            let message = Transaction.compile(instructions: [instruction], feePayer: signer.publicKey, recentBlockhash: latest.blockhash)
            signature = try await rpc.send(try Transaction.sign(message: message, signers: [signer]))
        } catch let RPCError.rpc(_, message, logs) {
            return .failed(.rejected(Self.errorName(in: logs) ?? message))
        } catch {
            return .failed(.noRoute)
        }

        let deadline = ContinuousClock.now + timeout
        while ContinuousClock.now < deadline {
            do {
                if let status = try await rpc.signatureStatus(signature) {
                    if let error = status.error { return .failed(.rejected(error)) }
                    if status.isConfirmed { return .confirmed(signature: signature) }
                } else if try await rpc.blockHeight() > lastValidBlockHeight {
                    return .failed(.expired)
                }
            } catch {
                return .failed(.noRoute)
            }
            try? await Task.sleep(for: pollInterval)
        }
        return .failed(.expired)
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
            guard let logs = try await rpc.transactionLogs(info.signature) else { continue }
            for event in logs.compactMap(ValpixEvent.init(logLine:)) {
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
