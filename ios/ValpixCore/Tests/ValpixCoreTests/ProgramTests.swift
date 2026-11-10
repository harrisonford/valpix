import CryptoKit
import Foundation
import Testing
@testable import ValpixCore

private let program = ValpixProgram(programId: PublicKey(base58: Vectors.programId)!)
private let wallet = PublicKey(base58: Vectors.publicKey)!
private let recipient = PublicKey(base58: Vectors.recipient)!
private let newcomer = PublicKey(base58: Vectors.newcomer)!
private let blockhash = Base58.decode(Vectors.blockhash)!

@Suite struct TransactionTests {
    @Test func encodesCompactLengths() {
        #expect(Transaction.compactU16(0).hex == "00")
        #expect(Transaction.compactU16(127).hex == "7f")
        #expect(Transaction.compactU16(128).hex == "8001")
        #expect(Transaction.compactU16(16_384).hex == "808001")
    }

    @Test func payMessageMatchesSolanas() {
        let ix = program.pay(payer: wallet, recipient: recipient, amountMicro: 300_000_000, name: "Camila")
        let message = Transaction.compile(instructions: [ix], feePayer: wallet, recentBlockhash: blockhash)
        #expect(ParsedMessage(message.serialize()) == ParsedMessage(Data(hex: Vectors.payMessageHex)))
    }

    @Test func joinMessageMatchesSolanas() {
        let ix = program.join(inviter: wallet, newcomer: newcomer)
        let message = Transaction.compile(instructions: [ix], feePayer: wallet, recentBlockhash: blockhash)
        #expect(ParsedMessage(message.serialize()) == ParsedMessage(Data(hex: Vectors.joinMessageHex)))
    }

    @Test func reviveSelfMessageMatchesSolanas() {
        let ix = program.reviveSelf(payer: wallet)
        let message = Transaction.compile(instructions: [ix], feePayer: wallet, recentBlockhash: blockhash)
        #expect(ParsedMessage(message.serialize()) == ParsedMessage(Data(hex: Vectors.reviveSelfMessageHex)))
    }

    @Test func feePayerComesFirstAndSignersBeforeTheRest() {
        let ix = program.pay(payer: wallet, recipient: recipient, amountMicro: 1, name: "")
        let message = Transaction.compile(instructions: [ix], feePayer: wallet, recentBlockhash: blockhash)
        #expect(message.accountKeys.first == wallet)
        #expect(message.requiredSignatures == 1)
        #expect(Set(message.accountKeys).count == message.accountKeys.count)
    }

    @Test func signedTransactionCarriesAValidSignatureOverTheMessage() throws {
        let keypair = try #require(Keypair(seed: Data(hex: Vectors.seedHex)))
        let ix = program.pay(payer: wallet, recipient: recipient, amountMicro: 300_000_000, name: "Camila")
        let message = Transaction.compile(instructions: [ix], feePayer: wallet, recentBlockhash: blockhash)
        let wire = try Transaction.sign(message: message, signers: [keypair])
        let body = message.serialize()
        #expect(wire.count == 1 + 64 + body.count)
        #expect(wire.first == 1)
        #expect(wire.suffix(body.count) == body)
        let signature = wire.dropFirst().prefix(64)
        let verifier = try Curve25519.Signing.PublicKey(rawRepresentation: wallet.data)
        #expect(verifier.isValidSignature(signature, for: body))
    }

    @Test func refusesToSignWithoutTheRequiredSigner() {
        let ix = program.pay(payer: wallet, recipient: recipient, amountMicro: 1, name: "")
        let message = Transaction.compile(instructions: [ix], feePayer: wallet, recentBlockhash: blockhash)
        #expect(throws: TransactionError.self) { try Transaction.sign(message: message, signers: [Keypair()]) }
    }
}

@Suite struct ValpixProgramTests {
    @Test func derivesTheProgramsAddresses() {
        #expect(program.config.base58 == Vectors.config.address)
        #expect(program.pool.base58 == Vectors.pool.address)
        #expect(program.vault.base58 == Vectors.vault.address)
        #expect(program.member(of: wallet).base58 == Vectors.member.address)
        #expect(program.member(of: recipient).base58 == Vectors.recipientMember)
    }

    @Test func encodesInstructionDataLikeAnchor() {
        #expect(program.pay(payer: wallet, recipient: recipient, amountMicro: 300_000_000, name: "Camila").data.hex == Vectors.payDataHex)
        #expect(program.join(inviter: wallet, newcomer: newcomer).data.hex == Vectors.joinDataHex)
        #expect(program.reviveSelf(payer: wallet).data.hex == Vectors.reviveSelfDataHex)
    }

    @Test func decodesTheConfigRecord() throws {
        let record = try #require(ConfigRecord(accountData: Data(base64Encoded: Vectors.configData)!))
        #expect(record.admin.base58 == Vectors.configAdmin)
        #expect(record.dailyRate == Vectors.configDailyRate)
        #expect(record.feeRefundLamports == Vectors.configFeeRefund)
        #expect(record.floatLamports == Vectors.configFloat)
        #expect(record.joinSurcharge == Vectors.configJoinSurcharge)
        #expect(record.reviveThresholdLamports == Vectors.configReviveThreshold)
        #expect(record.revivePeriodSecs == Vectors.configRevivePeriod)
        #expect(record.poolFloorLamports == Vectors.configPoolFloor)
        #expect(record.maxFee == Vectors.configMaxFee)
    }

    @Test func decodesThePoolRecord() throws {
        let record = try #require(PoolRecord(accountData: Data(base64Encoded: Vectors.poolData)!))
        #expect(record.clpcReserve == Vectors.poolClpcReserve)
    }

    @Test func decodesAMemberRecord() throws {
        let record = try #require(MemberRecord(accountData: Data(base64Encoded: Vectors.memberData)!))
        #expect(record.wallet.base58 == Vectors.memberWallet)
        #expect(record.settled == Vectors.memberSettled)
        #expect(record.lastSettledTs == Vectors.memberLastSettled)
        #expect(record.createdTs == Vectors.memberCreated)
        #expect(record.inviter.base58 == Vectors.memberInviter)
        #expect(record.lastReviveTs == Vectors.memberLastRevive)
    }

    @Test func rejectsRecordsOfTheWrongKindOrLength() {
        let pool = Data(base64Encoded: Vectors.poolData)!
        let member = Data(base64Encoded: Vectors.memberData)!
        #expect(MemberRecord(accountData: pool) == nil)
        #expect(PoolRecord(accountData: member) == nil)
        #expect(MemberRecord(accountData: member.prefix(40)) == nil)
        #expect(ConfigRecord(accountData: Data()) == nil)
    }

    @Test func decodesAPaidEventFromALogLine() throws {
        guard case .paid(let paid)? = ValpixEvent(logLine: Vectors.paidLogLine) else {
            Issue.record("expected a paid event")
            return
        }
        #expect(paid.payer.base58 == Vectors.paidPayer)
        #expect(paid.recipient.base58 == Vectors.paidRecipient)
        #expect(paid.amount == Vectors.paidAmount)
        #expect(paid.fee == Vectors.paidFee)
        #expect(paid.name == Vectors.paidName)
        #expect(paid.ts == Vectors.paidTs)
    }

    @Test func ignoresLogLinesThatAreNotValpixEvents() {
        #expect(ValpixEvent(logLine: "Program log: Instruction: Pay") == nil)
        #expect(ValpixEvent(logLine: "Program data: AAAA") == nil)
        #expect(ValpixEvent(logLine: "Program data: not base64 !!") == nil)
    }
}
