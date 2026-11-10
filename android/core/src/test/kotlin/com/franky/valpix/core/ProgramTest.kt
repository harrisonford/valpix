package com.franky.valpix.core

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgramTest {
    private val program = ValpixProgram(key(Vectors.programId))
    private val payer = key(Vectors.publicKey)
    private val recipient = key(Vectors.recipient)
    private val newcomer = key(Vectors.newcomer)
    private val blockhash = Base58.decode(Vectors.blockhash)!!
    private fun b64(text: String) = Base64.getDecoder().decode(text)
    private fun message(ix: Instruction) = Transaction.compile(listOf(ix), payer, blockhash)

    @Test fun encodesCompactLengths() {
        val cases = mapOf(0 to "00", 5 to "05", 127 to "7f", 128 to "8001", 16383 to "ff7f", 16384 to "808001")
        for ((value, hex) in cases) assertEquals(hex, Transaction.compactU16(value).toHex(), "$value")
    }

    @Test fun payMessageMatchesSolanas() {
        val ix = program.pay(payer, recipient, 300_000_000uL, "Camila")
        assertEquals(Vectors.payDataHex, ix.data.toHex())
        assertEquals(ParsedMessage.parse(hex(Vectors.payMessageHex)), ParsedMessage.parse(message(ix).serialize()))
    }

    @Test fun joinMessageMatchesSolanas() {
        val ix = program.join(payer, newcomer)
        assertEquals(Vectors.joinDataHex, ix.data.toHex())
        assertEquals(ParsedMessage.parse(hex(Vectors.joinMessageHex)), ParsedMessage.parse(message(ix).serialize()))
    }

    @Test fun reviveSelfMessageMatchesSolanas() {
        val ix = program.reviveSelf(payer)
        assertEquals(Vectors.reviveSelfDataHex, ix.data.toHex())
        assertEquals(ParsedMessage.parse(hex(Vectors.reviveSelfMessageHex)), ParsedMessage.parse(message(ix).serialize()))
    }

    @Test fun feePayerComesFirstAndSignersBeforeTheRest() {
        val other = key(Vectors.config.first)
        val ix = Instruction(
            program.programId,
            listOf(AccountMeta(other), AccountMeta(recipient, isSigner = true), AccountMeta(newcomer, isWritable = true), AccountMeta(payer, isSigner = true, isWritable = true)),
            byteArrayOf(),
        )
        val compiled = message(ix)
        assertEquals(listOf(payer, recipient, newcomer), compiled.accountKeys.take(3))
        assertEquals(2, compiled.requiredSignatures)
        assertEquals(setOf(other, program.programId), compiled.accountKeys.drop(3).toSet())
    }

    @Test fun signedTransactionCarriesAValidSignatureOverTheMessage() {
        val pair = Keypair.fromSeed(hex(Vectors.seedHex))!!
        val compiled = message(program.pay(payer, recipient, 1uL, ""))
        val wire = Transaction.sign(compiled, listOf(pair))
        val body = compiled.serialize()
        assertEquals(1, wire[0].toInt())
        assertContentEquals(body, wire.copyOfRange(65, wire.size))
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(payer.bytes, 0))
        verifier.update(body, 0, body.size)
        assertTrue(verifier.verifySignature(wire.copyOfRange(1, 65)))
    }

    @Test fun refusesToSignWithoutTheRequiredSigner() {
        val compiled = message(program.pay(payer, recipient, 1uL, ""))
        assertFailsWith<MissingSignerException> { Transaction.sign(compiled, listOf(Keypair.generate())) }
    }

    @Test fun derivesTheProgramsAddresses() {
        assertEquals(Vectors.config.first, program.config.base58)
        assertEquals(Vectors.pool.first, program.pool.base58)
        assertEquals(Vectors.vault.first, program.vault.base58)
        assertEquals(Vectors.member.first, program.member(payer).base58)
        assertEquals(Vectors.recipientMember, program.member(recipient).base58)
        assertEquals(Vectors.newcomerMember, program.member(newcomer).base58)
    }

    @Test fun decodesTheConfigRecord() {
        val config = assertNotNull(ConfigRecord.decode(b64(Vectors.configData)))
        assertEquals(Vectors.configAdmin, config.admin.base58)
        assertEquals(Vectors.configDailyRate, config.dailyRate)
        assertEquals(Vectors.configFeeRefund, config.feeRefundLamports)
        assertEquals(Vectors.configFloat, config.floatLamports)
        assertEquals(Vectors.configJoinSurcharge, config.joinSurcharge)
        assertEquals(Vectors.configReviveThreshold, config.reviveThresholdLamports)
        assertEquals(Vectors.configRevivePeriod, config.revivePeriodSecs)
        assertEquals(Vectors.configPoolFloor, config.poolFloorLamports)
        assertEquals(Vectors.configMaxFee, config.maxFee)
    }

    @Test fun decodesThePoolRecord() {
        assertEquals(Vectors.poolClpcReserve, PoolRecord.decode(b64(Vectors.poolData))?.clpcReserve)
    }

    @Test fun decodesAMemberRecord() {
        val member = assertNotNull(MemberRecord.decode(b64(Vectors.memberData)))
        assertEquals(Vectors.memberWallet, member.wallet.base58)
        assertEquals(Vectors.memberSettled, member.settled)
        assertEquals(Vectors.memberLastSettled, member.lastSettledTs)
        assertEquals(Vectors.memberCreated, member.createdTs)
        assertEquals(Vectors.memberInviter, member.inviter.base58)
        assertEquals(Vectors.memberLastRevive, member.lastReviveTs)
    }

    @Test fun rejectsRecordsOfTheWrongKindOrLength() {
        assertNull(ConfigRecord.decode(b64(Vectors.poolData)))
        assertNull(MemberRecord.decode(b64(Vectors.configData)))
        assertNull(MemberRecord.decode(b64(Vectors.memberData).copyOf(40)))
        assertNull(PoolRecord.decode(byteArrayOf()))
    }

    @Test fun decodesAPaidEventFromALogLine() {
        val event = ValpixEvent.fromLogLine(Vectors.paidLogLine)
        val paid = assertNotNull(event as? ValpixEvent.Paid)
        assertEquals(Vectors.paidPayer, paid.payer.base58)
        assertEquals(Vectors.paidRecipient, paid.recipient.base58)
        assertEquals(Vectors.paidAmount, paid.amount)
        assertEquals(Vectors.paidFee, paid.fee)
        assertEquals(Vectors.paidName, paid.name)
        assertEquals(Vectors.paidTs, paid.ts)
    }

    @Test fun ignoresLogLinesThatAreNotValpixEvents() {
        for (line in listOf("Program log: Instruction: Pay", "Program data: ", "Program data: !!!", "Program data: AAAAAAAAAAA=", Vectors.paidLogLine.dropLast(12))) {
            assertNull(ValpixEvent.fromLogLine(line), line)
        }
    }
}
