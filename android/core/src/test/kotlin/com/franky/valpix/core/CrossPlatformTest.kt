package com.franky.valpix.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The exact text and bytes the iPhone and Android apps hand each other. The
 * same fixtures are pinned in the Swift core
 * (`ios/ValpixCore/Tests/ValpixCoreTests/CrossPlatformTests.swift`): change
 * one side only and a phone stops being able to read the other's codes.
 */
class CrossPlatformTest {
    private val pay = "valpix:pay?to=3eSdHG44thnPEP7QETrxKDLpMbecvTA2HVeXFJJiAd2Q&amount=1500&name=pan%20%26%20caf%C3%A9%20%2B1"
    private val open = "valpix:pay?to=3eSdHG44thnPEP7QETrxKDLpMbecvTA2HVeXFJJiAd2Q"
    private val join = "valpix:join?key=8u8LCMQvMKrFxHbn326Ltcqv72HDPEC5FPMgPC3mXvxV&name=%C3%91and%C3%BA%20%3D%20100%25"
    private val wallet = "valpix:wallet?seed=4wBqpZM9xaSheZzJSMawUKKwhdpChKbZ5eu5ky4Vigw"
    /** Sealed by the Swift core. */
    private val backupFromSwift = "valpix:backup?d=AQOmdWr_IqZ30ayvAIjUGkUFJhiZFOGeyntPE9MCDRUzXD7XoO9Cc8TNwJ0wpbgQjTIcaTIIAKRPops8eoDZtS-7FplJAhScmyW-sg8"
    private val password = "contraseña ñandú 1"

    private val recipient = key(Vectors.recipient)
    private val newcomer = key(Vectors.newcomer)
    private val seed = hex(Vectors.seedHex)

    @Test fun writesLinksAsPinned() {
        assertEquals(pay, ValpixLink.Pay(recipient, 1_500uL, "pan & café +1").string)
        assertEquals(open, ValpixLink.Pay(recipient, null, "").string)
        assertEquals(join, ValpixLink.Join(newcomer, "Ñandú = 100%").string)
        assertEquals(wallet, ValpixLink.Wallet(seed).string)
    }

    @Test fun readsPinnedLinks() {
        assertEquals(ValpixLink.Pay(recipient, 1_500uL, "pan & café +1"), ValpixLink.parse(pay))
        assertEquals(ValpixLink.Pay(recipient, null, ""), ValpixLink.parse(open))
        assertEquals(ValpixLink.Join(newcomer, "Ñandú = 100%"), ValpixLink.parse(join))
        assertEquals(ValpixLink.Wallet(seed), ValpixLink.parse(wallet))
    }

    @Test fun opensABackupSealedBySwift() {
        val link = assertIs<ValpixLink.Backup>(ValpixLink.parse(backupFromSwift))
        assertContentEquals(seed, WalletBackup.open(link.blob, password))
        assertNull(WalletBackup.open(link.blob, "contrasena nandu 1"))
    }
}
