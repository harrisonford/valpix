import Foundation
@testable import ValpixCore

/// Test-only: reads a legacy Solana message back into a form that does not
/// depend on the order accounts were listed in, so our output can be
/// compared with what Solana's own library produced.
struct ParsedMessage: Equatable {
    struct Account: Equatable { let key: String; let isSigner: Bool; let isWritable: Bool }
    struct Ix: Equatable { let program: String; let accounts: [Account]; let dataHex: String }
    let feePayer: String
    let signatureCount: Int
    let blockhash: String
    let instructions: [Ix]

    init(_ data: Data) {
        var bytes = [UInt8](data)[...]
        func compact() -> Int {
            var value = 0, shift = 0
            while true {
                let byte = bytes.removeFirst()
                value |= Int(byte & 0x7f) << shift
                if byte & 0x80 == 0 { return value }
                shift += 7
            }
        }
        let signatures = Int(bytes.removeFirst())
        let readonlySigned = Int(bytes.removeFirst())
        let readonlyUnsigned = Int(bytes.removeFirst())
        let keyCount = compact()
        var keys = [String]()
        for _ in 0..<keyCount {
            keys.append(Base58.encode(Data(bytes.prefix(32))))
            bytes = bytes.dropFirst(32)
        }
        blockhash = Base58.encode(Data(bytes.prefix(32)))
        bytes = bytes.dropFirst(32)
        func account(_ index: Int) -> Account {
            let signer = index < signatures
            let writable = signer ? index < signatures - readonlySigned : index < keyCount - readonlyUnsigned
            return Account(key: keys[index], isSigner: signer, isWritable: writable)
        }
        var parsed = [Ix]()
        for _ in 0..<compact() {
            let program = keys[Int(bytes.removeFirst())]
            var accounts = [Account]()
            for _ in 0..<compact() { accounts.append(account(Int(bytes.removeFirst()))) }
            let length = compact()
            parsed.append(Ix(program: program, accounts: accounts, dataHex: Data(bytes.prefix(length)).hex))
            bytes = bytes.dropFirst(length)
        }
        precondition(bytes.isEmpty, "trailing bytes in message")
        feePayer = keys[0]
        signatureCount = signatures
        instructions = parsed
    }
}
