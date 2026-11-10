import Foundation
import Observation
import ValpixCore

/// The app's state and everything a person can do in it.
@Observable
final class AppModel {
    enum Phase { case loading, needsJoin, ready, storageFailed }

    enum Flow: Equatable {
        case charge
        case scan
        case confirm(to: PublicKey, amountPesos: UInt64?, name: String)
        case invite(key: PublicKey, name: String)
        case adopt(seed: Data)
        case unlock(blob: Data)
        case sending(amountPesos: UInt64, name: String)
        case done(amountPesos: UInt64?, name: String, incoming: Bool)
        case failed(amountPesos: UInt64?, reason: FailureReason)
        case history
        case wallet
    }

    private(set) var phase: Phase = .loading
    private(set) var state: WalletState?
    /// Whether an access point answered the last time we asked.
    private(set) var hasRoute = true
    private(set) var history: [HistoryEntry] = []
    private(set) var keypair: Keypair
    /// Until the person has unlocked the app with face, fingerprint or passcode.
    private(set) var locked = true
    /// The chain has answered that this phone's key has no account.
    private var chainConfirmedNoAccount = false
    var flow: Flow?
    var name: String { didSet { defaults.set(name, forKey: "name") } }

    private var client: ValpixClient
    private let store = KeychainStore()
    private let defaults = UserDefaults.standard
    private var names: [String: String]
    private var refreshTask: Task<Void, Never>?

    init() {
        if let stored = WalletKeeper.loadOrCreate(in: KeychainStore()) {
            keypair = stored
        } else {
            // The Keychain could not be read or would not keep a key. Never
            // operate on a key that is not safely stored.
            keypair = Keypair()
            phase = .storageFailed
        }
        client = ValpixClient(rpc: SolanaRPC(transport: AppConfig.transport), program: ValpixProgram(programId: AppConfig.programId))
        name = defaults.string(forKey: "name") ?? ""
        names = defaults.dictionary(forKey: "names") as? [String: String] ?? [:]
        // Start from what this phone last knew, so a member who opens the app
        // with no route still sees their number ticking.
        if phase != .storageFailed,
           let saved = defaults.data(forKey: "state").flatMap(WalletState.init(snapshot:)),
           saved.member?.wallet == keypair.publicKey {
            state = saved
            phase = .ready
        }
        #if DEBUG
        writeDebugKey()
        #endif
    }

    var publicKey: PublicKey { keypair.publicKey }

    /// The name sent with payments and shown in QR codes: at most 32 bytes.
    var sharedName: String { ValpixLink.shareableName(name) }

    // MARK: Lock

    func unlockApp() async {
        guard locked else { return }
        if await Authenticator.confirm(reason: "Abrir valpix") { locked = false }
    }

    func lock() {
        locked = true
        flow = nil
    }

    /// The person's own access point, tried before the built-in ones.
    var accessPoint: String {
        get { defaults.string(forKey: "accessPoint") ?? "" }
        set {
            defaults.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: "accessPoint")
            client = ValpixClient(rpc: SolanaRPC(transport: AppConfig.transport), program: ValpixProgram(programId: AppConfig.programId))
        }
    }

    // MARK: Keeping up with the chain

    func start() {
        guard refreshTask == nil, phase != .storageFailed else { return }
        refreshTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refresh()
                let waiting = self?.phase != .ready
                try? await Task.sleep(for: .seconds(waiting ? 2 : 5))
            }
        }
    }

    func refresh() async {
        do {
            let fresh = try await client.state(for: publicKey)
            state = fresh
            defaults.set(fresh.snapshot, forKey: "state")
            hasRoute = true
            phase = fresh.member == nil ? .needsJoin : .ready
            chainConfirmedNoAccount = fresh.member == nil
            #if DEBUG
            openDebugLinkOnce()
            #endif
        } catch {
            hasRoute = false
            // Without a route we cannot tell a member from a newcomer; keep
            // whatever we last knew, and show the join code only if nothing.
            if phase == .loading, state == nil { phase = .needsJoin }
        }
    }

    func loadHistory() async {
        if let entries = try? await client.history(for: publicKey, limit: 25) {
            history = entries
            for entry in entries where entry.kind == .received && !entry.name.isEmpty {
                remember(name: entry.name, for: entry.counterparty)
            }
        }
    }

    // MARK: Money

    func balance(at date: Date) -> UInt64 { state?.balance(at: date) ?? 0 }

    func canPay(_ amountPesos: UInt64) -> Bool {
        guard let state, hasRoute else { return false }
        return Money.canPay(amountPesos: amountPesos, balance: state.balance(at: Date()), fee: state.fee)
    }

    func pay(to recipient: PublicKey, amountPesos: UInt64, name recipientName: String) async {
        guard canPay(amountPesos) else { return }
        guard await Authenticator.confirm(reason: "Aprobar pago de \(PesoFormat.whole(amountPesos))") else { return }
        remember(name: recipientName, for: recipient)
        flow = .sending(amountPesos: amountPesos, name: recipientName)
        let outcome = await client.pay(
            from: keypair, to: recipient, amountMicro: Money.micro(pesos: amountPesos), name: sharedName
        )
        switch outcome {
        case .confirmed: flow = .done(amountPesos: amountPesos, name: recipientName, incoming: false)
        case .failed(let reason): flow = .failed(amountPesos: amountPesos, reason: reason)
        }
        await refresh()
    }

    func invite(_ newcomer: PublicKey, name newcomerName: String) async {
        guard await Authenticator.confirm(reason: "Invitar a \(newcomerName.isEmpty ? "una persona" : newcomerName)") else { return }
        remember(name: newcomerName, for: newcomer)
        flow = .sending(amountPesos: 0, name: newcomerName)
        switch await client.join(inviter: keypair, newcomer: newcomer) {
        case .confirmed: flow = .done(amountPesos: nil, name: newcomerName, incoming: false)
        case .failed(let reason): flow = .failed(amountPesos: nil, reason: reason)
        }
        await refresh()
    }

    /// What bringing someone in costs right now, in whole pesos, if the pool can cover it.
    var inviteCostPesos: UInt64? {
        guard let state else { return nil }
        // Record rent is not known to the phone exactly; the float plus the
        // refund plus a typical record deposit is a close estimate.
        let lamports = state.config.floatLamports + state.config.feeRefundLamports + 1_700_000
        guard let price = Money.quoteClpcIn(solReserve: state.solReserve, clpcReserve: state.pool.clpcReserve, lamportsOut: lamports) else { return nil }
        return (price + state.config.joinSurcharge + Money.unit - 1) / Money.unit
    }

    /// Waits for a payment to arrive after `start` (chain time). Used while a
    /// QR is on screen.
    func nextIncomingPayment(requestedPesos: UInt64?, after start: TimeInterval) async -> HistoryEntry? {
        while !Task.isCancelled {
            if let entries = try? await client.history(for: publicKey, limit: 5),
               let hit = entries.first(where: { $0.settles(requestedPesos: requestedPesos, after: start) }) {
                return hit
            }
            try? await Task.sleep(for: .milliseconds(1_500))
        }
        return nil
    }

    var chainNow: TimeInterval { state?.clock.now() ?? Date().timeIntervalSince1970 }

    // MARK: Links (scanned QR codes and opened valpix: links)

    /// Handles a scanned code or an opened link. Returns false when it is
    /// not a valpix link or there is nothing to do with it.
    @discardableResult
    func open(text: String, from source: LinkSource) -> Bool {
        guard !locked, phase != .storageFailed, let link = ValpixLink(text) else { return false }
        let situation = WalletSituation(
            own: publicKey, isMember: phase == .ready, chainConfirmedNoAccount: chainConfirmedNoAccount
        )
        switch LinkPolicy.action(for: link, from: source, situation: situation) {
        case .pay(let to, let amount, let name): flow = .confirm(to: to, amountPesos: amount, name: name)
        case .invite(let key, let name): flow = .invite(key: key, name: name)
        case .adopt(let seed): flow = .adopt(seed: seed)
        case .unlock(let blob): flow = .unlock(blob: blob)
        case .ignore: return false
        }
        return true
    }

    /// Replaces this phone's wallet with a scanned one, after the person
    /// approves. `LinkPolicy` only offers this while the chain says this
    /// phone has no account, so it can never overwrite a funded wallet.
    func adopt(seed: Data) async -> Bool {
        guard phase == .needsJoin, chainConfirmedNoAccount,
              await Authenticator.confirm(reason: "Usar esta billetera en este teléfono"),
              let imported = WalletKeeper.replace(with: seed, in: store)
        else { return false }
        keypair = imported
        defaults.removeObject(forKey: "state")
        state = nil
        chainConfirmedNoAccount = false
        phase = .loading
        flow = nil
        await refresh()
        return true
    }

    func unlock(blob: Data, password: String) async -> Bool {
        guard let seed = WalletBackup.open(blob, password: password) else { return false }
        return await adopt(seed: seed)
    }

    /// The secret QR contents. Callers must authenticate first.
    var walletLink: String { ValpixLink.wallet(seed: keypair.seed).string }
    func backupLink(password: String) -> String? {
        (try? WalletBackup.seal(seed: keypair.seed, password: password)).map { ValpixLink.backup(blob: $0).string }
    }

    // MARK: Names seen on this phone

    func remember(name: String, for key: PublicKey) {
        guard !name.isEmpty else { return }
        names[key.base58] = name
        defaults.set(names, forKey: "names")
    }

    func displayName(for key: PublicKey, fallback: String = "") -> String {
        if let known = names[key.base58] { return known }
        if !fallback.isEmpty { return fallback }
        return String(key.base58.prefix(4)) + "…" + String(key.base58.suffix(4))
    }

    #if DEBUG
    private var debugLinkOpened = false
    /// Debug builds only: `VALPIX_OPEN=<valpix: link>` in the environment is
    /// treated as a scanned code once the app is ready. A script cannot tap
    /// through the system's "Open in valpix?" prompt, so this stands in for it.
    private func openDebugLinkOnce() {
        guard !debugLinkOpened, !locked, phase != .loading,
              let text = ProcessInfo.processInfo.environment["VALPIX_OPEN"]
        else { return }
        debugLinkOpened = true
        switch text {
        case "history": flow = .history
        case "charge": flow = .charge
        case "wallet": flow = .wallet
        default: open(text: text, from: .scanned)
        }
    }

    /// Lets a script on the Mac learn the simulator wallet's address.
    private func writeDebugKey() {
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("debug-pubkey.txt")
        try? publicKey.base58.write(to: url, atomically: true, encoding: .utf8)
    }
    #endif
}
