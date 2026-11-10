import Foundation
import ValpixCore

/// Where the app reaches Solana and which program it talks to.
enum AppConfig {
    /// Access points, tried in order: the person's own (if they set one),
    /// then the list shipped in Info.plist.
    static var accessPoints: [URL] {
        var texts = [String]()
        #if DEBUG
        // Debug builds only: point one launch somewhere else.
        if let override = ProcessInfo.processInfo.environment["VALPIX_RPC"] { return [URL(string: override)!] }
        #endif
        if let own = UserDefaults.standard.string(forKey: "accessPoint"), !own.isEmpty { texts.append(own) }
        texts += Bundle.main.object(forInfoDictionaryKey: "ValpixRPCURLs") as? [String] ?? []
        return texts.compactMap(URL.init(string:)).filter { $0.scheme == "https" || $0.scheme == "http" }
    }

    static var transport: RPCTransport {
        FallbackTransport(accessPoints.map { HTTPTransport(url: $0) })
    }

    static var programId: PublicKey {
        let text = Bundle.main.object(forInfoDictionaryKey: "ValpixProgramID") as? String ?? ""
        return PublicKey(base58: text)!
    }

    #if DEBUG
    /// Debug builds only: lets a script drive the app on the simulator.
    static func flag(_ name: String) -> Bool { ProcessInfo.processInfo.environment[name] == "1" }
    #else
    static func flag(_ name: String) -> Bool { false }
    #endif
}
