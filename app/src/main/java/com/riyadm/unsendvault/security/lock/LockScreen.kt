package com.riyadm.unsendvault.security.lock

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.riyadm.unsendvault.R

/**
 * Renders [content] only while unlocked. While locked or initializing, the archive UI is not composed at all, so no
 * message text can appear in the view hierarchy, accessibility tree or a Recents thumbnail.
 */
@Composable
fun LockGate(
    state: LockState,
    onAuthenticated: () -> Unit,
    onDisableLock: () -> Unit,
    content: @Composable () -> Unit,
) {
    when (state) {
        LockState.Initializing -> Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .testTag(LockTestTags.INITIALIZING),
        )
        LockState.Locked -> LockScreen(onAuthenticated = onAuthenticated, onDisableLock = onDisableLock)
        LockState.Unlocked -> content()
    }
}

object LockTestTags {
    const val SCREEN = "lock_screen"
    const val INITIALIZING = "lock_initializing"
    const val UNLOCK = "lock_unlock"
}

@Composable
fun LockScreen(
    onAuthenticated: () -> Unit,
    onDisableLock: () -> Unit,
    autoPrompt: Boolean = true,
) {
    val authenticator = LocalLockAuthenticator.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var noCredential by rememberSaveable { mutableStateOf(authenticator.availability() == LockAvailability.NO_CREDENTIAL) }
    var autoPrompted by rememberSaveable { mutableStateOf(false) }
    val errorFallback = stringResource(R.string.lock_error_generic)

    val unlock: () -> Unit = {
        authenticator.authenticate { result ->
            when (result) {
                AuthResult.Success -> {
                    message = null
                    onAuthenticated()
                }
                AuthResult.Cancelled -> message = null
                AuthResult.NoCredential -> noCredential = true
                is AuthResult.Error -> message = result.message.ifBlank { errorFallback }
            }
        }
    }

    // Prompt once automatically, but only while resumed (BiometricPrompt must not be shown after onSaveInstanceState).
    LaunchedEffect(autoPrompt) {
        if (!autoPrompt) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!autoPrompted && !noCredential) {
                autoPrompted = true
                unlock()
            }
        }
    }

    Surface(Modifier.fillMaxSize().testTag(LockTestTags.SCREEN), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(88.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                Text(
                    stringResource(R.string.lock_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                if (noCredential) {
                    Text(
                        stringResource(R.string.lock_no_credential_body),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                    }) { Text(stringResource(R.string.lock_open_security_settings)) }
                    OutlinedButton(onClick = onDisableLock) { Text(stringResource(R.string.lock_turn_off_and_continue)) }
                    TextButton(onClick = {
                        noCredential = authenticator.availability() == LockAvailability.NO_CREDENTIAL
                        if (!noCredential) unlock()
                    }) { Text(stringResource(R.string.lock_check_again)) }
                } else {
                    Text(
                        stringResource(R.string.lock_body),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    message?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = unlock, modifier = Modifier.testTag(LockTestTags.UNLOCK)) {
                        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.lock_unlock))
                    }
                }
            }
        }
    }
}
