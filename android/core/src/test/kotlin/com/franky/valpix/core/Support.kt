package com.franky.valpix.core

fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun key(text: String): PublicKey = PublicKey.fromBase58(text) ?: error("not a key: $text")
