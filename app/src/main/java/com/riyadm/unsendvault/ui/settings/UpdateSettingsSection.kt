package com.riyadm.unsendvault.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Update
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.appContainer
import com.riyadm.unsendvault.ui.common.rememberTimeFormatter
import com.riyadm.unsendvault.ui.components.InfoText
import com.riyadm.unsendvault.ui.components.SectionHeader
import com.riyadm.unsendvault.ui.components.SwitchRow
import com.riyadm.unsendvault.ui.components.ValueRow
import com.riyadm.unsendvault.ui.update.UpdateTestTags
import com.riyadm.unsendvault.update.FailureReason
import com.riyadm.unsendvault.update.ManualCheckState
import com.riyadm.unsendvault.update.UpdateController
import com.riyadm.unsendvault.update.UpdateUiState

/** Settings > Updates. The update dialog itself is shown by the main scaffold's UpdatePrompts. */
@Composable
fun UpdateSettingsSection(controller: UpdateController = LocalContext.current.appContainer.updateController) {
    val state by controller.state.collectAsStateWithLifecycle()
    UpdateSettingsContent(
        state = state,
        onAutoCheckChange = controller::setAutomaticChecks,
        onCheckNow = controller::checkNow,
        onShowUpdate = controller::showAvailableUpdate,
    )
}

@Composable
fun UpdateSettingsContent(
    state: UpdateUiState,
    onAutoCheckChange: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
    onShowUpdate: () -> Unit,
) {
    val formatter = rememberTimeFormatter()
    val manual = state.manual
    SectionHeader(stringResource(R.string.settings_section_updates))
    SwitchRow(
        title = stringResource(R.string.update_auto_title),
        summary = stringResource(if (state.autoCheckDisabledInBuild) R.string.update_auto_summary_debug else R.string.update_auto_summary),
        checked = state.autoCheckEnabled,
        enabled = state.consent != null,
        onCheckedChange = onAutoCheckChange,
        icon = Icons.Outlined.Update,
        modifier = Modifier.testTag(UpdateTestTags.AUTO_SWITCH),
    )
    ValueRow(
        title = stringResource(if (manual is ManualCheckState.Available) R.string.update_show_update else R.string.update_check_now),
        value = manualCheckText(manual),
        icon = Icons.Outlined.SystemUpdate,
        enabled = manual != ManualCheckState.Checking,
        onClick = if (manual is ManualCheckState.Available) onShowUpdate else onCheckNow,
        modifier = Modifier
            .testTag(UpdateTestTags.CHECK_NOW)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
    InfoText(
        stringResource(
            R.string.update_version_info,
            state.currentVersion,
            state.lastCheckAt?.let(formatter::dateTime) ?: stringResource(R.string.update_never_checked),
        ),
    )
}

@Composable
private fun manualCheckText(manual: ManualCheckState): String = when (manual) {
    ManualCheckState.Idle -> stringResource(R.string.update_check_now_summary)
    ManualCheckState.Checking -> stringResource(R.string.update_checking)
    is ManualCheckState.UpToDate -> stringResource(R.string.update_result_up_to_date, manual.version)
    is ManualCheckState.Available -> stringResource(R.string.update_result_available, manual.release.version)
    is ManualCheckState.Failed -> stringResource(
        when (manual.reason) {
            FailureReason.RATE_LIMITED -> R.string.update_result_rate_limited
            FailureReason.INVALID_RESPONSE, FailureReason.TOO_LARGE -> R.string.update_result_invalid
            FailureReason.OFFLINE, FailureReason.TIMEOUT, FailureReason.HTTP_ERROR, FailureReason.NETWORK ->
                R.string.update_result_offline
        },
    )
}
