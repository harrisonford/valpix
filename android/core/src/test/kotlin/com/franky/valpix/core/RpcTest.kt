package com.franky.valpix.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RpcTest {
    private val fake = FakeTransport()
    private val rpc = SolanaRpc(fake)

    @Test fun readsNumbersAboveTheSignedRange() {
        fake.on("getMultipleAccounts") { """{"value":[{"lamports":18446744073709551615,"data":["AQI=","base64"]},null]}""" }
        fake.on("getLatestBlockhash") { """{"value":{"blockhash":"${Vectors.blockhash}","lastValidBlockHeight":9223372036854775808}}""" }
        fake.on("getBlockHeight") { "18446744073709551615" }
        val accounts = rpc.accounts(listOf(key(Vectors.publicKey), key(Vectors.recipient)))
        assertEquals(ULong.MAX_VALUE, accounts[0]?.lamports)
        assertContentEquals(byteArrayOf(1, 2), accounts[0]?.data)
        assertNull(accounts[1])
        assertEquals(9_223_372_036_854_775_808uL, rpc.latestBlockhash().lastValidBlockHeight)
        assertEquals(ULong.MAX_VALUE, rpc.blockHeight())
    }

    @Test fun refusesNumbersThatAreNotWholeAndUnsigned() {
        for (bad in listOf("-1", "1.5", "\"7\"", "null", "{}", "18446744073709551616")) {
            fake.on("getBlockHeight") { bad }
            assertFailsWith<RpcException.Malformed>(bad) { rpc.blockHeight() }
        }
    }

    @Test fun turnsAnRpcErrorIntoItsCodeMessageAndLogs() {
        fake.on("sendTransaction") { throw FakeRpcFailure(-32002, "Transaction simulation failed", listOf("Program log: \"hi\"", "line 2")) }
        val error = assertFailsWith<RpcException.Rpc> { rpc.send(byteArrayOf(1, 2, 3)) }
        assertEquals(-32002, error.code)
        assertEquals("Transaction simulation failed", error.text)
        assertEquals(listOf("Program log: \"hi\"", "line 2"), error.logs)
    }

    @Test fun anUnreachableAccessPointIsATransportError() {
        assertFailsWith<RpcException.Transport> { SolanaRpc(DeadTransport).blockHeight() }
    }

    @Test fun anAnswerThatIsNotJsonRpcIsMalformed() {
        for (body in listOf("", "<html>", "[]", "{}", """{"result":null}""")) {
            val rpc = SolanaRpc { body }
            assertFailsWith<RpcException.Malformed>(body) { rpc.blockHeight() }
        }
    }

    @Test fun readsSignatureStatuses() {
        fake.on("getSignatureStatuses") { """{"value":[null]}""" }
        assertNull(rpc.signatureStatus("sig"))
        fake.on("getSignatureStatuses") { """{"value":[{"confirmationStatus":"processed","err":null}]}""" }
        assertEquals(SignatureStatus(false, null), rpc.signatureStatus("sig"))
        fake.on("getSignatureStatuses") { """{"value":[{"confirmationStatus":"finalized","err":null}]}""" }
        assertEquals(SignatureStatus(true, null), rpc.signatureStatus("sig"))
        fake.on("getSignatureStatuses") { """{"value":[{"confirmationStatus":"confirmed","err":{"InstructionError":[0,{"Custom":6006}]}}]}""" }
        assertEquals(SignatureStatus(true, "6006"), rpc.signatureStatus("sig"))
        fake.on("getSignatureStatuses") { """{"value":[{"confirmationStatus":"confirmed","err":"BlockhashNotFound"}]}""" }
        assertEquals("BlockhashNotFound", rpc.signatureStatus("sig")?.error)
    }

    @Test fun readsChainTimeSignaturesAndLogs() {
        fake.on("getSlot") { "321" }
        fake.on("getBlockTime") { params -> if (params[0].toString() == "321") "1794318200" else "0" }
        assertEquals(1_794_318_200L, rpc.chainTime())
        fake.on("getSignaturesForAddress") { """[{"signature":"a","blockTime":5,"err":null},{"signature":"b","blockTime":null,"err":{"x":1}},{"nope":1}]""" }
        assertEquals(
            listOf(SignatureInfo("a", 5, false), SignatureInfo("b", null, true)),
            rpc.signatures(key(Vectors.publicKey), 10),
        )
        fake.on("getTransaction") { """{"meta":{"logMessages":["one","two"]}}""" }
        assertEquals(listOf("one", "two"), rpc.transactionLogs("a"))
        fake.on("getTransaction") { "null" }
        assertNull(rpc.transactionLogs("a"))
    }

    @Test fun httpTransportPostsJsonAndReadsTheAnswer() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var seen = ""
        server.createContext("/ok") { exchange ->
            seen = exchange.requestHeaders.getFirst("Content-Type") + "|" + exchange.requestBody.readBytes().decodeToString()
            val answer = """{"jsonrpc":"2.0","id":1,"result":77}""".toByteArray()
            exchange.sendResponseHeaders(200, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }
        server.createContext("/busy") { exchange ->
            exchange.sendResponseHeaders(429, -1)
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals(77uL, SolanaRpc(HttpTransport("$base/ok")).blockHeight())
            assertTrue(seen.startsWith("application/json|{"), seen)
            assertTrue("\"getBlockHeight\"" in seen)
            assertFailsWith<RpcException.Transport> { SolanaRpc(HttpTransport("$base/busy")).blockHeight() }
        } finally {
            server.stop(0)
        }
        // Nothing is listening there any more.
        assertFailsWith<RpcException.Transport> { SolanaRpc(HttpTransport("http://127.0.0.1:${server.address.port}/ok", 500)).blockHeight() }
    }
}
