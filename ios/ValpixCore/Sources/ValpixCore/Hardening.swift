import Foundation

// MARK: Which program wrote a log line

extension ValpixEvent {
    /// The valpix events in a transaction's log, counting only lines written
    /// while valpix itself was the running program. Any program can log the
    /// same bytes; only valpix's own are evidence that money moved.
    public static func events(in logs: [String], programId: PublicKey) -> [ValpixEvent] {
        var stack = [String]()
        var found = [ValpixEvent]()
        for line in logs {
            if line.hasPrefix("Program data: ") {
                if stack.last == programId.base58, let event = ValpixEvent(logLine: line) {
                    found.append(event)
                }
                continue
            }
            // "Program <id> invoke [n]" enters a program; "success" or
            // "failed" leaves it. Programs cannot forge these: the runtime
            // prefixes everything a program logs with "Program log: ".
            let parts = line.split(separator: " ", maxSplits: 3)
            guard parts.count >= 3, parts[0] == "Program" else { continue }
            if parts[2] == "invoke" {
                stack.append(String(parts[1]))
            } else if parts[2] == "success" || parts[2].hasPrefix("failed") {
                if stack.last == String(parts[1]) { stack.removeLast() }
            }
        }
        return found
    }
}

// MARK: Did the payment a seller is waiting for arrive

extension HistoryEntry {
    /// Whether this entry is the payment a QR on screen was asking for: an
    /// incoming payment, no older than the QR, and at least the amount asked.
    public func settles(requestedPesos: UInt64?, after start: TimeInterval) -> Bool {
        guard kind == .received, TimeInterval(ts) >= start else { return false }
        guard let requestedPesos else { return true }
        let (needed, overflow) = requestedPesos.multipliedReportingOverflow(by: Money.unit)
        return !overflow && amountMicro >= needed
    }
}

// MARK: What a link may make the app do

public enum LinkSource: Sendable {
    /// Read by the camera, or pasted, inside the app.
    case scanned
    /// Opened from outside: a link tapped in another app.
    case external
}

public struct WalletSituation: Sendable {
    public let own: PublicKey
    public let isMember: Bool
    /// The chain has answered that this phone's key has no account. Without
    /// that answer the key may be funded and merely out of sight.
    public let chainConfirmedNoAccount: Bool

    public init(own: PublicKey, isMember: Bool, chainConfirmedNoAccount: Bool) {
        self.own = own
        self.isMember = isMember
        self.chainConfirmedNoAccount = chainConfirmedNoAccount
    }
}

/// What to offer the person next. Every action still needs their approval.
public enum LinkAction: Equatable, Sendable {
    case pay(to: PublicKey, amountPesos: UInt64?, name: String)
    case invite(key: PublicKey, name: String)
    case adopt(seed: Data)
    case unlock(blob: Data)
    case ignore
}

public enum LinkPolicy {
    public static func action(for link: ValpixLink, from source: LinkSource, situation: WalletSituation) -> LinkAction {
        switch link {
        case .pay(let to, let amount, let name):
            return situation.isMember && to != situation.own ? .pay(to: to, amountPesos: amount, name: name) : .ignore
        case .join(let key, let name):
            return situation.isMember && key != situation.own ? .invite(key: key, name: name) : .ignore
        case .wallet(let seed):
            guard mayReceiveWallet(source, situation), Keypair(seed: seed)?.publicKey != situation.own else { return .ignore }
            return .adopt(seed: seed)
        case .backup(let blob):
            return mayReceiveWallet(source, situation) ? .unlock(blob: blob) : .ignore
        }
    }

    /// A wallet replaces the one on this phone, so it is offered only from
    /// the in-app scanner and only once the chain says there is nothing here
    /// to lose.
    private static func mayReceiveWallet(_ source: LinkSource, _ situation: WalletSituation) -> Bool {
        source == .scanned && !situation.isMember && situation.chainConfirmedNoAccount
    }
}

// MARK: Keeping the wallet key

public enum StoredSecret: Equatable, Sendable {
    case found(Data)
    /// The store answered: nothing is there.
    case missing
    /// The store could not be read right now (a locked phone, for one).
    /// This says nothing about whether a wallet exists.
    case unavailable
}

/// Where the wallet's secret lives. The app backs this with the Keychain.
public protocol SecretStore {
    func read() -> StoredSecret
    /// Stores a secret where there was none. Must not overwrite.
    func add(_ secret: Data) -> Bool
    /// Replaces the stored secret in place.
    func replace(with secret: Data) -> Bool
}

/// The only code that decides to create or replace a wallet. It never
/// deletes, and it creates only when the store has positively said there is
/// no wallet: a failed read is not an empty store.
public enum WalletKeeper {
    /// The stored wallet, or a new one stored just now. `nil` when the store
    /// cannot be relied on, in which case the app must not run on any key.
    public static func loadOrCreate(in store: SecretStore) -> Keypair? {
        switch store.read() {
        case .found(let seed):
            return Keypair(seed: seed)
        case .unavailable:
            return nil
        case .missing:
            let fresh = Keypair()
            guard store.add(fresh.seed), store.read() == .found(fresh.seed) else { return nil }
            return fresh
        }
    }

    /// Puts another wallet on this phone. `nil` when it was not kept.
    public static func replace(with seed: Data, in store: SecretStore) -> Keypair? {
        guard let incoming = Keypair(seed: seed) else { return nil }
        let stored: Bool
        switch store.read() {
        case .found: stored = store.replace(with: seed)
        case .missing: stored = store.add(seed)
        case .unavailable: return nil
        }
        guard stored, store.read() == .found(seed) else { return nil }
        return incoming
    }
}

// MARK: More than one access point

/// Tries each access point in turn until one answers. An answer that is a
/// JSON-RPC error still counts as an answer.
public struct FallbackTransport: RPCTransport {
    private let transports: [RPCTransport]

    public init(_ transports: [RPCTransport]) { self.transports = transports }

    public func post(_ body: Data) async throws -> Data {
        var last: Error = RPCError.transport("no access points configured")
        for transport in transports {
            do {
                return try await transport.post(body)
            } catch {
                last = error
            }
        }
        throw last
    }
}
