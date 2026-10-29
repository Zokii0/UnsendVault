package com.riyadm.unsendvault.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.data.fake.InMemorySettingsRepository
import com.riyadm.unsendvault.security.lock.AuthResult
import com.riyadm.unsendvault.security.lock.LocalLockAuthenticator
import com.riyadm.unsendvault.security.lock.LockAvailability
import com.riyadm.unsendvault.security.lock.LockGate
import com.riyadm.unsendvault.security.lock.LockState
import com.riyadm.unsendvault.security.lock.LockTestTags
import com.riyadm.unsendvault.ui.onboarding.OnboardingScreen
import com.riyadm.unsendvault.ui.onboarding.OnboardingTestTags
import com.riyadm.unsendvault.ui.onboarding.OnboardingViewModel
import com.riyadm.unsendvault.ui.theme.UnsendVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingAndLockUiTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun onboardingWalksThroughLimitsAccessAndFinishes() {
        val settings = InMemorySettingsRepository()
        val listener = FakeListenerStatus(granted = false)
        val vm = OnboardingViewModel(settings, listener)
        var grantClicks = 0
        rule.setContent {
            UnsendVaultTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                OnboardingScreen(state, vm::next, vm::back, { grantClicks++ }, vm::setAppLockEnabled, vm::finish)
            }
        }
        rule.onNodeWithText(str(R.string.onboarding_welcome_title)).assertIsDisplayed()
        rule.onNodeWithTag(OnboardingTestTags.NEXT).performClick()
        // Honest limitations are spelled out before access is requested.
        rule.onNodeWithText(str(R.string.onboarding_limits_title)).assertIsDisplayed()
        rule.onNodeWithTag(OnboardingTestTags.NEXT).performClick()
        rule.onNodeWithText(str(R.string.onboarding_privacy_title)).assertIsDisplayed()
        rule.onNodeWithTag(OnboardingTestTags.NEXT).performClick()

        rule.onNodeWithText(str(R.string.onboarding_access_not_granted)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(OnboardingTestTags.GRANT).performScrollTo().performClick()
        assertEquals(1, grantClicks)
        listener.granted = true
        rule.runOnIdle { vm.refreshAccess() }
        rule.onNodeWithText(str(R.string.onboarding_access_granted)).performScrollTo().assertIsDisplayed()

        rule.onNodeWithTag(OnboardingTestTags.NEXT).performClick()
        rule.onNodeWithText(str(R.string.onboarding_lock_title)).assertIsDisplayed()
        rule.onNodeWithTag(OnboardingTestTags.FINISH).performClick()
        rule.waitUntil(5_000) { settings.current.onboardingCompleted }
    }

    @Test
    fun lockedGateNeverComposesArchiveContent() {
        val auth = FakeLockAuthenticator()
        var state by mutableStateOf(LockState.Locked)
        var unlocked = 0
        rule.setContent {
            UnsendVaultTheme {
                CompositionLocalProvider(LocalLockAuthenticator provides auth) {
                    LockGate(state, onAuthenticated = { unlocked++; state = LockState.Unlocked }, onDisableLock = {}) {
                        Text("Alex Rivera: See you at 7", Modifier.testTag("archive_content"))
                    }
                }
            }
        }
        rule.onNodeWithTag(LockTestTags.SCREEN).assertIsDisplayed()
        rule.onNodeWithTag("archive_content").assertDoesNotExist()
        rule.onNodeWithText("Alex Rivera: See you at 7").assertDoesNotExist()

        // The prompt is shown automatically once; a cancel keeps everything hidden.
        rule.waitUntil(5_000) { auth.pending.isNotEmpty() }
        rule.runOnIdle { auth.pending.removeAt(0)(AuthResult.Cancelled) }
        rule.onNodeWithTag("archive_content").assertDoesNotExist()

        rule.onNodeWithTag(LockTestTags.UNLOCK).performClick()
        rule.runOnIdle { auth.pending.removeAt(0)(AuthResult.Success) }
        rule.onNodeWithTag("archive_content").assertIsDisplayed()
        assertEquals(1, unlocked)

        rule.runOnIdle { state = LockState.Initializing }
        rule.onNodeWithTag("archive_content").assertDoesNotExist()
        rule.onNodeWithTag(LockTestTags.INITIALIZING).assertExists()
    }

    @Test
    fun lockWithoutDeviceCredentialOffersSafeWayOut() {
        val auth = FakeLockAuthenticator(LockAvailability.NO_CREDENTIAL)
        var disabled = false
        rule.setContent {
            UnsendVaultTheme {
                CompositionLocalProvider(LocalLockAuthenticator provides auth) {
                    LockGate(LockState.Locked, onAuthenticated = {}, onDisableLock = { disabled = true }) {
                        Text("secret", Modifier.testTag("archive_content"))
                    }
                }
            }
        }
        rule.onNodeWithText(str(R.string.lock_no_credential_body)).assertIsDisplayed()
        rule.onNodeWithTag("archive_content").assertDoesNotExist()
        assertTrue(auth.pending.isEmpty())
        rule.onNodeWithText(str(R.string.lock_turn_off_and_continue)).performClick()
        assertTrue(disabled)
    }
}
