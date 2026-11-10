import Foundation

public struct AccountMeta: Hashable, Sendable {
    public let publicKey: PublicKey
    public let isSigner: Bool
    public let isWritable: Bool

    public init(_ publicKey: PublicKey, signer: Bool = false, writable: Bool = false) {
        self.publicKey = publicKey
        self.isSigner = signer
        self.isWritable = writable
    }
}

public struct Instruction: Sendable {
    public let programId: PublicKey
    public let accounts: [AccountMeta]
    public let data: Data

    public init(programId: PublicKey, accounts: [AccountMeta], data: Data) {
        self.programId = programId
        self.accounts = accounts
        self.data = data
    }
}

public enum TransactionError: Error, Equatable {
    case missingSigner(PublicKey)
}

/// A compiled legacy Solana message: what gets signed.
public struct Message: Sendable {
    struct CompiledInstruction: Sendable {
        let programIndex: UInt8
        let accountIndexes: [UInt8]
        let data: Data
    }

    public let requiredSignatures: Int
    let readonlySigned: Int
    let readonlyUnsigned: Int
    public let accountKeys: [PublicKey]
    public let recentBlockhash: Data
    let instructions: [CompiledInstruction]

    public func serialize() -> Data {
        var out = Data([UInt8(requiredSignatures), UInt8(readonlySigned), UInt8(readonlyUnsigned)])
        out.append(Transaction.compactU16(accountKeys.count))
        for key in accountKeys { out.append(key.data) }
        out.append(recentBlockhash)
        out.append(Transaction.compactU16(instructions.count))
        for ix in instructions {
            out.append(ix.programIndex)
            out.append(Transaction.compactU16(ix.accountIndexes.count))
            out.append(contentsOf: ix.accountIndexes)
            out.append(Transaction.compactU16(ix.data.count))
            out.append(ix.data)
        }
        return out
    }
}

public enum Transaction {
    /// Solana's variable-length encoding for small counts.
    static func compactU16(_ value: Int) -> Data {
        var remaining = value
        var out = Data()
        repeat {
            var byte = UInt8(remaining & 0x7f)
            remaining >>= 7
            if remaining > 0 { byte |= 0x80 }
            out.append(byte)
        } while remaining > 0
        return out
    }

    /// Gathers every account the instructions touch into the single ordered
    /// list Solana wants: the fee payer first, then other signers, then the
    /// rest, with writable accounts ahead of read-only ones in each group.
    public static func compile(instructions: [Instruction], feePayer: PublicKey, recentBlockhash: Data) -> Message {
        var order = [PublicKey]()
        var flags = [PublicKey: (signer: Bool, writable: Bool)]()
        func add(_ key: PublicKey, signer: Bool, writable: Bool) {
            if let existing = flags[key] {
                flags[key] = (existing.signer || signer, existing.writable || writable)
            } else {
                flags[key] = (signer, writable)
                order.append(key)
            }
        }
        add(feePayer, signer: true, writable: true)
        for ix in instructions {
            for meta in ix.accounts { add(meta.publicKey, signer: meta.isSigner, writable: meta.isWritable) }
            add(ix.programId, signer: false, writable: false)
        }

        func group(signer: Bool, writable: Bool) -> [PublicKey] {
            order.filter { flags[$0]! == (signer, writable) }
        }
        let keys = group(signer: true, writable: true) + group(signer: true, writable: false)
            + group(signer: false, writable: true) + group(signer: false, writable: false)
        let index = Dictionary(uniqueKeysWithValues: keys.enumerated().map { ($1, UInt8($0)) })

        return Message(
            requiredSignatures: keys.filter { flags[$0]!.signer }.count,
            readonlySigned: group(signer: true, writable: false).count,
            readonlyUnsigned: group(signer: false, writable: false).count,
            accountKeys: keys,
            recentBlockhash: recentBlockhash,
            instructions: instructions.map { ix in
                Message.CompiledInstruction(
                    programIndex: index[ix.programId]!,
                    accountIndexes: ix.accounts.map { index[$0.publicKey]! },
                    data: ix.data
                )
            }
        )
    }

    /// The bytes to hand to `sendTransaction`: signatures, then the message.
    public static func sign(message: Message, signers: [Keypair]) throws -> Data {
        let body = message.serialize()
        var out = compactU16(message.requiredSignatures)
        for key in message.accountKeys.prefix(message.requiredSignatures) {
            guard let signer = signers.first(where: { $0.publicKey == key }) else {
                throw TransactionError.missingSigner(key)
            }
            out.append(signer.sign(body))
        }
        out.append(body)
        return out
    }
}
