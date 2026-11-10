package com.franky.valpix.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/**
 * Test-only access point: answers each JSON-RPC method from a table, and
 * records what it was asked. A handler returns the `result` as JSON text.
 */
class FakeTransport : RpcTransport {
    private val handlers = HashMap<String, (JsonArray) -> String>()
    val calls = ArrayList<Pair<String, JsonArray>>()

    fun on(method: String, handler: (JsonArray) -> String) { handlers[method] = handler }
    fun count(method: String) = calls.count { it.first == method }

    override fun post(body: String): String {
        val request = Json.parseToJsonElement(body).jsonObject
        val method = request.getValue("method").jsonPrimitive.content
        val params = request["params"]?.jsonArray ?: JsonArray(emptyList())
        calls.add(method to params)
        val handler = handlers[method] ?: throw IOException("no handler for $method")
        return try {
            """{"jsonrpc":"2.0","id":1,"result":${handler(params)}}"""
        } catch (e: FakeRpcFailure) {
            val logs = e.logs.joinToString(",") { JsonPrimitive(it).toString() }
            """{"jsonrpc":"2.0","id":1,"error":{"code":${e.code},"message":"${e.text}","data":{"logs":[$logs]}}}"""
        }
    }
}

class FakeRpcFailure(val code: Int, val text: String, val logs: List<String> = emptyList()) : Exception(text)

/** An access point that cannot be reached at all. */
object DeadTransport : RpcTransport {
    override fun post(body: String): String = throw IOException("not connected")
}

/** A JSON string literal. */
fun q(text: String) = "\"$text\""
