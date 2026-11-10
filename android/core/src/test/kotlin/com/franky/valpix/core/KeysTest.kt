package com.franky.valpix.core

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeysTest {
    @Test fun encodesKnownPairs() {
        for ((hex, base58) in Vectors.base58Pairs) assertEquals(base58, Base58.encode(hex(hex)), hex)
    }

    @Test fun decodesKnownPairs() {
        for ((hex, base58) in Vectors.base58Pairs) assertEquals(hex, Base58.decode(base58)?.toHex(), base58)
    }

    @Test fun rejectsCharactersOutsideTheAlphabet() {
        for (bad in listOf("0", "O", "I", "l", "abc def", "ñ", "9C6h+")) assertNull(Base58.decode(bad), bad)
    }

    @Test fun roundTripsBase58() {
        val bytes = ByteArray(40) { (it * 37 + 5).toByte() }
        assertContentEquals(bytes, Base58.decode(Base58.encode(bytes)))
    }

    @Test fun rejectsWrongLengths() {
        assertNull(PublicKey.of(ByteArray(31)))
        assertNull(PublicKey.of(ByteArray(33)))
        assertNull(PublicKey.fromBase58("abc"))
        assertNull(PublicKey.fromBase58("1".repeat(5_000)))
    }

    @Test fun tellsWalletKeysFromProgramAddresses() {
        for (text in Vectors.onCurve) assertTrue(key(text).isOnCurve, text)
        for (text in Vectors.offCurve) assertFalse(key(text).isOnCurve, text)
    }

    @Test fun derivesTheSameAddressesAsSolana() {
        val program = key(Vectors.programId)
        fun find(vararg seeds: ByteArray) = PublicKey.findProgramAddress(seeds.toList(), program)!!
        assertEquals(Pair(key(Vectors.config.first), Vectors.config.second), find("config".toByteArray()))
        assertEquals(Pair(key(Vectors.pool.first), Vectors.pool.second), find("pool".toByteArray()))
        assertEquals(Pair(key(Vectors.vault.first), Vectors.vault.second), find("vault".toByteArray()))
        assertEquals(
            Pair(key(Vectors.member.first), Vectors.member.second),
            find("member".toByteArray(), key(Vectors.publicKey).bytes),
        )
    }

    @Test fun keysCompareByValue() {
        assertEquals(key(Vectors.publicKey), PublicKey.of(hex(Vectors.publicKeyHex)))
        assertEquals(key(Vectors.publicKey).hashCode(), PublicKey.of(hex(Vectors.publicKeyHex)).hashCode())
        assertNotEquals(key(Vectors.publicKey), key(Vectors.recipient))
        assertEquals(Vectors.publicKey, key(Vectors.publicKey).toString())
    }

    @Test fun derivesThePublicKeyFromTheSeed() {
        val pair = assertNotNull(Keypair.fromSeed(hex(Vectors.seedHex)))
        assertEquals(Vectors.publicKey, pair.publicKey.base58)
        assertEquals(Vectors.seedHex, pair.seed.toHex())
    }

    @Test fun rejectsSeedsOfTheWrongLength() {
        assertNull(Keypair.fromSeed(ByteArray(31)))
        assertNull(Keypair.fromSeed(ByteArray(64)))
    }

    @Test fun producesSignaturesSolanaAccepts() {
        val pair = Keypair.fromSeed(hex(Vectors.seedHex))!!
        val message = Vectors.signedMessage.toByteArray()
        val signature = pair.sign(message)
        assertEquals(Vectors.signatureHex, signature.toHex())
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(pair.publicKey.bytes, 0))
        verifier.update(message, 0, message.size)
        assertTrue(verifier.verifySignature(signature))
    }

    @Test fun newKeypairsDiffer() {
        assertNotEquals(Keypair.generate().publicKey, Keypair.generate().publicKey)
    }

    @Test fun writesLittleEndianIntegersAndLengthPrefixedStrings() {
        val w = BorshWriter()
        w.u8(7); w.u32(1u); w.u64(300_000_000uL); w.i64(-2); w.string("ñu")
        assertEquals("07" + "01000000" + "00a3e11100000000" + "feffffffffffffff" + "03000000c3b175", w.toByteArray().toHex())
    }

    @Test fun readsBackWhatItWrote() {
        val w = BorshWriter()
        w.u8(255); w.u32(UInt.MAX_VALUE); w.u64(ULong.MAX_VALUE); w.i64(Long.MIN_VALUE)
        w.publicKey(key(Vectors.publicKey)); w.string("Camila ☕"); w.bytes(byteArrayOf(1, 2))
        val r = BorshReader(w.toByteArray())
        assertEquals(255, r.u8())
        assertEquals(UInt.MAX_VALUE, r.u32())
        assertEquals(ULong.MAX_VALUE, r.u64())
        assertEquals(Long.MIN_VALUE, r.i64())
        assertEquals(key(Vectors.publicKey), r.publicKey())
        assertEquals("Camila ☕", r.string())
        assertContentEquals(byteArrayOf(1, 2), r.bytes(2))
        assertTrue(r.isAtEnd)
    }

    @Test fun throwsInsteadOfReadingPastTheEnd() {
        assertFailsWith<BorshException> { BorshReader(byteArrayOf(1, 2, 3)).u64() }
        // A string that claims to be 4 GB long.
        assertFailsWith<BorshException> { BorshReader(hex("ffffffff41")).string() }
        // Bytes that are not text.
        assertFailsWith<BorshException> { BorshReader(hex("02000000c328")).string() }
    }
}
