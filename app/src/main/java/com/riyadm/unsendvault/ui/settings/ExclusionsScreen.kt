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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.apps.ProfileKind
import com.riyadm.unsendvault.ui.components.AppAvatar
import com.riyadm.unsendvault.ui.components.AppScaffold
import com.riyadm.unsendvault.ui.components.ConfirmDialog
import com.riyadm.unsendvault.ui.components.InfoText

/** Which list the shared app-list screen edits. */
private enum class AppListMode { EXCLUDE, SELECT }

/** One row of either list: [checked] is "excluded" or "selected" depending on the mode. */
@Immutable
private data class AppRow(
    val packageName: String,
    val label: String,
    val archivedMessages: Int,
    val checked: Boolean,
    val profile: ProfileKind,
    val alsoIn: Set<ProfileKind>,
    val isNew: Boolean,
    val onToggle: (Boolean) -> Unit,
)

@Composable
fun ExclusionsRoute(viewModel: SettingsViewModel, onBack: () -> Unit) = AppListRoute(viewModel, AppListMode.EXCLUDE, onBack)

/** The apps saved in "only selected apps" mode. Same screen as the excluded apps, with a different list behind it. */
@Composable
fun SelectedAppsRoute(viewModel: SettingsViewModel, onBack: () -> Unit) = AppListRoute(viewModel, AppListMode.SELECT, onBack)

@Composable
private fun AppListRoute(viewModel: SettingsViewModel, mode: AppListMode, onBack: () -> Unit) {
    val select = mode == AppListMode.SELECT
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var offer by remember { mutableStateOf<SettingsEvent.OfferDeleteAppData?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(viewModel) {
        viewModel.loadLauncherApps()
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.OfferDeleteAppData -> offer = event
                is SettingsEvent.AppDataDeleted -> snackbar.showSnackbar(resources.getString(R.string.exclusions_data_deleted, event.label))
                else -> Unit
            }
        }
    }

    val checkedLabel = stringResource(if (select) R.string.selected_apps_state_selected else R.string.exclusions_state_excluded)
    val uncheckedLabel = stringResource(if (select) R.string.selected_apps_state_not_selected else R.string.exclusions_state_captured)
    AppScaffold(
        title = stringResource(if (select) R.string.settings_selected_apps else R.string.settings_excluded_apps),
        onBack = onBack,
        snackbarHostState = snackbar,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            InfoText(stringResource(if (select) R.string.selected_apps_explainer else R.string.exclusions_explainer), Modifier.padding(top = 8.dp))
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
            if (state.launcherApps.orEmpty().any { it.profile != ProfileKind.PERSONAL || it.alsoIn.isNotEmpty() }) {
                InfoText(stringResource(if (select) R.string.selected_apps_profiles_note else R.string.exclusions_profiles_note))
            }
            // The selected list sorts "New" and ticked apps first, which only works once every app is known: showing a
            // partial list and re-sorting it later made the list open scrolled down, with the first rows hidden above.
            val ready = !select || (state.launcherApps != null && state.settings != null)
            if (ready) {
                // Apps that were ticked when the list opened go first. A snapshot, so rows do not jump while ticking.
                val tickedOnOpen = remember { if (select) state.selectionCandidates.filter { it.selected }.map { it.packageName }.toSet() else emptySet() }
                val rows: List<AppRow> = if (select) {
                    state.selectionCandidates
                        .sortedWith(compareByDescending<SelectionCandidate> { it.isNew }.thenByDescending { it.packageName in tickedOnOpen })
                        .map { c ->
                            AppRow(c.packageName, c.label, c.archivedMessages, c.selected, c.profile, c.alsoIn, c.isNew) { viewModel.setAllowed(c.packageName, it) }
                        }
                } else {
                    state.exclusionCandidates.map { c ->
                        AppRow(c.packageName, c.label, c.archivedMessages, c.excluded, c.profile, c.alsoIn, false) { viewModel.setExcluded(c, it) }
                    }
                }
                val needle = filter.trim()
                val visible = if (needle.isEmpty()) rows else rows.filter { it.label.contains(needle, true) || it.packageName.contains(needle, true) }
                LazyColumn(Modifier.fillMaxSize().testTag(if (select) "selected_apps_list" else "exclusions_list")) {
                    items(visible, key = { it.packageName }, contentType = { "app" }) { row ->
                        ListItem(
                            headlineContent = { Text(row.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                val detail = when {
                                    row.isNew -> stringResource(R.string.selected_apps_new)
                                    row.archivedMessages > 0 ->
                                        pluralStringResource(R.plurals.stats_messages, row.archivedMessages, row.archivedMessages)
                                    else -> row.packageName
                                }
                                val where = profileText(row.profile, row.alsoIn)
                                Text(
                                    if (where == null) detail else stringResource(R.string.profile_separator, where, detail),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = { AppAvatar(row.packageName, row.label) },
                            // key(): a recycled row would otherwise animate its switch from the previous row's state, so
                            // a ticked row seemed to tick again while scrolling back to it.
                            trailingContent = { key(row.packageName) { Switch(checked = row.checked, onCheckedChange = null) } },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier
                                .heightIn(min = 56.dp)
                                .toggleable(value = row.checked, role = Role.Switch, onValueChange = row.onToggle)
                                .semantics { stateDescription = if (row.checked) checkedLabel else uncheckedLabel },
                        )
                    }
                }
            }
        }
    }

    offer?.let { event ->
        ConfirmDialog(
            title = stringResource(R.string.exclusions_delete_title, event.label),
            body = pluralStringResource(R.plurals.exclusions_delete_body, event.messageCount, event.messageCount, event.label),
            confirmLabel = stringResource(R.string.action_delete),
            dismissLabel = stringResource(R.string.action_keep),
            destructive = true,
            onConfirm = {
                viewModel.deleteAppData(event.packageName, event.label)
                offer = null
            },
            onDismiss = { offer = null },
        )
    }
}

/** "Work profile" for an app only there, "Also in work profile" for one in both; null for personal-only apps. */
@Composable
private fun profileText(profile: ProfileKind, alsoIn: Set<ProfileKind>): String? = when {
    profile != ProfileKind.PERSONAL -> stringResource(
        when (profile) {
            ProfileKind.WORK -> R.string.profile_work
            ProfileKind.CLONE -> R.string.profile_clone
            ProfileKind.PRIVATE -> R.string.profile_private
            else -> R.string.profile_other
        },
    )
    alsoIn.isNotEmpty() -> stringResource(
        when {
            ProfileKind.WORK in alsoIn -> R.string.profile_also_work
            ProfileKind.CLONE in alsoIn -> R.string.profile_also_clone
            ProfileKind.PRIVATE in alsoIn -> R.string.profile_also_private
            else -> R.string.profile_also_other
        },
    )
    else -> null
}
