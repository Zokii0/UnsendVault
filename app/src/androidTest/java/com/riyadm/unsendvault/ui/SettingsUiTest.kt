package com.riyadm.unsendvault.ui

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.data.fake.InMemorySettingsRepository
import com.riyadm.unsendvault.data.fake.InMemoryVaultRepository
import com.riyadm.unsendvault.ui.settings.BackupTestTags
import com.riyadm.unsendvault.ui.settings.BackupViewModel
import com.riyadm.unsendvault.ui.settings.SettingsScreen
import com.riyadm.unsendvault.ui.settings.SettingsTestTags
import com.riyadm.unsendvault.ui.settings.SettingsViewModel
import com.riyadm.unsendvault.ui.theme.UnsendVaultTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsUiTest {
    @get:Rule val rule = createComposeRule()

    private fun setSettings(settings: InMemorySettingsRepository): BackupViewModel {
        val vm = SettingsViewModel(settings, InMemoryVaultRepository(), FakeRetentionScheduler(), { emptyList() })
        val backup = BackupViewModel(FakeBackupManager(), settings)
        rule.setContent {
            UnsendVaultTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                val backupState by backup.state.collectAsStateWithLifecycle()
                SettingsScreen(state, backupState, vm, backup, {}, {}, {}, {}, showDeveloperOptions = false)
            }
        }
        return backup
    }

    @Test
    fun togglesUpdateSettings() {
        val settings = InMemorySettingsRepository(VaultSettings(onboardingCompleted = true))
        setSettings(settings)
        rule.onNodeWithTag(SettingsTestTags.CAPTURE).assertIsOn().performClick()
        rule.onNodeWithTag(SettingsTestTags.CAPTURE).assertIsOff()
        rule.onNodeWithTag(SettingsTestTags.OTP).performScrollTo().assertIsOn().performClick()
        rule.onNodeWithTag(SettingsTestTags.OTP).assertIsOff()
        rule.onNodeWithTag(SettingsTestTags.SECURE).performScrollTo().assertIsOn()
        rule.waitForIdle()
        assertFalse(settings.current.captureEnabled)
        assertFalse(settings.current.filterOtp)
    }

    @Test
    fun widgetContentDisabledWhileAppLockOn() {
        val settings = InMemorySettingsRepository(VaultSettings(onboardingCompleted = true, appLockEnabled = true))
        setSettings(settings)
        rule.onNodeWithTag(SettingsTestTags.WIDGET).performScrollTo().assertIsNotEnabled().assertIsOff()
    }

    @Test
    fun exportDialogRejectsShortPassword() {
        val settings = InMemorySettingsRepository(VaultSettings(onboardingCompleted = true))
        setSettings(settings)
        rule.onNodeWithTag(SettingsTestTags.EXPORT).performScrollTo().performClick()
        rule.onNodeWithTag(BackupTestTags.PASSWORD).performTextInput("short")
        rule.onNodeWithTag(BackupTestTags.CONFIRM).performTextInput("short")
        rule.onNodeWithTag(BackupTestTags.STRENGTH).assertIsDisplayed()
        rule.onNodeWithTag(BackupTestTags.SUBMIT).performClick()
        rule.onNodeWithTag(BackupTestTags.ERROR).assertIsDisplayed()
    }
}
