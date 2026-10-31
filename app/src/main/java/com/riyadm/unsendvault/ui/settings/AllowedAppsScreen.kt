package com.riyadm.unsendvault.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.ui.components.AppAvatar
import com.riyadm.unsendvault.ui.components.AppScaffold
import com.riyadm.unsendvault.ui.components.InfoText

/**
 * Picker for the "Only selected apps" capture mode: the apps installed on this phone (same launcher-app query as the
 * excluded-apps screen, so no extra permission), with known chat apps listed first. Ticked apps are captured.
 */
@Composable
fun AllowedAppsRoute(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(viewModel) { viewModel.loadLauncherApps() }

    AppScaffold(title = stringResource(R.string.settings_allowed_apps), onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            InfoText(stringResource(R.string.allowed_apps_explainer), Modifier.padding(top = 8.dp))
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.exclusions_filter_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.extraLarge,
            )
            if (state.launcherApps == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            val candidates = state.allowedCandidates.let { all ->
                val needle = filter.trim()
                if (needle.isEmpty()) all else all.filter { it.label.contains(needle, true) || it.packageName.contains(needle, true) }
            }
            LazyColumn(Modifier.fillMaxSize().testTag("allowed_apps_list")) {
                items(candidates, key = { it.packageName }, contentType = { "app" }) { candidate ->
                    ListItem(
                        headlineContent = { Text(candidate.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                if (candidate.knownChatApp) stringResource(R.string.allowed_apps_chat_app) else candidate.packageName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = { AppAvatar(candidate.packageName, candidate.label) },
                        trailingContent = { Switch(checked = candidate.allowed, onCheckedChange = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .heightIn(min = 56.dp)
                            .toggleable(value = candidate.allowed, role = Role.Switch) { viewModel.setAllowed(candidate.packageName, it) },
                    )
                }
            }
        }
    }
}
