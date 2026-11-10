package com.franky.valpix.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End to end against a real validator running the real program.
 *
 * Runs only when a local validator answers on port 8899 with valpix set up
 * (see program/README.md). It uses the admin script to play the admin, and
 * everything else goes through the Kotlin client exactly as the app does.
 */
class IntegrationTest {
    private val url = "http://127.0.0.1:8899"
    private val rpc = SolanaRpc(HttpTransport(url, 5_000))
    private val program = ValpixProgram(key(Vectors.programId))
    private val client = ValpixClient(rpc, program, pollIntervalMs = 200)
    private val scripts = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "program/scripts") }.first { it.isDirectory }

    private fun validatorIsRunning() = try {
        "ok" in HttpTransport(url, 1_000).post("""{"jsonrpc":"2.0","id":1,"method":"getHealth"}""")
    } catch (e: IOException) {
        false
    }

    /** Runs the admin script, as the admin would from a terminal. */
    private fun admin(vararg arguments: String) {
        val process = ProcessBuilder(listOf(File(scripts, "node_modules/.bin/tsx").path, "admin.ts") + arguments)
            .directory(scripts).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "admin ${arguments.joinToString(" ")} failed: $output" }
    }

    /** Writes a keypair in the file format Solana's tools read. */
    private fun keyFile(keypair: Keypair): File {
        val file = File.createTempFile("valpix-test-", ".json")
        file.writeText((keypair.seed + keypair.publicKey.bytes).joinToString(",", "[", "]") { (it.toInt() and 0xff).toString() })
        return file
    }

    /** A new member holding about 2,000 CLPC, bought from the pool with test SOL. */
    private fun fundedMember(): Keypair {
        val keypair = Keypair.generate()
        admin("member", keypair.publicKey.base58)
        val airdrop = rpc.requestAirdrop(keypair.publicKey, 1_000_000_000)
        while (rpc.signatureStatus(airdrop)?.isConfirmed != true) Thread.sleep(200)
        val file = keyFile(keypair)
        try {
            admin("fund", "--sol", "0.01", "--keypair", file.path)
        } finally {
            file.delete()
        }
        return keypair
    }

    @Test fun payJoinAndHistoryAgainstTheRealProgram() {
        assumeTrue(validatorIsRunning(), "needs a local validator with valpix set up")
        val x = fundedMember()
        val y = Keypair.generate()
        admin("member", y.publicKey.base58)

        // Reading state.
        val before = client.state(x.publicKey)
        assertEquals(x.publicKey, before.member?.wallet)
        assertTrue(before.balance(System.currentTimeMillis()) > 1_900uL * Money.UNIT)
        val fee = assertNotNull(before.fee)
        assertTrue(fee < 2uL * Money.UNIT)

        // Paying: confirmed, the money arrives, and the pool returns the fee.
        val paid = assertIs<Outcome.Confirmed>(client.pay(x, y.publicKey, 300uL * Money.UNIT, "Prueba"))
        val after = client.state(x.publicKey)
        assertEquals(before.walletLamports, after.walletLamports, "the pool refunded the Solana fee")
        assertTrue(after.member!!.settled <= before.balance(System.currentTimeMillis()) - 300uL * Money.UNIT - fee + Money.UNIT)
        assertTrue(client.state(y.publicKey).balance(System.currentTimeMillis()) >= 300uL * Money.UNIT)

        // History, read back from the chain as the payee sees it.
        val entry = assertNotNull(client.history(y.publicKey).firstOrNull { it.signature == paid.signature })
        assertEquals(HistoryEntry.Kind.RECEIVED, entry.kind)
        assertEquals(x.publicKey, entry.counterparty)
        assertEquals("Prueba", entry.name)
        assertEquals(300uL * Money.UNIT, entry.amountMicro)

        // Joining: a wallet that exists nowhere becomes a member with a float.
        val z = Keypair.generate()
        assertNull(client.state(z.publicKey).member)
        assertNotEquals<Outcome>(Outcome.Failed(FailureReason.NoRoute), client.join(x, z.publicKey))
        val newcomer = client.state(z.publicKey)
        assertEquals(x.publicKey, newcomer.member?.inviter)
        assertEquals(newcomer.config.floatLamports, newcomer.walletLamports)

        // Refusals carry the program's own reason, and nothing is confirmed.
        assertEquals<Outcome>(Outcome.Failed(FailureReason.Rejected("InsufficientFunds")), client.pay(x, y.publicKey, 1_000_000uL * Money.UNIT, ""))
        assertEquals<Outcome>(Outcome.Failed(FailureReason.Rejected("AccountNotInitialized")), client.pay(x, Keypair.generate().publicKey, Money.UNIT, ""))
    }
}
