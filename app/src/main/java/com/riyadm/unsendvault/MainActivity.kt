package com.riyadm.unsendvault

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.security.lock.AppLockManager
import com.riyadm.unsendvault.security.lock.BiometricLockAuthenticator
import com.riyadm.unsendvault.security.lock.LocalLockAuthenticator
import com.riyadm.unsendvault.security.lock.LockGate
import com.riyadm.unsendvault.security.lock.LockState
import com.riyadm.unsendvault.ui.common.LocalExternalActivityGuard
import com.riyadm.unsendvault.ui.navigation.DeepLinks
import com.riyadm.unsendvault.ui.navigation.UnsendVaultAppUi
import com.riyadm.unsendvault.ui.theme.AppearanceSettings
import com.riyadm.unsendvault.ui.theme.UnsendVaultTheme
import com.riyadm.unsendvault.ui.theme.isDark
import kotlinx.coroutines.launch

/**
 * Single activity. FragmentActivity is required by androidx.biometric's BiometricPrompt.
 *
 * Privacy defaults until settings load: FLAG_SECURE on, lock gate in "initializing" (nothing from the archive composed).
 */
class MainActivity : FragmentActivity() {
    private lateinit var lockManager: AppLockManager
    private lateinit var authenticator: BiometricLockAuthenticator

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setSecure(true)
        val container = appContainer
        // Keeps the daily retention job in sync with the setting (the scheduler's watcher starts on first access).
        // Done here rather than in Application.onCreate so listener-only cold starts never open DataStore/WorkManager.
        container.appScope.launch { container.retentionScheduler }
        lockManager = AppLockManager.get(this)
        authenticator = BiometricLockAuthenticator(this)
        if (savedInstanceState == null) DeepLinks.offer(intent)

        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle<VaultSettings?>(initialValue = null)
            val lockState by lockManager.state.collectAsStateWithLifecycle()
            val widthSizeClass = calculateWindowSizeClass(this).widthSizeClass
            val scope = rememberCoroutineScope()
            val current = settings

            LaunchedEffect(current?.secureScreen) { setSecure(current?.secureScreen ?: true) }

            val appearance = current?.let { AppearanceSettings(it.themeMode, it.amoledBlack, it.dynamicColor) } ?: AppearanceSettings()
            val dark = appearance.isDark()
            LaunchedEffect(dark) { applySystemBarStyle(dark) }

            UnsendVaultTheme(appearance = appearance, dark = dark) {
                CompositionLocalProvider(
                    LocalLockAuthenticator provides authenticator,
                    LocalExternalActivityGuard provides lockManager::expectExternalActivity,
                ) {
                    LockGate(
                        state = if (current == null) LockState.Initializing else lockState,
                        onAuthenticated = lockManager::onAuthenticated,
                        onDisableLock = {
                            // Only offered when the device has no screen lock at all, so there is nothing to verify against.
                            scope.launch { container.settingsRepository.update { it.copy(appLockEnabled = false) } }
                        },
                    ) {
                        if (current != null) UnsendVaultAppUi(widthSizeClass, current, onLockNow = lockManager::lockNow)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLinks.offer(intent)
    }

    private fun setSecure(secure: Boolean) {
        if (secure) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /** Status/navigation bar icon contrast follows the in-app theme, which may differ from the system theme. */
    private fun applySystemBarStyle(dark: Boolean) {
        val status = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        val navigation = if (dark) SystemBarStyle.dark(DARK_SCRIM) else SystemBarStyle.light(LIGHT_SCRIM, DARK_SCRIM)
        enableEdgeToEdge(statusBarStyle = status, navigationBarStyle = navigation)
    }

    private companion object {
        // Same scrims androidx.activity uses for 3-button navigation on older releases.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
