import Foundation
@testable import ValpixCore

/// Test-only access point: answers each JSON-RPC method from a table, and
/// records what it was asked.
final class FakeTransport: RPCTransport, @unchecked Sendable {
    typealias Handler = @Sendable (_ params: [Any]) throws -> Any?
    private let lock = NSLock()
    private var handlers = [String: Handler]()
    private var log = [(method: String, params: [Any])]()

    func on(_ method: String, _ handler: @escaping Handler) {
        lock.lock(); defer { lock.unlock() }
        handlers[method] = handler
    }

    var calls: [(method: String, params: [Any])] {
        lock.lock(); defer { lock.unlock() }
        return log
    }

    func post(_ body: Data) async throws -> Data {
        let request = try JSONSerialization.jsonObject(with: body) as! [String: Any]
        let method = request["method"] as! String
        let params = request["params"] as? [Any] ?? []
        let handler = lock.withLock { () -> Handler? in
            log.append((method, params))
            return handlers[method]
        }
        guard let handler else { throw RPCError.transport("no handler for \(method)") }
        do {
            let result = try handler(params)
            return try JSONSerialization.data(withJSONObject: ["jsonrpc": "2.0", "id": 1, "result": result ?? NSNull()])
        } catch let error as FakeRPCFailure {
            return try JSONSerialization.data(withJSONObject: [
                "jsonrpc": "2.0", "id": 1,
                "error": ["code": error.code, "message": error.message, "data": ["logs": error.logs]],
            ])
        }
    }
}

struct FakeRPCFailure: Error {
    let code: Int
    let message: String
    var logs: [String] = []
}

/// An access point that cannot be reached at all.
struct DeadTransport: RPCTransport {
    func post(_ body: Data) async throws -> Data { throw URLError(.notConnectedToInternet) }
}
