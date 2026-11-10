import Foundation

/// How a JSON-RPC request reaches a Solana access point. The app uses HTTPS;
/// later a nearby phone over Bluetooth can stand in for it.
public protocol RPCTransport: Sendable {
    /// Posts one JSON-RPC request body and returns the response body.
    func post(_ body: Data) async throws -> Data
}

public enum RPCError: Error, Equatable {
    /// The access point could not be reached.
    case transport(String)
    /// The access point answered with an error. `logs` holds the program's
    /// log when a transaction was rejected before being sent on.
    case rpc(code: Int, message: String, logs: [String])
    case malformed
}

public struct HTTPTransport: RPCTransport {
    public let url: URL
    private let session: URLSession

    public init(url: URL, timeout: TimeInterval = 10) {
        self.url = url
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = timeout
        config.waitsForConnectivity = false
        session = URLSession(configuration: config)
    }

    public func post(_ body: Data) async throws -> Data {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw RPCError.transport("HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0)")
        }
        return data
    }
}

public struct AccountInfo: Equatable, Sendable {
    public let lamports: UInt64
    public let data: Data
}

public struct SignatureStatus: Equatable, Sendable {
    /// Confirmed or finalized by the cluster.
    public let isConfirmed: Bool
    /// A short description of the error the chain recorded, if any.
    public let error: String?
}

public struct SignatureInfo: Equatable, Sendable {
    public let signature: String
    public let blockTime: Int64?
    public let failed: Bool
}

/// The handful of Solana JSON-RPC methods valpix needs.
public struct SolanaRPC: Sendable {
    private let transport: RPCTransport

    public init(transport: RPCTransport) { self.transport = transport }
    public init(url: URL) { self.init(transport: HTTPTransport(url: url)) }

    private static var commitment: [String: Any] { ["commitment": "confirmed"] }

    /// Sends one request and returns its `result`.
    private func call(_ method: String, _ params: [Any]) async throws -> Any {
        let body = try JSONSerialization.data(withJSONObject: [
            "jsonrpc": "2.0", "id": 1, "method": method, "params": params,
        ])
        let response: Data
        do {
            response = try await transport.post(body)
        } catch let error as RPCError {
            throw error
        } catch {
            throw RPCError.transport(String(describing: error))
        }
        guard let object = try? JSONSerialization.jsonObject(with: response) as? [String: Any] else {
            throw RPCError.malformed
        }
        if let error = object["error"] as? [String: Any] {
            let logs = (error["data"] as? [String: Any])?["logs"] as? [String] ?? []
            throw RPCError.rpc(
                code: (error["code"] as? NSNumber)?.intValue ?? 0,
                message: error["message"] as? String ?? "",
                logs: logs
            )
        }
        guard let result = object["result"] else { throw RPCError.malformed }
        return result
    }

    private static func value(_ result: Any) throws -> Any {
        guard let value = (result as? [String: Any])?["value"] else { throw RPCError.malformed }
        return value
    }

    private static func unsigned(_ any: Any?) throws -> UInt64 {
        guard let number = any as? NSNumber else { throw RPCError.malformed }
        return number.uint64Value
    }

    public func latestBlockhash() async throws -> (blockhash: Data, lastValidBlockHeight: UInt64) {
        let value = try Self.value(try await call("getLatestBlockhash", [Self.commitment])) as? [String: Any]
        guard let text = value?["blockhash"] as? String, let hash = Base58.decode(text), hash.count == 32 else {
            throw RPCError.malformed
        }
        return (hash, try Self.unsigned(value?["lastValidBlockHeight"]))
    }

    /// Several accounts in one request. `nil` where an address holds nothing.
    public func accounts(_ keys: [PublicKey]) async throws -> [AccountInfo?] {
        var options = Self.commitment
        options["encoding"] = "base64"
        let value = try Self.value(try await call("getMultipleAccounts", [keys.map(\.base58), options]))
        guard let list = value as? [Any], list.count == keys.count else { throw RPCError.malformed }
        return try list.map { item in
            guard let account = item as? [String: Any] else { return nil }
            guard let encoded = (account["data"] as? [Any])?.first as? String,
                  let data = Data(base64Encoded: encoded)
            else { throw RPCError.malformed }
            return AccountInfo(lamports: try Self.unsigned(account["lamports"]), data: data)
        }
    }

    public func minimumBalanceForRentExemption(_ size: Int) async throws -> UInt64 {
        try Self.unsigned(try await call("getMinimumBalanceForRentExemption", [size]))
    }

    /// Submits a signed transaction. The access point simulates it first and
    /// throws `RPCError.rpc` with the program's log when it would fail.
    public func send(_ wire: Data) async throws -> String {
        var options = Self.commitment
        options["encoding"] = "base64"
        options["preflightCommitment"] = "confirmed"
        guard let signature = try await call("sendTransaction", [wire.base64EncodedString(), options]) as? String else {
            throw RPCError.malformed
        }
        return signature
    }

    /// `nil` while the cluster has not seen the transaction.
    public func signatureStatus(_ signature: String) async throws -> SignatureStatus? {
        let value = try Self.value(try await call("getSignatureStatuses", [[signature]]))
        guard let status = (value as? [Any])?.first as? [String: Any] else { return nil }
        let level = status["confirmationStatus"] as? String
        var error: String?
        if let raw = status["err"], !(raw is NSNull) {
            error = Self.describe(error: raw)
        }
        return SignatureStatus(isConfirmed: level == "confirmed" || level == "finalized", error: error)
    }

    /// Pulls the custom program error number out of a transaction error.
    private static func describe(error: Any) -> String {
        if let dict = error as? [String: Any],
           let parts = dict["InstructionError"] as? [Any], parts.count == 2,
           let custom = (parts[1] as? [String: Any])?["Custom"] as? NSNumber {
            return custom.stringValue
        }
        if let text = error as? String { return text }
        return (try? JSONSerialization.data(withJSONObject: error)).flatMap { String(data: $0, encoding: .utf8) } ?? "error"
    }

    public func blockHeight() async throws -> UInt64 {
        try Self.unsigned(try await call("getBlockHeight", [Self.commitment]))
    }

    /// The chain's clock, in seconds since 1970.
    public func chainTime() async throws -> Int64 {
        let slot = try Self.unsigned(try await call("getSlot", [Self.commitment]))
        guard let time = try await call("getBlockTime", [slot]) as? NSNumber else { throw RPCError.malformed }
        return time.int64Value
    }

    /// The most recent transactions that touched an address, newest first.
    public func signatures(for address: PublicKey, limit: Int) async throws -> [SignatureInfo] {
        var options = Self.commitment
        options["limit"] = limit
        guard let list = try await call("getSignaturesForAddress", [address.base58, options]) as? [[String: Any]] else {
            throw RPCError.malformed
        }
        return list.compactMap { item in
            guard let signature = item["signature"] as? String else { return nil }
            let failed = item["err"].map { !($0 is NSNull) } ?? false
            return SignatureInfo(signature: signature, blockTime: (item["blockTime"] as? NSNumber)?.int64Value, failed: failed)
        }
    }

    /// A confirmed transaction's log, or `nil` when the access point no
    /// longer has it.
    public func transactionLogs(_ signature: String) async throws -> [String]? {
        var options = Self.commitment
        options["encoding"] = "json"
        options["maxSupportedTransactionVersion"] = 0
        let result = try await call("getTransaction", [signature, options])
        return ((result as? [String: Any])?["meta"] as? [String: Any])?["logMessages"] as? [String]
    }

    /// Test networks only: asks the faucet for SOL.
    public func requestAirdrop(to address: PublicKey, lamports: UInt64) async throws -> String {
        guard let signature = try await call("requestAirdrop", [address.base58, lamports]) as? String else {
            throw RPCError.malformed
        }
        return signature
    }
}
