package com.riyadm.unsendvault.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.data.security.VaultHealth
import com.riyadm.unsendvault.data.security.VaultKeyState
import kotlinx.coroutines.launch

/**
 * Shown above every screen when the encrypted archive cannot be opened (Keystore key lost, corrupt key file, or a
 * transient Keystore error). Reset is destructive and always confirmed.
 */
@Composable
fun VaultHealthBanner(health: VaultHealth?, modifier: Modifier = Modifier) {
    if (health == null) return
    val state by health.keyState.collectAsStateWithLifecycle()
    val unavailable = state as? VaultKeyState.Unavailable ?: return
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.vault_unavailable_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(if (unavailable.retryable) R.string.vault_unavailable_transient else R.string.vault_unavailable_lost),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (unavailable.retryable) {
                    TextButton(onClick = { scope.launch { health.retryOpen() } }) { Text(stringResource(R.string.vault_retry)) }
                }
                TextButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.vault_reset)) }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.vault_reset_confirm_title)) },
            text = { Text(stringResource(R.string.vault_reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch { health.resetVault() }
                }) { Text(stringResource(R.string.vault_reset_confirm_action)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
