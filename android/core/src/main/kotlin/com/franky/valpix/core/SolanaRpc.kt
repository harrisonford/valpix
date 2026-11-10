package com.franky.valpix.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * How a JSON-RPC request reaches a Solana access point. The app uses HTTPS;
 * later a nearby phone over Bluetooth can stand in for it.
 */
fun interface RpcTransport {
    /** Posts one JSON-RPC request body and returns the response body. Throws `IOException` when it cannot. */
    @Throws(IOException::class)
    fun post(body: String): String
}

sealed class RpcException(message: String) : Exception(message) {
    /** The access point could not be reached. */
    class Transport(val detail: String) : RpcException(detail)

    /**
     * The access point answered with an error. `logs` holds the program's
     * log when a transaction was rejected before being sent on.
     */
    class Rpc(val code: Int, val text: String, val logs: List<String>) : RpcException("$code $text")

    class Malformed : RpcException("malformed answer")
}

class HttpTransport(private val url: String, private val timeoutMs: Int = 10_000) : RpcTransport {
    override fun post(body: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }
}

class AccountInfo(val lamports: ULong, val data: ByteArray)

class Blockhash(val hash: ByteArray, val lastValidBlockHeight: ULong)

data class SignatureStatus(
    /** Confirmed or finalized by the cluster. */
    val isConfirmed: Boolean,
    /** A short description of the error the chain recorded, if any. */
    val error: String?,
)

data class SignatureInfo(val signature: String, val blockTime: Long?, val failed: Boolean)

/** The handful of Solana JSON-RPC methods valpix needs. Every call blocks. */
class SolanaRpc(private val transport: RpcTransport) {
    constructor(url: String) : this(HttpTransport(url))

    private fun options(extra: Map<String, JsonElement> = emptyMap()) = buildJsonObject {
        put("commitment", "confirmed")
        for ((name, value) in extra) put(name, value)
    }

    /** Sends one request and returns its `result`. */
    private fun call(method: String, params: JsonArray): JsonElement {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", method)
            put("params", params)
        }.toString()
        val response = try {
            transport.post(body)
        } catch (e: IOException) {
            throw Transport(e)
        }
        val answer = try {
            Json.parseToJsonElement(response) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw RpcException.Malformed()
        (answer["error"] as? JsonObject)?.let { error ->
            val logs = ((error["data"] as? JsonObject)?.get("logs") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content } ?: emptyList()
            throw RpcException.Rpc(
                (error["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                text(error["message"]) ?: "",
                logs,
            )
        }
        return answer["result"] ?: throw RpcException.Malformed()
    }

    private fun Transport(e: IOException) = RpcException.Transport(e.message ?: e.javaClass.simpleName)

    private fun value(result: JsonElement): JsonElement = (result as? JsonObject)?.get("value") ?: throw RpcException.Malformed()

    private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    /** A whole number from 0 to 2^64 − 1, read from its digits so none are lost. */
    private fun unsigned(element: JsonElement?): ULong {
        val primitive = element as? JsonPrimitive
        if (primitive == null || primitive.isString) throw RpcException.Malformed()
        return primitive.content.toULongOrNull() ?: throw RpcException.Malformed()
    }

    private fun present(element: JsonElement?) = element != null && element !is JsonNull

    fun latestBlockhash(): Blockhash {
        val value = value(call("getLatestBlockhash", buildJsonArray { add(options()) })) as? JsonObject
        val hash = text(value?.get("blockhash"))?.let(Base58::decode)
        if (hash == null || hash.size != 32) throw RpcException.Malformed()
        return Blockhash(hash, unsigned(value?.get("lastValidBlockHeight")))
    }

    /** Several accounts in one request. `null` where an address holds nothing. */
    fun accounts(keys: List<PublicKey>): List<AccountInfo?> {
        val params = buildJsonArray {
            add(buildJsonArray { for (key in keys) add(key.base58) })
            add(options(mapOf("encoding" to JsonPrimitive("base64"))))
        }
        val list = value(call("getMultipleAccounts", params)) as? JsonArray
        if (list == null || list.size != keys.size) throw RpcException.Malformed()
        return list.map { item ->
            val account = item as? JsonObject ?: return@map null
            val data = try {
                Base64.getDecoder().decode(text((account["data"] as? JsonArray)?.firstOrNull()) ?: throw RpcException.Malformed())
            } catch (e: IllegalArgumentException) {
                throw RpcException.Malformed()
            }
            AccountInfo(unsigned(account["lamports"]), data)
        }
    }

    fun minimumBalanceForRentExemption(size: Int): ULong =
        unsigned(call("getMinimumBalanceForRentExemption", buildJsonArray { add(size) }))

    /**
     * Submits a signed transaction. The access point simulates it first and
     * throws [RpcException.Rpc] with the program's log when it would fail.
     */
    fun send(wire: ByteArray): String {
        val params = buildJsonArray {
            add(Base64.getEncoder().encodeToString(wire))
            add(options(mapOf("encoding" to JsonPrimitive("base64"), "preflightCommitment" to JsonPrimitive("confirmed"))))
        }
        return text(call("sendTransaction", params)) ?: throw RpcException.Malformed()
    }

    /** `null` while the cluster has not seen the transaction. */
    fun signatureStatus(signature: String): SignatureStatus? {
        val params = buildJsonArray { add(buildJsonArray { add(signature) }) }
        val status = (value(call("getSignatureStatuses", params)) as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val level = text(status["confirmationStatus"])
        val error = status["err"].takeIf(::present)?.let(::describe)
        return SignatureStatus(level == "confirmed" || level == "finalized", error)
    }

    /** Pulls the custom program error number out of a transaction error. */
    private fun describe(error: JsonElement): String {
        val parts = (error as? JsonObject)?.get("InstructionError") as? JsonArray
        if (parts != null && parts.size == 2) {
            ((parts[1] as? JsonObject)?.get("Custom") as? JsonPrimitive)?.takeIf { !it.isString }?.let { return it.content }
        }
        return text(error) ?: error.toString()
    }

    fun blockHeight(): ULong = unsigned(call("getBlockHeight", buildJsonArray { add(options()) }))

    /** The chain's clock, in seconds since 1970. */
    fun chainTime(): Long {
        val slot = unsigned(call("getSlot", buildJsonArray { add(options()) }))
        val time = call("getBlockTime", buildJsonArray { add(JsonPrimitive(slot.toLong())) }) as? JsonPrimitive
        if (time == null || time.isString) throw RpcException.Malformed()
        return time.content.toLongOrNull() ?: throw RpcException.Malformed()
    }

    /** The most recent transactions that touched an address, newest first. */
    fun signatures(address: PublicKey, limit: Int): List<SignatureInfo> {
        val params = buildJsonArray {
            add(address.base58)
            add(options(mapOf("limit" to JsonPrimitive(limit))))
        }
        val list = call("getSignaturesForAddress", params) as? JsonArray ?: throw RpcException.Malformed()
        return list.mapNotNull { item ->
            val entry = item as? JsonObject ?: return@mapNotNull null
            val signature = text(entry["signature"]) ?: return@mapNotNull null
            val blockTime = (entry["blockTime"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
            SignatureInfo(signature, blockTime, present(entry["err"]))
        }
    }

    /** A confirmed transaction's log, or `null` when the access point no longer has it. */
    fun transactionLogs(signature: String): List<String>? {
        val params = buildJsonArray {
            add(signature)
            add(options(mapOf("encoding" to JsonPrimitive("json"), "maxSupportedTransactionVersion" to JsonPrimitive(0))))
        }
        val meta = (call("getTransaction", params) as? JsonObject)?.get("meta") as? JsonObject
        return (meta?.get("logMessages") as? JsonArray)?.mapNotNull(::text)
    }

    /** Test networks only: asks the faucet for SOL. */
    fun requestAirdrop(to: PublicKey, lamports: Long): String {
        val params = buildJsonArray { add(to.base58); add(lamports) }
        return text(call("requestAirdrop", params)) ?: throw RpcException.Malformed()
    }
}
