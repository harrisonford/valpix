package com.franky.valpix.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * What a valpix QR code says. Everything a camera reads goes through
 * [parse], which accepts only these exact shapes.
 */
sealed class ValpixLink {
    /** "Pay me." Without an amount, the payer types one. */
    data class Pay(val to: PublicKey, val amountPesos: ULong?, val name: String) : ValpixLink()

    /** "Bring me in." Shown by a phone that has no account yet. */
    data class Join(val key: PublicKey, val name: String) : ValpixLink()

    /** The wallet itself, for moving to another phone. Secret. */
    class Wallet(seed: ByteArray) : ValpixLink() {
        private val raw = seed.copyOf()
        val seed: ByteArray get() = raw.copyOf()
        override fun equals(other: Any?) = other is Wallet && raw.contentEquals(other.raw)
        override fun hashCode() = raw.contentHashCode()
        override fun toString() = "Wallet(secret)"
    }

    /** The wallet sealed with a password. */
    class Backup(blob: ByteArray) : ValpixLink() {
        private val raw = blob.copyOf()
        val blob: ByteArray get() = raw.copyOf()
        override fun equals(other: Any?) = other is Backup && raw.contentEquals(other.raw)
        override fun hashCode() = raw.contentHashCode()
        override fun toString() = "Backup(${raw.size} bytes)"
    }

    val string: String
        get() = when (this) {
            is Pay -> "valpix:pay?to=${to.base58}" +
                (amountPesos?.let { "&amount=$it" } ?: "") +
                (if (name.isEmpty()) "" else "&name=${encode(name)}")
            is Join -> "valpix:join?key=${key.base58}" + (if (name.isEmpty()) "" else "&name=${encode(name)}")
            is Wallet -> "valpix:wallet?seed=${Base58.encode(seed)}"
            is Backup -> "valpix:backup?d=" + Base64.getUrlEncoder().withoutPadding().encodeToString(blob)
        }

    companion object {
        /** The largest amount a link may ask for, in pesos. */
        const val MAX_AMOUNT_PESOS: ULong = 1_000_000_000uL
        const val MAX_NAME_BYTES = 32

        /** Longer than any real valpix link; anything bigger is not parsed at all. */
        const val MAX_LENGTH = 1_024

        fun parse(input: String): ValpixLink? {
            if (input.length > MAX_LENGTH || input.toByteArray().size > MAX_LENGTH) return null
            val text = input.trim()
            val colon = text.indexOf(':')
            if (colon < 0 || !text.substring(0, colon).equals("valpix", ignoreCase = true)) return null
            // A "#fragment" is not part of a link.
            var rest = text.substring(colon + 1).substringBefore('#')
            val queryText = if ('?' in rest) rest.substringAfter('?') else ""
            rest = rest.substringBefore('?')
            // "valpix:pay?..." puts the action in the path, "valpix://pay?..." in the host.
            val action = if (rest.startsWith("//")) {
                val authority = rest.substring(2).substringBefore('/')
                if (authority.isNotEmpty()) authority else rest.substring(2 + authority.length)
            } else {
                rest
            }.lowercase()

            val query = HashMap<String, String>()
            if (queryText.isNotEmpty()) {
                for (pair in queryText.split('&')) {
                    val equals = pair.indexOf('=')
                    if (equals < 0) return null
                    val name = decode(pair.substring(0, equals)) ?: return null
                    val value = decode(pair.substring(equals + 1)) ?: return null
                    if (query.put(name, value) != null) return null
                }
            }

            fun name(): String? {
                val value = query["name"] ?: ""
                if (value.toByteArray().size > MAX_NAME_BYTES) return null
                // No control or invisible formatting characters in a name.
                val hidden = value.codePoints().anyMatch {
                    val type = Character.getType(it)
                    type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
                }
                return if (hidden) null else value
            }

            return when (action) {
                "pay" -> {
                    val to = query["to"]?.let(PublicKey::fromBase58) ?: return null
                    val name = name() ?: return null
                    var amount: ULong? = null
                    val raw = query["amount"]
                    if (raw != null) {
                        if (raw.isEmpty() || raw.length > 20 || !raw.all { it in '0'..'9' }) return null
                        val value = raw.toULongOrNull() ?: return null
                        if (value == 0uL || value > MAX_AMOUNT_PESOS) return null
                        amount = value
                    }
                    Pay(to, amount, name)
                }
                "join" -> {
                    val key = query["key"]?.let(PublicKey::fromBase58) ?: return null
                    Join(key, name() ?: return null)
                }
                "wallet" -> {
                    val text58 = query["seed"] ?: return null
                    if (text58.length > 44) return null
                    val seed = Base58.decode(text58) ?: return null
                    if (seed.size == 32) Wallet(seed) else null
                }
                "backup" -> {
                    val encoded = query["d"] ?: return null
                    if (encoded.isEmpty() || !encoded.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) return null
                    val blob = try {
                        Base64.getUrlDecoder().decode(encoded)
                    } catch (e: IllegalArgumentException) {
                        return null
                    }
                    if (blob.isEmpty()) null else Backup(blob)
                }
                else -> null
            }
        }

        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        /** Percent-encodes everything but letters, digits and `-._~`. */
        private fun encode(value: String): String {
            val out = StringBuilder()
            for (byte in value.toByteArray()) {
                val char = (byte.toInt() and 0xff).toChar()
                if (char in UNRESERVED) out.append(char) else out.append('%').append("%02X".format(byte.toInt() and 0xff))
            }
            return out.toString()
        }

        /** Undoes percent-encoding. `null` for a broken escape or bytes that are not UTF-8. */
        private fun decode(value: String): String? {
            val bytes = ByteArrayOutputStream()
            var i = 0
            while (i < value.length) {
                if (value[i] == '%') {
                    if (i + 3 > value.length) return null
                    val high = Character.digit(value[i + 1], 16)
                    val low = Character.digit(value[i + 2], 16)
                    if (high < 0 || low < 0 || value[i + 1].code > 127 || value[i + 2].code > 127) return null
                    bytes.write(high * 16 + low)
                    i += 3
                } else {
                    val end = value.indexOf('%', i).let { if (it < 0) value.length else it }
                    bytes.write(value.substring(i, end).toByteArray())
                    i = end
                }
            }
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            } catch (e: CharacterCodingException) {
                null
            }
        }
    }
}
