package com.riyadm.unsendvault.ui

import android.net.Uri
import app.cash.turbine.test
import com.riyadm.unsendvault.data.BackupResult
import com.riyadm.unsendvault.data.ImportMode
import com.riyadm.unsendvault.data.fake.InMemorySettingsRepository
import com.riyadm.unsendvault.ui.settings.BackupEvent
import com.riyadm.unsendvault.ui.settings.BackupOperation
import com.riyadm.unsendvault.ui.settings.BackupViewModel
import com.riyadm.unsendvault.ui.settings.PasswordIssue
import com.riyadm.unsendvault.ui.settings.PasswordRules
import com.riyadm.unsendvault.ui.settings.PasswordStrength
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val settings = InMemorySettingsRepository()
    private val backup = FakeBackupManager()
    private val now = 1_761_696_000_000L // 2025-10-29T00:00Z
    private val vm = BackupViewModel(backup, settings, clock = { now }, zone = ZoneOffset.UTC)
    private val destination = Uri.parse("content://test/backup")

    @Test
    fun passwordRules() {
        assertEquals(PasswordIssue.TOO_SHORT, PasswordRules.validate("short", "short"))
        assertEquals(PasswordIssue.MISMATCH, PasswordRules.validate("longenough1", "longenough2"))
        assertNull(PasswordRules.validate("longenough1", "longenough1"))
        assertEquals(PasswordStrength.WEAK, PasswordRules.strength("abcdefgh"))
        assertEquals(PasswordStrength.FAIR, PasswordRules.strength("abcdefgh12"))
        assertEquals(PasswordStrength.STRONG, PasswordRules.strength("Abcdefgh12!x"))
        assertEquals(PasswordStrength.STRONG, PasswordRules.strength("correct horse battery"))
    }

    @Test
    fun invalidPasswordKeepsDialogOpen() = runTest {
        vm.openExportDialog()
        vm.events.test {
            assertEquals(PasswordIssue.MISMATCH, vm.confirmExportPassword("password-one", "password-two"))
            expectNoEvents()
        }
        assertTrue(vm.state.value.exportDialogOpen)
    }

    @Test
    fun exportFlowSucceedsRecordsTimeAndWipesPassword() = runTest {
        vm.openExportDialog()
        vm.events.test {
            assertNull(vm.confirmExportPassword("correct horse", "correct horse"))
            assertEquals(BackupEvent.PickExportDestination("unsendvault-backup-2025-10-29.uvbackup"), awaitItem())
            assertTrue(!vm.state.value.exportDialogOpen)
            vm.onExportDestination(destination)
            assertEquals(BackupEvent.Finished(BackupOperation.EXPORT, BackupResult.Success(4, 3)), awaitItem())
        }
        assertEquals(listOf(destination to "correct horse"), backup.exports)
        assertTrue("password buffer must be wiped", backup.lastPasswordArray!!.all { it == '\u0000' })
        assertEquals(now, settings.current.lastExportAt)
        assertNull(vm.state.value.running)
    }

    @Test
    fun cancelledPickerDoesNotExport() = runTest {
        vm.events.test {
            vm.confirmExportPassword("correct horse", "correct horse")
            assertTrue(awaitItem() is BackupEvent.PickExportDestination)
            vm.onExportDestination(null)
            assertTrue(backup.exports.isEmpty())
            // A later picker result without a fresh password is rejected, not exported with a stale one.
            vm.onExportDestination(destination)
            assertEquals(BackupEvent.PasswordLost, awaitItem())
        }
        assertTrue(backup.exports.isEmpty())
    }

    @Test
    fun failedExportDoesNotRecordTime() = runTest {
        backup.exportResult = BackupResult.Failure(BackupResult.Reason.IO_ERROR)
        vm.events.test {
            vm.confirmExportPassword("correct horse", "correct horse")
            awaitItem()
            vm.onExportDestination(destination)
            assertEquals(BackupEvent.Finished(BackupOperation.EXPORT, BackupResult.Failure(BackupResult.Reason.IO_ERROR)), awaitItem())
        }
        assertNull(settings.current.lastExportAt)
    }

    @Test
    fun importFlowUsesChosenMode() = runTest {
        vm.events.test {
            vm.startImport()
            assertEquals(BackupEvent.PickImportSource, awaitItem())
            vm.onImportSource(destination)
            assertEquals(destination, vm.state.value.importSource)
            vm.confirmImport("correct horse", ImportMode.REPLACE)
            assertEquals(BackupEvent.Finished(BackupOperation.IMPORT, BackupResult.Success(4, 3)), awaitItem())
        }
        assertEquals(listOf(Triple(destination, "correct horse", ImportMode.REPLACE)), backup.imports)
        assertNull(vm.state.value.importSource)
    }

    @Test
    fun importWrongPasswordIsReported() = runTest {
        backup.importResult = BackupResult.Failure(BackupResult.Reason.WRONG_PASSWORD)
        vm.onImportSource(destination)
        vm.events.test {
            vm.confirmImport("nope nope", ImportMode.MERGE)
            assertEquals(BackupEvent.Finished(BackupOperation.IMPORT, BackupResult.Failure(BackupResult.Reason.WRONG_PASSWORD)), awaitItem())
        }
    }

    @Test
    fun emptyImportPasswordRejectedWithoutCallingManager() = runTest {
        vm.onImportSource(destination)
        vm.events.test {
            vm.confirmImport("", ImportMode.MERGE)
            assertEquals(BackupEvent.Finished(BackupOperation.IMPORT, BackupResult.Failure(BackupResult.Reason.EMPTY_PASSWORD)), awaitItem())
        }
        assertTrue(backup.imports.isEmpty())
    }
}
