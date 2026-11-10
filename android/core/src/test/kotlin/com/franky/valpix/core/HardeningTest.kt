package com.franky.valpix.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HardeningTest {
    private val pid = key(Vectors.programId)
    private val other = "11111111111111111111111111111111"
    private val line = Vectors.paidLogLine
    private fun events(vararg logs: String) = ValpixEvent.events(logs.toList(), pid)

    @Test fun acceptsEventsLoggedByValpix() {
        val found = events("Program $pid invoke [1]", "Program log: Instruction: Pay", line, "Program $pid consumed 9 of 200000 compute units", "Program $pid success")
        assertEquals(1, found.size)
        assertEquals(Vectors.paidAmount, (found[0] as ValpixEvent.Paid).amount)
    }

    @Test fun ignoresTheSameBytesLoggedByAnotherProgram() {
        assertEquals(emptyList(), events("Program $other invoke [1]", line, "Program $other success"))
    }

    @Test fun ignoresEventsLoggedAfterValpixReturned() {
        assertEquals(emptyList(), events("Program $pid invoke [1]", "Program $pid success", line))
        assertEquals(emptyList(), events("Program $pid invoke [1]", "Program $pid failed: custom program error: 0x1", line))
    }

    @Test fun tracksNestedCalls() {
        // valpix calls another program, which logs the bytes: not ours.
        assertEquals(emptyList(), events("Program $pid invoke [1]", "Program $other invoke [2]", line, "Program $other success", "Program $pid success"))
        // Back in valpix after the inner call: ours.
        assertEquals(1, events("Program $pid invoke [1]", "Program $other invoke [2]", "Program $other success", line, "Program $pid success").size)
        // Another program calls valpix: ours while valpix runs.
        assertEquals(1, events("Program $other invoke [1]", "Program $pid invoke [2]", line, "Program $pid success", "Program $other success").size)
    }

    @Test fun aLineWithNoProgramRunningIsIgnored() {
        assertEquals(emptyList(), events(line))
        assertEquals(emptyList(), events("Program log: Program $pid invoke [1]", line))
    }

    private fun entry(kind: HistoryEntry.Kind, pesos: ULong, ts: Long) =
        HistoryEntry("sig", ts, kind, key(Vectors.recipient), "", pesos * Money.UNIT)

    @Test fun aFixedChargeNeedsAtLeastItsAmount() {
        assertTrue(entry(HistoryEntry.Kind.RECEIVED, 300uL, 100).settles(300uL, 100.0))
        assertTrue(entry(HistoryEntry.Kind.RECEIVED, 301uL, 100).settles(300uL, 100.0))
        assertFalse(entry(HistoryEntry.Kind.RECEIVED, 299uL, 100).settles(300uL, 100.0))
        assertFalse(entry(HistoryEntry.Kind.RECEIVED, 300uL, 100).settles(ULong.MAX_VALUE, 100.0))
    }

    @Test fun anOpenChargeAcceptsAnyAmount() {
        assertTrue(entry(HistoryEntry.Kind.RECEIVED, 1uL, 100).settles(null, 100.0))
    }

    @Test fun olderPaymentsAndOtherKindsDoNotCount() {
        assertFalse(entry(HistoryEntry.Kind.RECEIVED, 300uL, 99).settles(300uL, 100.0))
        assertFalse(entry(HistoryEntry.Kind.SENT, 300uL, 100).settles(300uL, 100.0))
        assertFalse(entry(HistoryEntry.Kind.INVITED, 300uL, 100).settles(null, 100.0))
        assertFalse(entry(HistoryEntry.Kind.JOINED, 300uL, 100).settles(null, 100.0))
    }

    private val own = key(Vectors.publicKey)
    private val someone = key(Vectors.recipient)
    private val member = WalletSituation(own, isMember = true, chainConfirmedNoAccount = false)
    private val newcomer = WalletSituation(own, isMember = false, chainConfirmedNoAccount = true)
    private val unknown = WalletSituation(own, isMember = false, chainConfirmedNoAccount = false)
    private val foreignSeed = ByteArray(32) { 9 }

    @Test fun membersCanBeAskedToPayOrInviteFromAnywhere() {
        for (source in LinkSource.entries) {
            assertEquals(LinkAction.Pay(someone, 5uL, "x"), LinkPolicy.action(ValpixLink.Pay(someone, 5uL, "x"), source, member))
            assertEquals(LinkAction.Invite(someone, "x"), LinkPolicy.action(ValpixLink.Join(someone, "x"), source, member))
        }
    }

    @Test fun nobodyPaysOrInvitesThemselvesOrWithoutAnAccount() {
        assertEquals(LinkAction.Ignore, LinkPolicy.action(ValpixLink.Pay(own, 5uL, ""), LinkSource.SCANNED, member))
        assertEquals(LinkAction.Ignore, LinkPolicy.action(ValpixLink.Join(own, ""), LinkSource.SCANNED, member))
        assertEquals(LinkAction.Ignore, LinkPolicy.action(ValpixLink.Pay(someone, 5uL, ""), LinkSource.SCANNED, newcomer))
        assertEquals(LinkAction.Ignore, LinkPolicy.action(ValpixLink.Join(someone, ""), LinkSource.SCANNED, newcomer))
    }

    @Test fun aWalletNeverArrivesThroughATappedLink() {
        assertEquals(LinkAction.Ignore, LinkPolicy.action(ValpixLink.Wallet(foreignSeed), LinkSource.EXTERNAL, newcomer))
        assertEquals(LinkAction.Ignore, LinkPolicy.action(ValpixLink.Backup(ByteArray(77)), LinkSource.EXTERNAL, newcomer))
    }

    @Test fun aScannedWalletIsOfferedOnlyOnceTheChainSaysThisPhoneHasNoAccount() {
        assertEquals(LinkAction.Adopt(foreignSeed), LinkPolicy.action(ValpixLink.Wallet(foreignSeed), LinkSource.SCANNED, newcomer))
        assertEquals(LinkAction.Unlock(ByteArray(77)), LinkPolicy.action(ValpixLink.Backup(ByteArray(77)), LinkSource.SCANNED, newcomer))
        for (situation in listOf(member, unknown)) {
            assertEquals(LinkAction.Ignore, LinkPolicy.action(ValpixLink.Wallet(foreignSeed), LinkSource.SCANNED, situation))
            assertEquals(LinkAction.Ignore, LinkPolicy.action(ValpixLink.Backup(ByteArray(77)), LinkSource.SCANNED, situation))
        }
    }

    @Test fun scanningYourOwnWalletDoesNothing() {
        val seed = hex(Vectors.seedHex)
        assertEquals(LinkAction.Ignore, LinkPolicy.action(ValpixLink.Wallet(seed), LinkSource.SCANNED, newcomer))
    }

    /** A store that behaves as told. */
    private class MemoryStore(var content: StoredSecret, val keeps: Boolean = true) : SecretStore {
        var writes = 0
        override fun read() = content
        override fun add(secret: ByteArray): Boolean {
            writes++
            if (content != StoredSecret.Missing) return false
            if (keeps) content = StoredSecret.Found(secret)
            return true
        }
        override fun replace(secret: ByteArray): Boolean {
            writes++
            if (content !is StoredSecret.Found) return false
            if (keeps) content = StoredSecret.Found(secret)
            return true
        }
    }

    @Test fun makesAWalletOnlyWhenThereIsNone() {
        val store = MemoryStore(StoredSecret.Missing)
        val made = assertNotNull(WalletKeeper.loadOrCreate(store))
        assertEquals(StoredSecret.Found(made.seed), store.content)
        assertEquals(made.publicKey, WalletKeeper.loadOrCreate(store)?.publicKey)
        assertEquals(1, store.writes)
    }

    @Test fun returnsTheStoredWalletUntouched() {
        val store = MemoryStore(StoredSecret.Found(hex(Vectors.seedHex)))
        assertEquals(Vectors.publicKey, WalletKeeper.loadOrCreate(store)?.publicKey?.base58)
        assertEquals(0, store.writes)
    }

    @Test fun neverCreatesOrOverwritesWhenTheStoreCannotBeRead() {
        val store = MemoryStore(StoredSecret.Unavailable)
        assertNull(WalletKeeper.loadOrCreate(store))
        assertNull(WalletKeeper.replace(foreignSeed, store))
        assertEquals(0, store.writes)
    }

    @Test fun leavesDamagedContentsAlone() {
        val store = MemoryStore(StoredSecret.Found(ByteArray(5)))
        assertNull(WalletKeeper.loadOrCreate(store))
        assertEquals(0, store.writes)
    }

    @Test fun refusesAWalletThatWasNotKept() {
        assertNull(WalletKeeper.loadOrCreate(MemoryStore(StoredSecret.Missing, keeps = false)))
        assertNull(WalletKeeper.replace(foreignSeed, MemoryStore(StoredSecret.Found(hex(Vectors.seedHex)), keeps = false)))
        assertNull(WalletKeeper.replace(ByteArray(31), MemoryStore(StoredSecret.Missing)))
    }

    @Test fun replacingUpdatesInPlace() {
        val store = MemoryStore(StoredSecret.Found(hex(Vectors.seedHex)))
        val replaced = assertNotNull(WalletKeeper.replace(foreignSeed, store))
        assertContentEquals(foreignSeed, replaced.seed)
        assertEquals(StoredSecret.Found(foreignSeed), store.content)
        val empty = MemoryStore(StoredSecret.Missing)
        assertNotNull(WalletKeeper.replace(foreignSeed, empty))
        assertEquals(StoredSecret.Found(foreignSeed), empty.content)
    }

    private class Counting(val answer: String?) : RpcTransport {
        var asked = 0
        override fun post(body: String): String {
            asked++
            return answer ?: throw IOException("down")
        }
    }

    @Test fun usesTheNextAccessPointWhenOneIsDown() {
        val down = Counting(null)
        val up = Counting("ok")
        assertEquals("ok", FallbackTransport(listOf(down, up)).post("{}"))
        assertEquals(1, down.asked)
        assertEquals(1, up.asked)
    }

    @Test fun doesNotBotherTheOthersWhileTheFirstAnswers() {
        val first = Counting("first")
        val second = Counting("second")
        assertEquals("first", FallbackTransport(listOf(first, second)).post("{}"))
        assertEquals(0, second.asked)
    }

    @Test fun failsOnlyWhenAllAreDown() {
        assertFailsWith<IOException> { FallbackTransport(listOf(Counting(null), Counting(null))).post("{}") }
        assertFailsWith<IOException> { FallbackTransport(emptyList()).post("{}") }
    }
}
