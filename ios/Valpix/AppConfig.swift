import Foundation
import ValpixCore

/// Where the app reaches Solana and which program it talks to. Set in
/// Info.plist; `VALPIX_RPC` in the environment overrides the access point,
/// which is how the simulator is pointed at a local validator.
enum AppConfig {
    static var rpcURL: URL {
        let text = ProcessInfo.processInfo.environment["VALPIX_RPC"]
            ?? Bundle.main.object(forInfoDictionaryKey: "ValpixRPCURL") as? String
            ?? "http://127.0.0.1:8899"
        return URL(string: text)!
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
