package com.riyadm.unsendvault.data

import com.riyadm.unsendvault.capture.IngestResult
import com.riyadm.unsendvault.core.model.MessageQuery
import com.riyadm.unsendvault.data.Fx.T0
import com.riyadm.unsendvault.data.Fx.msg
import com.riyadm.unsendvault.data.Fx.parsed
import com.riyadm.unsendvault.data.db.VaultDatabase
import com.riyadm.unsendvault.data.repo.RoomVaultRepository
import com.riyadm.unsendvault.data.repo.vaultHealth
import com.riyadm.unsendvault.data.fake.InMemoryVaultRepository
import com.riyadm.unsendvault.data.security.VaultDatabaseHolder
import com.riyadm.unsendvault.data.security.VaultKeyException
import com.riyadm.unsendvault.data.security.VaultKeyState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultHealthTest : RoomTestBase() {

    @Test
    fun unavailableVault_readsEmpty_writesNoop_thenRetryAndReset() = runBlocking {
        var now = 0L
        var failures = 1
        var destroyed = 0
        val holder = VaultDatabaseHolder(
            opener = {
                if (failures-- > 0) throw VaultKeyException(VaultKeyState.Reason.KEYSTORE_ERROR, "busy")
                db
            },
            destroyer = { destroyed++; db.clearAllTables() },
            clock = { now },
            retryCooldownMs = 30_000,
            closeOnReset = false,
        )
        val r = RoomVaultRepository(holder)
        assertEquals(VaultKeyState.Initializing, r.keyState.value)
        assertEquals(IngestResult.EMPTY, r.ingest(parsed(msg("x")), T0))
        val state = r.keyState.value as VaultKeyState.Unavailable
        assertEquals(VaultKeyState.Reason.KEYSTORE_ERROR, state.reason)
        assertTrue(state.retryable)
        assertEquals(0, r.observeStats().first().totalMessages)
        assertTrue(r.observeMessages(MessageQuery()).first().isEmpty())
        assertEquals(0, r.deleteOlderThan(Long.MAX_VALUE))

        // Within the cooldown nothing is retried automatically; afterwards it is.
        now = 30_000
        assertEquals(1, r.ingest(parsed(msg("x")), T0).inserted)
        assertEquals(VaultKeyState.Ready, r.keyState.value)

        assertEquals(VaultKeyState.Ready, r.resetVault())
        assertEquals(1, destroyed)
        assertEquals(0, r.observeStats().first().totalMessages)
    }

    @Test
    fun permanentFailure_isNotRetriedAutomatically_butRetryOpenWorks() = runBlocking {
        var fail = true
        var attempts = 0
        val holder = VaultDatabaseHolder(
            opener = {
                attempts++
                if (fail) throw VaultKeyException(VaultKeyState.Reason.KEY_LOST, "gone")
                db
            },
            destroyer = {},
            clock = { Long.MAX_VALUE / 2 },
            closeOnReset = false,
        )
        assertNull(holder.database())
        assertNull(holder.database())
        assertEquals(1, attempts)
        fail = false
        assertEquals(VaultKeyState.Ready, holder.retryOpen())
        assertEquals(db, holder.database())
    }

    @Test
    fun unexpectedOpenError_isDatabaseUnreadable() = runBlocking {
        val holder = VaultDatabaseHolder(opener = { error("file is not a database") }, destroyer = {})
        assertNull(holder.database())
        assertEquals(VaultKeyState.Reason.DATABASE_UNREADABLE, (holder.keyState.value as VaultKeyState.Unavailable).reason)
    }

    @Test
    fun vaultHealthAccessor() {
        assertTrue(repo.vaultHealth != null)
        assertNull(InMemoryVaultRepository().vaultHealth)
        assertTrue(VaultDatabase.FILE_NAME.endsWith(".db"))
    }
}
