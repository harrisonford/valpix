package com.franky.valpix.core

/**
 * Test-only: reads a legacy Solana message back into a form that does not
 * depend on the order accounts were listed in, so our output can be compared
 * with what Solana's own library produced.
 */
data class ParsedMessage(val feePayer: String, val signatureCount: Int, val blockhash: String, val instructions: List<Ix>) {
    data class Account(val key: String, val isSigner: Boolean, val isWritable: Boolean)
    data class Ix(val program: String, val accounts: List<Account>, val dataHex: String)

    companion object {
        fun parse(data: ByteArray): ParsedMessage {
            var at = 0
            fun byte() = data[at++].toInt() and 0xff
            fun take(count: Int) = data.copyOfRange(at, at + count).also { at += count }
            fun compact(): Int {
                var value = 0
                var shift = 0
                while (true) {
                    val b = byte()
                    value = value or ((b and 0x7f) shl shift)
                    if (b and 0x80 == 0) return value
                    shift += 7
                }
            }
            val signatures = byte()
            val readonlySigned = byte()
            val readonlyUnsigned = byte()
            val keyCount = compact()
            val keys = List(keyCount) { Base58.encode(take(32)) }
            val blockhash = Base58.encode(take(32))
            fun account(index: Int): Account {
                val signer = index < signatures
                val writable = if (signer) index < signatures - readonlySigned else index < keyCount - readonlyUnsigned
                return Account(keys[index], signer, writable)
            }
            val instructions = List(compact()) {
                val program = keys[byte()]
                val accounts = List(compact()) { account(byte()) }
                Ix(program, accounts, take(compact()).toHex())
            }
            check(at == data.size) { "trailing bytes in message" }
            return ParsedMessage(keys[0], signatures, blockhash, instructions)
        }
    }
}
