package com.riyadm.unsendvault.ui.detail

import androidx.compose.ui.platform.LocalResources
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.core.model.ArchivedMessage
import com.riyadm.unsendvault.core.model.ContentKind
import com.riyadm.unsendvault.core.model.MessageDetail
import com.riyadm.unsendvault.core.model.MessageStatus
import com.riyadm.unsendvault.core.model.RemovalReason
import com.riyadm.unsendvault.ui.common.LocalExternalActivityGuard
import com.riyadm.unsendvault.ui.common.TimeFormatter
import com.riyadm.unsendvault.ui.common.rememberTimeFormatter
import com.riyadm.unsendvault.ui.components.AppAvatar
import com.riyadm.unsendvault.ui.components.AppScaffold
import com.riyadm.unsendvault.ui.components.ConfirmDialog
import com.riyadm.unsendvault.ui.components.EmptyState
import com.riyadm.unsendvault.ui.components.MessageBadges
import com.riyadm.unsendvault.ui.components.messageBodyText
import kotlinx.coroutines.launch

object DetailTestTags {
    const val TEXT = "detail_text"
    const val EVIDENCE = "detail_evidence"
    const val REMOVAL = "detail_removal"
    const val HISTORY = "detail_history"
    const val FAVORITE = "detail_favorite"
    const val KIND = "detail_kind"
}

@Composable
fun MessageDetailRoute(viewModel: MessageDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val guard = LocalExternalActivityGuard.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedText = stringResource(R.string.detail_copied)
    LaunchedEffect(viewModel) { viewModel.deleted.collect { onBack() } }
    MessageDetailScreen(
        state = state,
        snackbarHostState = snackbar,
        onBack = onBack,
        onToggleFavorite = viewModel::toggleFavorite,
        onCopy = { text ->
            copyToClipboard(context, resources.getString(R.string.detail_clip_label), text)
            // Android 13+ shows its own clipboard confirmation.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbar.showSnackbar(copiedText) }
        },
        onShare = { text ->
            guard()
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            runCatching { context.startActivity(Intent.createChooser(send, resources.getString(R.string.detail_share_title))) }
        },
        onDelete = viewModel::delete,
    )
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService<ClipboardManager>() ?: return
    val clip = ClipData.newPlainText(label, text)
    // Keep archived messages out of clipboard previews / keyboard suggestions.
    clip.description.extras = PersistableBundle().apply {
        putBoolean(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
    }
    clipboard.setPrimaryClip(clip)
}

@Composable
fun MessageDetailScreen(
    state: MessageDetailUiState,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onDelete: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
) {
    val formatter = rememberTimeFormatter()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val detail = state.detail
    AppScaffold(
        title = stringResource(R.string.detail_title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        actions = {
            if (detail != null) {
                val message = detail.message
                val body = messageBodyText(message)
                IconToggleButton(
                    checked = message.isFavorite,
                    onCheckedChange = { onToggleFavorite() },
                    modifier = Modifier.testTag(DetailTestTags.FAVORITE),
                ) {
                    Icon(
                        if (message.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = stringResource(R.string.cd_favorite),
                    )
                }
                IconButton(onClick = { onCopy(body) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                }
                IconButton(onClick = { onShare(body) }) {
                    Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share))
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.action_delete))
                }
            }
        },
    ) { padding ->
        when {
            state.loading -> Unit
            detail == null -> EmptyState(
                Icons.Outlined.Inbox,
                stringResource(R.string.detail_missing_title),
                stringResource(R.string.detail_missing_body),
                Modifier.padding(padding),
            )
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MessageDetailContent(detail, formatter, Modifier.widthIn(max = 720.dp).padding(16.dp))
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_message_title),
            body = stringResource(R.string.delete_messages_body),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun MessageDetailContent(detail: MessageDetail, formatter: TimeFormatter, modifier: Modifier = Modifier) {
    val message = detail.message
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Header: who / where.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
            AppAvatar(message.packageName, message.appLabel, size = 48.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(message.sender ?: message.conversationTitle, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(
                    if (message.sender != null && message.sender != message.conversationTitle) {
                        stringResource(R.string.detail_in_conversation, message.conversationTitle, message.appLabel)
                    } else {
                        message.appLabel
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        if (message.status == MessageStatus.POSSIBLY_DELETED) {
            val evidence = message.deletionEvidence
            InfoCard(
                icon = Icons.Outlined.EventBusy,
                title = stringResource(R.string.detail_possibly_deleted_title),
                container = colors.tertiaryContainer,
                content = colors.onTertiaryContainer,
                modifier = Modifier.testTag(DetailTestTags.EVIDENCE),
            ) {
                Text(
                    if (evidence != null) {
                        stringResource(R.string.detail_evidence, message.appLabel, evidence.markerText, formatter.dateTime(evidence.observedAt))
                    } else {
                        stringResource(R.string.detail_evidence_missing, message.appLabel)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.detail_evidence_explainer), style = MaterialTheme.typography.bodySmall)
            }
        }

        ContentKindCard(message)

        // The message itself.
        Card(colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer {
                    Text(
                        messageBodyText(message),
                        style = MaterialTheme.typography.bodyLarge,
                        fontStyle = if (message.contentKind == ContentKind.TEXT) FontStyle.Normal else FontStyle.Italic,
                        modifier = Modifier.testTag(DetailTestTags.TEXT),
                    )
                }
                MessageBadges(message)
            }
        }

        // Facts.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.detail_section_details))
            Fact(stringResource(R.string.detail_message_time), formatter.dateTime(message.messageTimestamp))
            Fact(stringResource(R.string.detail_captured_at), formatter.dateTime(message.firstCapturedAt))
            if (message.lastUpdatedAt != message.firstCapturedAt) {
                Fact(stringResource(R.string.detail_last_updated), formatter.dateTime(message.lastUpdatedAt))
            }
            Fact(stringResource(R.string.detail_conversation), message.conversationTitle)
            Fact(stringResource(R.string.detail_app), message.appLabel)
            Text(stringResource(R.string.detail_times_explainer), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }

        message.lastRemovalReason?.let { reason ->
            InfoCard(
                icon = Icons.Outlined.NotificationsOff,
                title = stringResource(R.string.detail_removal_title),
                container = colors.surfaceContainer,
                content = colors.onSurface,
                modifier = Modifier.testTag(DetailTestTags.REMOVAL),
            ) {
                Text(removalText(message, reason, formatter), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.detail_removal_not_deletion), style = MaterialTheme.typography.bodySmall)
            }
        }

        if (detail.revisions.isNotEmpty()) {
            Column(Modifier.testTag(DetailTestTags.HISTORY), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.detail_history_title))
                Text(stringResource(R.string.detail_history_explainer), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                RevisionRow(stringResource(R.string.detail_history_current), formatter.dateTime(message.lastUpdatedAt), messageBodyText(message), current = true)
                detail.revisions.sortedByDescending { it.capturedAt }.forEach { revision ->
                    HorizontalDivider()
                    RevisionRow(stringResource(R.string.detail_history_earlier), formatter.dateTime(revision.capturedAt), revision.text, current = false)
                }
            }
        }

        Text(stringResource(R.string.detail_preview_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun removalText(message: ArchivedMessage, reason: RemovalReason, formatter: TimeFormatter): String {
    val at = message.lastRemovedAt?.let { formatter.dateTime(it) } ?: stringResource(R.string.detail_unknown_time)
    return when (reason) {
        RemovalReason.APP_CANCELLED, RemovalReason.GROUP_SUMMARY_CANCELED -> stringResource(R.string.removal_app_cancelled, message.appLabel, at)
        RemovalReason.USER_DISMISSED -> stringResource(R.string.removal_user_dismissed, at)
        RemovalReason.CLICKED -> stringResource(R.string.removal_clicked, at)
        RemovalReason.TIMEOUT -> stringResource(R.string.removal_timeout, at)
        RemovalReason.CHANNEL_DISABLED, RemovalReason.PACKAGE_CHANGED, RemovalReason.USER_STOPPED,
        RemovalReason.LISTENER_CANCELLED, RemovalReason.OTHER, RemovalReason.UNKNOWN,
        -> stringResource(R.string.removal_other, at)
    }
}

@Composable
private fun ContentKindCard(message: ArchivedMessage) {
    val colors = MaterialTheme.colorScheme
    val (icon, title, body) = when (message.contentKind) {
        ContentKind.TEXT -> return
        ContentKind.MEDIA_PLACEHOLDER -> Triple(Icons.Outlined.Image, R.string.kind_media_title, R.string.kind_media_body)
        ContentKind.SYSTEM_REDACTED -> Triple(Icons.Outlined.VisibilityOff, R.string.kind_redacted_title, R.string.kind_redacted_body)
        ContentKind.FILTERED_SENSITIVE -> Triple(Icons.Outlined.Shield, R.string.kind_filtered_title, R.string.kind_filtered_body)
    }
    InfoCard(icon, stringResource(title), colors.surfaceContainer, colors.onSurface, Modifier.testTag(DetailTestTags.KIND)) {
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InfoCard(
    icon: ImageVector,
    title: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            }
            body()
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Fact(label: String, value: String) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun RevisionRow(label: String, time: String, text: String, current: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "$label · $time",
            style = MaterialTheme.typography.labelMedium,
            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
    }
}
