package com.riyadm.unsendvault.ui.update

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.appContainer
import com.riyadm.unsendvault.ui.common.LocalExternalActivityGuard
import com.riyadm.unsendvault.ui.common.startFirstAvailable
import com.riyadm.unsendvault.update.ReleaseInfo
import com.riyadm.unsendvault.update.ReleaseNotes
import com.riyadm.unsendvault.update.UpdateController

object UpdateTestTags {
    const val CONSENT_DIALOG = "update_consent_dialog"
    const val CONSENT_ALLOW = "update_consent_allow"
    const val CONSENT_DECLINE = "update_consent_decline"
    const val UPDATE_DIALOG = "update_dialog"
    const val DOWNLOAD = "update_download"
    const val RELEASE_PAGE = "update_release_page"
    const val LATER = "update_later"
    const val SKIP = "update_skip"
    const val AUTO_SWITCH = "update_auto_switch"
    const val CHECK_NOW = "update_check_now"
}

/**
 * Main-screen hook: composed only inside the unlocked main scaffold (after onboarding), so nothing here can appear on
 * the lock screen or during onboarding. Starts the once-per-process automatic check and shows the consent / update
 * dialogs.
 */
@Composable
fun UpdatePrompts(controller: UpdateController = LocalContext.current.appContainer.updateController) {
    LaunchedEffect(controller) { controller.onMainUiVisible() }
    val state by controller.state.collectAsStateWithLifecycle()
    val prompt = state.prompt
    when {
        state.showConsentDialog -> UpdateConsentDialog(
            onAllow = controller::allowAutomaticChecks,
            onNotNow = controller::declineAutomaticChecks,
        )
        prompt != null -> UpdateAvailableDialog(
            release = prompt,
            currentVersion = state.currentVersion,
            onLater = controller::dismissForNow,
            onSkip = controller::skipVersion,
        )
    }
}

@Composable
fun UpdateConsentDialog(onAllow: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        // Back counts as "Not now" so the question is asked only once; Settings can turn it on later.
        onDismissRequest = onNotNow,
        properties = DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.testTag(UpdateTestTags.CONSENT_DIALOG),
        title = { Text(stringResource(R.string.update_consent_title)) },
        text = {
            Text(
                stringResource(R.string.update_consent_body),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onAllow, modifier = Modifier.testTag(UpdateTestTags.CONSENT_ALLOW)) {
                Text(stringResource(R.string.update_consent_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onNotNow, modifier = Modifier.testTag(UpdateTestTags.CONSENT_DECLINE)) {
                Text(stringResource(R.string.update_consent_not_now))
            }
        },
    )
}

@Composable
fun UpdateAvailableDialog(
    release: ReleaseInfo,
    currentVersion: String,
    onLater: () -> Unit,
    onSkip: () -> Unit,
    openUrl: (String, String?) -> Unit = rememberUrlOpener(),
) {
    val title = stringResource(R.string.update_available_title, release.version)
    val notes = remember(release.notes) { ReleaseNotes.excerpt(release.notes) }
    BasicAlertDialog(onDismissRequest = onLater, modifier = Modifier.testTag(UpdateTestTags.UPDATE_DIALOG)) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = Modifier.semantics { paneTitle = title },
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(stringResource(R.string.update_current_version, currentVersion), style = MaterialTheme.typography.bodyMedium)
                    if (notes.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.update_notes_heading),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.semantics { heading() },
                        )
                        Spacer(Modifier.height(4.dp))
                        // Plain Text only: release notes are never rendered as HTML or markdown.
                        Text(notes, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(if (release.apkUrl != null) R.string.update_verify_hint else R.string.update_no_apk_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
                ) {
                    TextButton(onClick = onSkip, modifier = Modifier.testTag(UpdateTestTags.SKIP)) {
                        Text(stringResource(R.string.update_action_skip))
                    }
                    TextButton(onClick = onLater, modifier = Modifier.testTag(UpdateTestTags.LATER)) {
                        Text(stringResource(R.string.update_action_later))
                    }
                    TextButton(
                        onClick = { openUrl(release.pageUrl, null) },
                        modifier = Modifier.testTag(UpdateTestTags.RELEASE_PAGE),
                    ) { Text(stringResource(R.string.update_action_release_page)) }
                    release.apkUrl?.let { apk ->
                        Button(
                            onClick = { openUrl(apk, release.pageUrl) },
                            modifier = Modifier.testTag(UpdateTestTags.DOWNLOAD),
                        ) { Text(stringResource(R.string.update_action_download)) }
                    }
                }
            }
        }
    }
}

/**
 * Opens a release link in the user's browser. Calls the app-lock guard first so returning from the browser does not
 * immediately demand authentication. [fallback] is tried when nothing handles [url].
 */
@Composable
fun rememberUrlOpener(): (String, String?) -> Unit {
    val context = LocalContext.current
    val guard = LocalExternalActivityGuard.current
    return remember(context, guard) {
        { url, fallback ->
            guard()
            val intents = listOfNotNull(url, fallback).map(::viewIntent)
            if (!context.startFirstAvailable(*intents.toTypedArray())) context.toastNoBrowser()
        }
    }
}

private fun viewIntent(url: String): Intent =
    Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)

private fun Context.toastNoBrowser() {
    Toast.makeText(this, R.string.update_open_failed, Toast.LENGTH_SHORT).show()
}
