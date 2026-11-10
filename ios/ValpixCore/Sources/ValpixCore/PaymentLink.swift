import Foundation

/// What a valpix QR code says. Everything a camera reads goes through
/// `init?`, which accepts only these exact shapes.
public enum ValpixLink: Equatable, Sendable {
    /// "Pay me." Without an amount, the payer types one.
    case pay(to: PublicKey, amountPesos: UInt64?, name: String)
    /// "Bring me in." Shown by a phone that has no account yet.
    case join(key: PublicKey, name: String)
    /// The wallet itself, for moving to another phone. Secret.
    case wallet(seed: Data)
    /// The wallet sealed with a password.
    case backup(blob: Data)

    /// The largest amount a link may ask for, in pesos.
    public static let maxAmountPesos: UInt64 = 1_000_000_000
    public static let maxNameBytes = 32

    public init?(_ text: String) {
        guard let parts = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              parts.scheme?.lowercased() == "valpix"
        else { return nil }
        // "valpix:pay?..." puts the action in the path, "valpix://pay?..." in the host.
        let action = (parts.host?.isEmpty == false ? parts.host! : parts.path).lowercased()
        var query = [String: String]()
        for item in parts.queryItems ?? [] {
            guard query[item.name] == nil, let value = item.value else { return nil }
            query[item.name] = value
        }

        func name() -> String? {
            let value = query["name"] ?? ""
            guard value.utf8.count <= Self.maxNameBytes,
                  !value.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) })
            else { return nil }
            return value
        }

        switch action {
        case "pay":
            guard let to = query["to"].flatMap(PublicKey.init(base58:)), let name = name() else { return nil }
            var amount: UInt64?
            if let raw = query["amount"] {
                guard !raw.isEmpty, raw.allSatisfy({ $0.isASCII && $0.isNumber }),
                      let value = UInt64(raw), value > 0, value <= Self.maxAmountPesos
                else { return nil }
                amount = value
            }
            self = .pay(to: to, amountPesos: amount, name: name)
        case "join":
            guard let key = query["key"].flatMap(PublicKey.init(base58:)), let name = name() else { return nil }
            self = .join(key: key, name: name)
        case "wallet":
            guard let seed = query["seed"].flatMap(Base58.decode), seed.count == 32 else { return nil }
            self = .wallet(seed: seed)
        case "backup":
            guard let blob = query["d"].flatMap(Self.decodeBase64URL), !blob.isEmpty else { return nil }
            self = .backup(blob: blob)
        default:
            return nil
        }
    }

    public var string: String {
        var parts = URLComponents()
        parts.scheme = "valpix"
        switch self {
        case .pay(let to, let amount, let name):
            parts.path = "pay"
            parts.queryItems = [URLQueryItem(name: "to", value: to.base58)]
            if let amount { parts.queryItems?.append(URLQueryItem(name: "amount", value: String(amount))) }
            if !name.isEmpty { parts.queryItems?.append(URLQueryItem(name: "name", value: name)) }
        case .join(let key, let name):
            parts.path = "join"
            parts.queryItems = [URLQueryItem(name: "key", value: key.base58)]
            if !name.isEmpty { parts.queryItems?.append(URLQueryItem(name: "name", value: name)) }
        case .wallet(let seed):
            parts.path = "wallet"
            parts.queryItems = [URLQueryItem(name: "seed", value: Base58.encode(seed))]
        case .backup(let blob):
            parts.path = "backup"
            parts.queryItems = [URLQueryItem(name: "d", value: Self.encodeBase64URL(blob))]
        }
        // URLComponents leaves "&" and "+" alone inside a value; escape them
        // so a name like "pan & café" survives the round trip.
        return (parts.string ?? "")
            .replacingOccurrences(of: "+", with: "%2B")
    }

    private static func encodeBase64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func decodeBase64URL(_ text: String) -> Data? {
        guard text.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }) else { return nil }
        var base64 = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 { base64.append("=") }
        return Data(base64Encoded: base64)
    }
}
