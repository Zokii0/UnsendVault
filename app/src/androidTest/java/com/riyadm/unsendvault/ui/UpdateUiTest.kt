package com.riyadm.unsendvault.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.ui.settings.UpdateSettingsContent
import com.riyadm.unsendvault.ui.theme.UnsendVaultTheme
import com.riyadm.unsendvault.ui.update.UpdateAvailableDialog
import com.riyadm.unsendvault.ui.update.UpdateConsentDialog
import com.riyadm.unsendvault.ui.update.UpdateTestTags
import com.riyadm.unsendvault.update.FailureReason
import com.riyadm.unsendvault.update.ManualCheckState
import com.riyadm.unsendvault.update.ReleaseInfo
import com.riyadm.unsendvault.update.UpdateConsent
import com.riyadm.unsendvault.update.UpdateUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateUiTest {
    @get:Rule val rule = createComposeRule()

    private val release = ReleaseInfo(
        version = "1.0.16",
        tagName = "v1.0.16",
        name = "UnsendVault 1.0.16",
        pageUrl = "https://github.com/riyadmondol2006/UnsendVault/releases/tag/v1.0.16",
        apkUrl = "https://github.com/riyadmondol2006/UnsendVault/releases/download/v1.0.16/UnsendVault-1.0.16.apk",
        notes = "## Install\n\nignored\n\n## What changed\n\n- **Faster** search\n- <b>Fixed</b> [a crash](https://example.com)\n",
    )

    @Test
    fun consentDialog_buttons() {
        val clicks = mutableListOf<String>()
        rule.setContent { UnsendVaultTheme { UpdateConsentDialog(onAllow = { clicks += "allow" }, onNotNow = { clicks += "no" }) } }
        rule.onNodeWithText(str(R.string.update_consent_title)).assertIsDisplayed()
        rule.onNodeWithTag(UpdateTestTags.CONSENT_ALLOW).performClick()
        rule.onNodeWithTag(UpdateTestTags.CONSENT_DECLINE).performClick()
        assertEquals(listOf("allow", "no"), clicks)
    }

    @Test
    fun updateDialog_showsVersionsPlainNotesAndOpensLinks() {
        val opened = mutableListOf<Pair<String, String?>>()
        val clicks = mutableListOf<String>()
        rule.setContent {
            UnsendVaultTheme {
                UpdateAvailableDialog(
                    release = release,
                    currentVersion = "1.0.15",
                    onLater = { clicks += "later" },
                    onSkip = { clicks += "skip" },
                    openUrl = { url, fallback -> opened += url to fallback },
                )
            }
        }
        rule.onNodeWithText(str(R.string.update_available_title, "1.0.16")).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.update_current_version, "1.0.15")).assertIsDisplayed()
        rule.onNodeWithText("• Faster search\n• Fixed a crash").assertExists()
        rule.onNodeWithTag(UpdateTestTags.DOWNLOAD).performClick()
        rule.onNodeWithTag(UpdateTestTags.RELEASE_PAGE).performClick()
        rule.onNodeWithTag(UpdateTestTags.LATER).performClick()
        rule.onNodeWithTag(UpdateTestTags.SKIP).performClick()
        assertEquals(listOf(release.apkUrl to release.pageUrl, release.pageUrl to null), opened)
        assertEquals(listOf("later", "skip"), clicks)
    }

    @Test
    fun updateDialog_withoutApk_hidesDownload() {
        rule.setContent {
            UnsendVaultTheme { UpdateAvailableDialog(release.copy(apkUrl = null), "1.0.15", {}, {}, openUrl = { _, _ -> }) }
        }
        rule.onNodeWithTag(UpdateTestTags.DOWNLOAD).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.update_no_apk_hint)).assertExists()
    }

    @Test
    fun settingsSection_toggleAndInlineResults() {
        val toggles = mutableListOf<Boolean>()
        var checks = 0
        var state by mutableStateOf(UpdateUiState(consent = UpdateConsent.DECLINED, currentVersion = "1.0.15"))
        rule.setContent {
            UnsendVaultTheme {
                Column { UpdateSettingsContent(state, onAutoCheckChange = { toggles += it }, onCheckNow = { checks++ }, onShowUpdate = {}) }
            }
        }
        rule.onNodeWithTag(UpdateTestTags.AUTO_SWITCH).assertIsOff().performClick()
        assertEquals(listOf(true), toggles)
        rule.onNodeWithTag(UpdateTestTags.CHECK_NOW).performClick()
        assertEquals(1, checks)

        state = state.copy(consent = UpdateConsent.ALLOWED, manual = ManualCheckState.UpToDate("1.0.15"))
        rule.onNodeWithTag(UpdateTestTags.AUTO_SWITCH).assertIsOn()
        rule.onNodeWithText(str(R.string.update_result_up_to_date, "1.0.15")).assertIsDisplayed()

        state = state.copy(manual = ManualCheckState.Failed(FailureReason.RATE_LIMITED))
        rule.onNodeWithText(str(R.string.update_result_rate_limited)).assertIsDisplayed()

        state = state.copy(manual = ManualCheckState.Failed(FailureReason.OFFLINE))
        rule.onNodeWithText(str(R.string.update_result_offline)).assertIsDisplayed()

        state = state.copy(manual = ManualCheckState.Available(release))
        rule.onNodeWithText(str(R.string.update_result_available, "1.0.16")).assertIsDisplayed()
    }
}
