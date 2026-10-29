package com.riyadm.unsendvault.security.lock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.fragment.app.FragmentActivity
import com.riyadm.unsendvault.R

enum class LockAvailability {
    /** A screen lock (PIN, pattern, password) is set; biometrics may additionally be used. */
    AVAILABLE,

    /** No screen lock is set up: the app lock cannot verify anyone and must not be enabled. */
    NO_CREDENTIAL,
}

sealed interface AuthResult {
    data object Success : AuthResult
    data object Cancelled : AuthResult
    data object NoCredential : AuthResult
    data class Error(val message: String) : AuthResult
}

/** Verifies the device owner. Implemented with BiometricPrompt / KeyguardManager; faked in tests. */
interface LockAuthenticator {
    fun availability(): LockAvailability
    fun authenticate(onResult: (AuthResult) -> Unit)
}

/** Default for previews/tests: always available, always succeeds. */
object NoOpLockAuthenticator : LockAuthenticator {
    override fun availability() = LockAvailability.AVAILABLE
    override fun authenticate(onResult: (AuthResult) -> Unit) = onResult(AuthResult.Success)
}

val LocalLockAuthenticator = staticCompositionLocalOf<LockAuthenticator> { NoOpLockAuthenticator }

/**
 * Activity-bound authenticator. Must be constructed in `onCreate` (it registers an activity-result launcher and the
 * BiometricPrompt callback before the activity is started).
 *
 * - API 30+: BIOMETRIC_STRONG or DEVICE_CREDENTIAL.
 * - API 28-29: BIOMETRIC_WEAK or DEVICE_CREDENTIAL (strong + credential is not supported there).
 * - API 26-27: KeyguardManager confirm-credential screen.
 */
class BiometricLockAuthenticator(private val activity: FragmentActivity) : LockAuthenticator {
    private var pending: ((AuthResult) -> Unit)? = null

    private val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = deliver(AuthResult.Success)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = deliver(
                when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED,
                    -> AuthResult.Cancelled
                    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> AuthResult.NoCredential
                    else -> AuthResult.Error(errString.toString())
                },
            )
            // onAuthenticationFailed: a single rejected attempt; the system prompt stays open, nothing to do.
        },
    )

    private val credentialLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            deliver(if (result.resultCode == Activity.RESULT_OK) AuthResult.Success else AuthResult.Cancelled)
        }

    private val keyguard: KeyguardManager? get() = activity.getSystemService()

    override fun availability(): LockAvailability =
        if (keyguard?.isDeviceSecure == true) LockAvailability.AVAILABLE else LockAvailability.NO_CREDENTIAL

    override fun authenticate(onResult: (AuthResult) -> Unit) {
        if (availability() == LockAvailability.NO_CREDENTIAL) {
            onResult(AuthResult.NoCredential)
            return
        }
        pending = onResult
        val title = activity.getString(R.string.lock_prompt_title)
        val subtitle = activity.getString(R.string.lock_prompt_subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                BIOMETRIC_STRONG or DEVICE_CREDENTIAL
            } else {
                BIOMETRIC_WEAK or DEVICE_CREDENTIAL
            }
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(authenticators)
                .setConfirmationRequired(false)
                .build()
            runCatching { prompt.authenticate(info) }.onFailure { deliver(AuthResult.Error(it.message.orEmpty())) }
        } else {
            @Suppress("DEPRECATION")
            val intent = keyguard?.createConfirmDeviceCredentialIntent(title, subtitle)
            if (intent == null) deliver(AuthResult.NoCredential) else credentialLauncher.launch(intent)
        }
    }

    private fun deliver(result: AuthResult) {
        val callback = pending
        pending = null
        callback?.invoke(result)
    }
}
