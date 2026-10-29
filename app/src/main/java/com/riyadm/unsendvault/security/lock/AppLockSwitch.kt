package com.riyadm.unsendvault.security.lock

import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.ui.common.LocalExternalActivityGuard
import com.riyadm.unsendvault.ui.common.startFirstAvailable
import com.riyadm.unsendvault.ui.components.SwitchRow

/**
 * App-lock switch. Turning it on first checks that a screen lock exists and that the user can pass the prompt, so the
 * lock can never be enabled in a state that would lock the user out.
 */
@Composable
fun AppLockSwitchRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val authenticator = LocalLockAuthenticator.current
    val context = LocalContext.current
    val guard = LocalExternalActivityGuard.current
    var showNoCredential by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    SwitchRow(
        title = stringResource(R.string.settings_app_lock),
        summary = stringResource(R.string.settings_app_lock_summary),
        checked = enabled,
        icon = Icons.Outlined.Fingerprint,
        modifier = modifier,
        onCheckedChange = { turnOn ->
            if (!turnOn) {
                onEnabledChange(false)
            } else if (authenticator.availability() == LockAvailability.NO_CREDENTIAL) {
                showNoCredential = true
            } else {
                authenticator.authenticate { result ->
                    when (result) {
                        AuthResult.Success -> onEnabledChange(true)
                        AuthResult.NoCredential -> showNoCredential = true
                        AuthResult.Cancelled -> Unit
                        is AuthResult.Error -> error = result.message
                    }
                }
            }
        },
    )

    if (showNoCredential) {
        AlertDialog(
            onDismissRequest = { showNoCredential = false },
            title = { Text(stringResource(R.string.lock_no_credential_title)) },
            text = { Text(stringResource(R.string.lock_no_credential_enable_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showNoCredential = false
                    guard()
                    context.startFirstAvailable(Intent(Settings.ACTION_SECURITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
                }) { Text(stringResource(R.string.lock_open_security_settings)) }
            },
            dismissButton = { TextButton(onClick = { showNoCredential = false }) { Text(stringResource(R.string.action_not_now)) } },
        )
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text(stringResource(R.string.lock_error_title)) },
            text = { Text(message.ifBlank { stringResource(R.string.lock_error_generic) }) },
            confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}
