package com.franky.valpix.core

import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientTest {
    private val fake = FakeTransport()
    private val program = ValpixProgram(key(Vectors.programId))
    private val client = ValpixClient(SolanaRpc(fake), program, pollIntervalMs = 1, timeoutMs = 2_000)
    private val payer = Keypair.fromSeed(hex(Vectors.seedHex))!!
    private val recipient = key(Vectors.recipient)
    private val wallet = key(Vectors.memberWallet)
    private val rentMin = 890_880uL

    private fun account(lamports: ULong, data: String) = """{"lamports":$lamports,"data":["$data","base64"]}"""

    private fun stateHandlers(member: String?, config: String? = account(1_000_000uL, Vectors.configData)) {
        fake.on("getMultipleAccounts") {
            val vault = account(rentMin + 1_000_000_000uL, "")
            """{"value":[${member ?: "null"},${config ?: "null"},${account(1uL, Vectors.poolData)},$vault,${account(1_390_880uL, "")}]}"""
        }
        fake.on("getMinimumBalanceForRentExemption") { rentMin.toString() }
        fake.on("getSlot") { "100" }
        fake.on("getBlockTime") { (Vectors.memberLastSettled + 8_640).toString() }
    }

    @Test fun readsAMembersStateInOneRoundTrip() {
        stateHandlers(account(2_000_000uL, Vectors.memberData))
        val state = client.state(wallet, observedAtMillis = 50_000_000L)
        assertEquals(wallet, state.member?.wallet)
        assertEquals(Vectors.configDailyRate, state.config.dailyRate)
        assertEquals(Vectors.poolClpcReserve, state.pool.clpcReserve)
        assertEquals(1_000_000_000uL, state.solReserve)
        assertEquals(1_390_880uL, state.walletLamports)
        // A tenth of a day after joining with nothing: 100 CLPC, and still rising.
        assertEquals(100uL * Money.UNIT, state.balance(atMillis = 50_000_000L))
        assertEquals(200uL * Money.UNIT, state.balance(atMillis = 50_000_000L + 8_640_000L))
        assertNotNull(state.fee)
        assertEquals(1, fake.count("getMultipleAccounts"))
        val asked = fake.calls.first { it.first == "getMultipleAccounts" }.second[0]
        assertEquals(
            listOf(program.member(wallet), program.config, program.pool, program.vault, wallet).map { it.base58 },
            (asked as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content },
        )
    }

    @Test fun aWalletWithoutAnAccountHasNoMemberAndNoBalance() {
        stateHandlers(null)
        val state = client.state(wallet)
        assertNull(state.member)
        assertEquals(0uL, state.balance(System.currentTimeMillis()))
    }

    @Test fun aVaultBelowItsRentHoldsNoSol() {
        stateHandlers(null)
        fake.on("getMinimumBalanceForRentExemption") { "999999999999" }
        assertEquals(0uL, client.state(wallet).solReserve)
    }

    @Test fun stateThrowsWhenTheProgramIsNotSetUp() {
        stateHandlers(null, config = null)
        assertFailsWith<ProgramNotFoundException> { client.state(wallet) }
        stateHandlers(null, config = account(1uL, Vectors.poolData))
        assertFailsWith<ProgramNotFoundException> { client.state(wallet) }
    }

    /** Handlers for a payment that the chain confirms on the second look. */
    private fun sendHandlers(): () -> ByteArray? {
        var wire: ByteArray? = null
        var looks = 0
        fake.on("getLatestBlockhash") { """{"value":{"blockhash":"${Vectors.blockhash}","lastValidBlockHeight":1000}}""" }
        fake.on("sendTransaction") { params ->
            wire = Base64.getDecoder().decode(params[0].jsonPrimitive.content)
            q("whatever-the-access-point-says")
        }
        fake.on("getSignatureStatuses") {
            if (++looks < 2) """{"value":[null]}""" else """{"value":[{"confirmationStatus":"confirmed","err":null}]}"""
        }
        fake.on("getBlockHeight") { "500" }
        return { wire }
    }

    @Test fun paySendsASignedPaymentAndWaitsForConfirmation() {
        val sent = sendHandlers()
        val outcome = client.pay(payer, recipient, 300_000_000uL, "Camila")
        val wire = assertNotNull(sent())
        // The outcome carries the transaction's own signature, not the access point's word.
        assertEquals(Outcome.Confirmed(Base58.encode(wire.copyOfRange(1, 65))), outcome)
        val message = ParsedMessage.parse(wire.copyOfRange(65, wire.size))
        assertEquals(ParsedMessage.parse(hex(Vectors.payMessageHex)), message)
        assertEquals(q(Base58.encode(wire.copyOfRange(1, 65))), fake.calls.last { it.first == "getSignatureStatuses" }.second[0].let { (it as kotlinx.serialization.json.JsonArray)[0].toString() })
    }

    @Test fun pay_reports_failure_when_rpc_is_down() {
        val dead = ValpixClient(SolanaRpc(DeadTransport), program, pollIntervalMs = 1, timeoutMs = 50)
        assertEquals(Outcome.Failed(FailureReason.NoRoute), dead.pay(payer, recipient, 1uL, ""))
    }

    @Test fun payReportsTheProgramsOwnReasonWhenRejected() {
        sendHandlers()
        fake.on("sendTransaction") {
            throw FakeRpcFailure(-32002, "Transaction simulation failed", listOf(
                "Program log: Instruction: Pay",
                "Program log: AnchorError occurred. Error Code: InsufficientFunds. Error Number: 6006. Error Message: Not enough.",
            ))
        }
        assertEquals(Outcome.Failed(FailureReason.Rejected("InsufficientFunds")), client.pay(payer, recipient, 1uL, ""))
        assertEquals(0, fake.count("getSignatureStatuses"))
        fake.on("sendTransaction") { throw FakeRpcFailure(-32003, "Blockhash not found") }
        assertEquals(Outcome.Failed(FailureReason.Rejected("Blockhash not found")), client.pay(payer, recipient, 1uL, ""))
    }

    @Test fun payFailsWhenTheChainRecordsAnError() {
        sendHandlers()
        fake.on("getSignatureStatuses") { """{"value":[{"confirmationStatus":"confirmed","err":{"InstructionError":[0,{"Custom":6006}]}}]}""" }
        assertEquals(Outcome.Failed(FailureReason.Rejected("6006")), client.pay(payer, recipient, 1uL, ""))
    }

    @Test fun payExpiresWhenTheTransactionNeverLands() {
        sendHandlers()
        fake.on("getSignatureStatuses") { """{"value":[null]}""" }
        fake.on("getBlockHeight") { "1001" }
        assertEquals(Outcome.Failed(FailureReason.Expired), client.pay(payer, recipient, 1uL, ""))
    }

    @Test fun aTransactionStillInsideItsWindowIsNotExpired() {
        sendHandlers()
        var looks = 0
        fake.on("getSignatureStatuses") { if (++looks < 5) """{"value":[null]}""" else """{"value":[{"confirmationStatus":"finalized","err":null}]}""" }
        fake.on("getBlockHeight") { "1000" }
        assertIs<Outcome.Confirmed>(client.pay(payer, recipient, 1uL, ""))
    }

    @Test fun aBriefLossOfRouteAfterSendingIsNotAFailure() {
        sendHandlers()
        var looks = 0
        fake.on("getSignatureStatuses") {
            if (++looks <= 3) throw IOException("tunnel") else """{"value":[{"confirmationStatus":"confirmed","err":null}]}"""
        }
        assertIs<Outcome.Confirmed>(client.pay(payer, recipient, 1uL, ""))
    }

    @Test fun aSendThatTimedOutButLandedIsFoundByItsSignature() {
        sendHandlers()
        fake.on("sendTransaction") { throw IOException("timed out") }
        assertIs<Outcome.Confirmed>(client.pay(payer, recipient, 1uL, ""))
    }

    @Test fun aPaymentThatLandsInTheLastBlocksIsNotCalledExpired() {
        sendHandlers()
        var looks = 0
        fake.on("getSignatureStatuses") { if (++looks < 2) """{"value":[null]}""" else """{"value":[{"confirmationStatus":"confirmed","err":null}]}""" }
        fake.on("getBlockHeight") { "5000" }
        assertIs<Outcome.Confirmed>(client.pay(payer, recipient, 1uL, ""))
    }

    @Test fun whenItCannotFindOutItSaysSoInsteadOfClaimingFailure() {
        sendHandlers()
        fake.on("getSignatureStatuses") { throw IOException("down") }
        fake.on("getBlockHeight") { throw IOException("down") }
        val quick = ValpixClient(SolanaRpc(fake), program, pollIntervalMs = 1, timeoutMs = 40)
        assertEquals(Outcome.Failed(FailureReason.Unconfirmed), quick.pay(payer, recipient, 1uL, ""))
    }

    @Test fun joinAndReviveSendTheirInstructions() {
        val sent = sendHandlers()
        assertIs<Outcome.Confirmed>(client.join(payer, key(Vectors.newcomer)))
        assertEquals(ParsedMessage.parse(hex(Vectors.joinMessageHex)), ParsedMessage.parse(sent()!!.let { it.copyOfRange(65, it.size) }))
        assertIs<Outcome.Confirmed>(client.reviveSelf(payer))
        assertEquals(ParsedMessage.parse(hex(Vectors.reviveSelfMessageHex)), ParsedMessage.parse(sent()!!.let { it.copyOfRange(65, it.size) }))
    }

    private fun joinedLine(inviter: PublicKey, newcomer: PublicKey, cost: ULong, ts: Long): String {
        val w = BorshWriter()
        w.bytes(ValpixProgram.discriminator("event", "Joined"))
        w.publicKey(inviter); w.publicKey(newcomer); w.u64(cost); w.i64(ts)
        return "Program data: " + Base64.getEncoder().encodeToString(w.toByteArray())
    }

    @Test fun historyTurnsEventsIntoEntriesSeenFromOneWallet() {
        val pid = Vectors.programId
        val me = key(Vectors.paidRecipient)
        val friend = key(Vectors.paidPayer)
        val child = key(Vectors.newcomer)
        fun logs(vararg lines: String) = (listOf("Program $pid invoke [1]") + lines + "Program $pid success").joinToString(",", "[", "]") { q(it) }
        fake.on("getSignaturesForAddress") {
            """[{"signature":"s4","blockTime":4,"err":null},{"signature":"bad","blockTime":3,"err":{"x":1}},{"signature":"gone","blockTime":3,"err":null},{"signature":"s2","blockTime":2,"err":null},{"signature":"s1","blockTime":1,"err":null}]"""
        }
        fake.on("getTransaction") { params ->
            when (params[0].jsonPrimitive.content) {
                "s4" -> """{"meta":{"logMessages":${logs(joinedLine(me, child, 7_500_000uL, 40))}}}"""
                "s2" -> """{"meta":{"logMessages":${logs(Vectors.paidLogLine)}}}"""
                "s1" -> """{"meta":{"logMessages":${logs(joinedLine(friend, me, 1uL, 10))}}}"""
                else -> "null"
            }
        }
        val mine = client.history(me)
        assertEquals(
            listOf(
                HistoryEntry("s4", 40, HistoryEntry.Kind.INVITED, child, "", 7_500_000uL),
                HistoryEntry("s2", Vectors.paidTs, HistoryEntry.Kind.RECEIVED, friend, "Camila", Vectors.paidAmount),
                HistoryEntry("s1", 10, HistoryEntry.Kind.JOINED, friend, "", 0uL),
            ),
            mine,
        )
        assertEquals(0, fake.calls.count { it.first == "getTransaction" && it.second[0].jsonPrimitive.content == "bad" })
        assertEquals(program.member(me).base58, fake.calls.first { it.first == "getSignaturesForAddress" }.second[0].jsonPrimitive.content)
        // The same payment seen from the other side.
        val theirs = client.history(friend).first { it.signature == "s2" }
        assertEquals(HistoryEntry("s2", Vectors.paidTs, HistoryEntry.Kind.SENT, me, "", Vectors.paidAmount), theirs)
    }

    @Test fun watchingForAPaymentDoesNotReadTheSameTransactionTwice() {
        // A seller's phone asks again every second or two while a code is on
        // screen. Public access points allow few transaction lookups, so each
        // transaction is read once and remembered.
        val pid = Vectors.programId
        val me = key(Vectors.paidRecipient)
        var signatures = """[{"signature":"old2","blockTime":2,"err":null},{"signature":"old1","blockTime":1,"err":null}]"""
        fake.on("getSignaturesForAddress") { signatures }
        fake.on("getTransaction") { params ->
            when (params[0].jsonPrimitive.content) {
                "new" -> """{"meta":{"logMessages":[${q("Program $pid invoke [1]")},${q(Vectors.paidLogLine)},${q("Program $pid success")}]}}"""
                "old1" -> "null" // not available yet: must be asked for again
                else -> """{"meta":{"logMessages":[${q("Program $pid invoke [1]")},${q("Program $pid success")}]}}"""
            }
        }
        repeat(4) { assertEquals(emptyList(), client.history(me, 5)) }
        assertEquals(1, fake.calls.count { it.first == "getTransaction" && it.second[0].jsonPrimitive.content == "old2" })
        assertEquals(4, fake.calls.count { it.first == "getTransaction" && it.second[0].jsonPrimitive.content == "old1" })

        signatures = """[{"signature":"new","blockTime":3,"err":null},{"signature":"old2","blockTime":2,"err":null},{"signature":"old1","blockTime":1,"err":null}]"""
        repeat(3) { assertEquals(listOf("new"), client.history(me, 5).map { it.signature }) }
        assertEquals(1, fake.calls.count { it.first == "getTransaction" && it.second[0].jsonPrimitive.content == "new" })
        assertEquals(1, fake.calls.count { it.first == "getTransaction" && it.second[0].jsonPrimitive.content == "old2" })
        // The same transaction, seen from the payer's side, is still read from memory.
        assertEquals(HistoryEntry.Kind.SENT, client.history(key(Vectors.paidPayer), 5).single().kind)
        assertEquals(1, fake.calls.count { it.first == "getTransaction" && it.second[0].jsonPrimitive.content == "new" })
    }

    private fun sampleState(member: Boolean = true): WalletState {
        stateHandlers(if (member) account(2_000_000uL, Vectors.memberData) else null)
        return client.state(wallet, observedAtMillis = 50_000_000L)
    }

    @Test fun survivesBeingSavedAndRestored() {
        for (state in listOf(sampleState(), sampleState(member = false))) {
            assertEquals(state, WalletState.fromSnapshot(state.snapshot()))
        }
    }

    @Test fun aRestoredStateKeepsTickingWithoutTheChain() {
        val restored = assertNotNull(WalletState.fromSnapshot(sampleState().snapshot()))
        val now = restored.balance(atMillis = 50_000_000L)
        assertTrue(restored.balance(atMillis = 50_000_000L + 60_000L) > now)
    }

    @Test fun rejectsGarbage() {
        val good = sampleState().snapshot()
        assertNull(WalletState.fromSnapshot(byteArrayOf()))
        assertNull(WalletState.fromSnapshot(byteArrayOf(9, 9, 9)))
        assertNull(WalletState.fromSnapshot(good.copyOf(good.size - 1)))
        assertNull(WalletState.fromSnapshot(good + 0))
        assertNull(WalletState.fromSnapshot(good.copyOf().also { it[0] = 99 }))
    }
}
