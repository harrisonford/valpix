package com.franky.valpix.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinkTest {
    private val recipient = key(Vectors.recipient)
    private val newcomer = key(Vectors.newcomer)
    private val seed = hex(Vectors.seedHex)
    private val to = Vectors.recipient

    @Test fun roundTripsAPaymentRequest() {
        val link = ValpixLink.Pay(recipient, 1_500uL, "Camila")
        assertEquals(link, ValpixLink.parse(link.string))
        assertEquals("valpix:pay?to=$to&amount=1500&name=Camila", link.string)
    }

    @Test fun roundTripsAnOpenRequestAndAccents() {
        for (name in listOf("", "pan & café", "Ñandú = 100%", "a+b", "¿qué? ☕", "x#y/z?w")) {
            val link = ValpixLink.Pay(recipient, null, name)
            assertEquals(link, ValpixLink.parse(link.string), name)
        }
    }

    @Test fun roundTripsJoinWalletAndBackup() {
        val join = ValpixLink.Join(newcomer, "Ñandú")
        assertEquals(join, ValpixLink.parse(join.string))
        val wallet = ValpixLink.Wallet(seed)
        assertEquals(wallet, ValpixLink.parse(wallet.string))
        val backup = ValpixLink.Backup(ByteArray(77) { (it * 3).toByte() })
        assertEquals(backup, ValpixLink.parse(backup.string))
    }

    @Test fun acceptsTheDoubleSlashFormAndAnyCaseOfScheme() {
        val expected = ValpixLink.Pay(recipient, 5uL, "")
        assertEquals(expected, ValpixLink.parse("valpix://pay?to=$to&amount=5"))
        assertEquals(expected, ValpixLink.parse("VALPIX:PAY?to=$to&amount=5"))
        assertEquals(expected, ValpixLink.parse("  valpix:pay?to=$to&amount=5\n"))
        // Keys this version does not know are ignored.
        assertEquals(expected, ValpixLink.parse("valpix:pay?to=$to&amount=5&v=2"))
    }

    @Test fun rejectsHostileOrBrokenContent() {
        val bad = listOf(
            "", "   ", "valpix", "valpix:", "valpix:pay", "valpix:pay?", "https://valpix.cl/pay?to=$to",
            "bitcoin:pay?to=$to", "valpix:steal?to=$to", "valpix:/pay?to=$to",
            "valpix:pay?to=abc", "valpix:pay?to=", "valpix:pay?to=${to}0", "valpix:pay?name=x",
            "valpix:pay?to=$to&amount=0", "valpix:pay?to=$to&amount=-5", "valpix:pay?to=$to&amount=",
            "valpix:pay?to=$to&amount=1.5", "valpix:pay?to=$to&amount=1e3", "valpix:pay?to=$to&amount=%2B5",
            "valpix:pay?to=$to&amount=1000000001", "valpix:pay?to=$to&amount=99999999999999999999999",
            "valpix:pay?to=$to&amount=١٢", "valpix:pay?to=$to&amount=5&amount=6", "valpix:pay?to=$to&to=$to",
            "valpix:pay?to=$to&name", "valpix:pay?to=$to&&name=x",
            "valpix:pay?to=$to&name=" + "x".repeat(33),
            "valpix:pay?to=$to&name=" + "ñ".repeat(17),
            "valpix:pay?to=$to&name=a%00b", "valpix:pay?to=$to&name=a%0Ab", "valpix:pay?to=$to&name=a%E2%80%8Bb",
            "valpix:pay?to=$to&name=%zz", "valpix:pay?to=$to&name=%4", "valpix:pay?to=$to&name=%ff",
            "valpix:join?key=abc", "valpix:join?to=$to",
            "valpix:wallet?seed=abc", "valpix:wallet?seed=$to&seed=$to",
            "valpix:backup?d=", "valpix:backup?d=a+b", "valpix:backup?d=a%20b", "valpix:backup?d=A",
        )
        for (text in bad) assertNull(ValpixLink.parse(text), text)
    }

    @Test fun acceptsTheLongestAllowedName() {
        val name = "ñ".repeat(16)
        assertEquals(32, name.toByteArray().size)
        assertEquals(ValpixLink.Pay(recipient, null, name), ValpixLink.parse(ValpixLink.Pay(recipient, null, name).string))
        assertEquals(ValpixLink.Pay(recipient, 1_000_000_000uL, ""), ValpixLink.parse("valpix:pay?to=$to&amount=1000000000"))
    }

    @Test fun aNameIsMadeSafeBeforeItGoesIntoACode() {
        assertEquals("Ana", ValpixLink.shareableName("  Ana \n"))
        assertEquals("ab", ValpixLink.shareableName("a\u0000b"))
        // Many emoji are joined by an invisible character that scanners
        // refuse in a name; left in, nobody could read this person's codes.
        val joined = "Ana \uD83E\uDD37\u200D\u2640\uFE0F"
        val safe = ValpixLink.shareableName(joined)
        assertFalse('\u200D' in safe)
        assertTrue(safe.startsWith("Ana "))
        assertEquals(ValpixLink.Join(newcomer, safe), ValpixLink.parse(ValpixLink.Join(newcomer, safe).string))
        assertEquals(ValpixLink.Pay(recipient, 5uL, safe), ValpixLink.parse(ValpixLink.Pay(recipient, 5uL, safe).string))
        // At most 32 bytes, never cutting a character in half.
        assertEquals("ñ".repeat(16), ValpixLink.shareableName("ñ".repeat(40)))
        assertEquals("\uD83D\uDE00".repeat(8), ValpixLink.shareableName("\uD83D\uDE00".repeat(20)))
        assertEquals("a" + "\uD83D\uDE00".repeat(7), ValpixLink.shareableName("a" + "\uD83D\uDE00".repeat(20)))
    }

    @Test fun veryLongLinksAreRejectedBeforeAnyWork() {
        assertNull(ValpixLink.parse("valpix:pay?to=$to&x=" + "a".repeat(1_100)))
        assertNotNull(ValpixLink.parse("valpix:pay?to=$to&x=" + "a".repeat(900)))
    }

    @Test fun opensWithTheRightPassword() {
        val blob = WalletBackup.seal(seed, "correcto caballo")
        assertEquals(77, blob.size)
        assertContentEquals(seed, WalletBackup.open(blob, "correcto caballo"))
    }

    @Test fun staysShutWithTheWrongPassword() {
        val blob = WalletBackup.seal(seed, "correcto caballo")
        assertNull(WalletBackup.open(blob, "correcto caballo "))
        assertNull(WalletBackup.open(blob, ""))
    }

    @Test fun detectsTampering() {
        val blob = WalletBackup.seal(seed, "clave")
        for (index in listOf(0, 1, 17, 29, 60, 76)) {
            val altered = blob.copyOf()
            altered[index] = (altered[index].toInt() xor 1).toByte()
            assertNull(WalletBackup.open(altered, "clave"), "byte $index")
        }
        assertNull(WalletBackup.open(blob.copyOf(76), "clave"))
        assertNull(WalletBackup.open(blob + 0, "clave"))
    }

    @Test fun neverProducesTheSameBlobTwice() {
        assertNotEquals(WalletBackup.seal(seed, "clave").toHex(), WalletBackup.seal(seed, "clave").toHex())
    }

    @Test fun refusesAnEmptyPasswordOrABadSeed() {
        assertFailsWith<IllegalArgumentException> { WalletBackup.seal(seed, "") }
        assertFailsWith<IllegalArgumentException> { WalletBackup.seal(ByteArray(31), "clave") }
    }
}
