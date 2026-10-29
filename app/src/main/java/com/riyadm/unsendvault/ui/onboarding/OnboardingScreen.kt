package com.riyadm.unsendvault.ui.onboarding

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.security.lock.AppLockSwitchRow
import com.riyadm.unsendvault.ui.common.LocalExternalActivityGuard
import com.riyadm.unsendvault.ui.common.appDetailsIntent
import com.riyadm.unsendvault.ui.common.rememberReducedMotion
import com.riyadm.unsendvault.ui.common.startFirstAvailable

object OnboardingTestTags {
    const val NEXT = "onboarding_next"
    const val BACK = "onboarding_back"
    const val GRANT = "onboarding_grant"
    const val FINISH = "onboarding_finish"
}

@Composable
fun OnboardingRoute(viewModel: OnboardingViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val guard = LocalExternalActivityGuard.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshAccess() }
    OnboardingScreen(
        state = state,
        onNext = viewModel::next,
        onBack = viewModel::back,
        onGrantAccess = {
            guard()
            context.startFirstAvailable(
                viewModel.accessSettingsIntent(),
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                context.appDetailsIntent(),
            )
        },
        onAppLockChange = viewModel::setAppLockEnabled,
        onFinish = viewModel::finish,
    )
}

@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onGrantAccess: () -> Unit,
    onAppLockChange: (Boolean) -> Unit,
    onFinish: () -> Unit,
) {
    val reducedMotion = rememberReducedMotion()
    val total = OnboardingStep.entries.size
    val position = state.step.ordinal + 1
    val progressLabel = stringResource(R.string.onboarding_progress, position, total)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            LinearProgressIndicator(
                progress = { position / total.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .semantics { contentDescription = progressLabel },
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                AnimatedContent(
                    targetState = state.step,
                    transitionSpec = {
                        if (reducedMotion) EnterTransition.None togetherWith ExitTransition.None else fadeIn() togetherWith fadeOut()
                    },
                    label = "onboarding-step",
                ) { step ->
                    Column(
                        Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        when (step) {
                            OnboardingStep.WELCOME -> StepContent(
                                Icons.Outlined.Inbox, stringResource(R.string.onboarding_welcome_title),
                                stringResource(R.string.onboarding_welcome_body),
                                stringArrayResource(R.array.onboarding_welcome_points).toList(),
                            )
                            OnboardingStep.LIMITS -> StepContent(
                                Icons.Outlined.Info, stringResource(R.string.onboarding_limits_title),
                                stringResource(R.string.onboarding_limits_body),
                                stringArrayResource(R.array.onboarding_limits_points).toList(),
                            )
                            OnboardingStep.PRIVACY -> StepContent(
                                Icons.Outlined.PrivacyTip, stringResource(R.string.onboarding_privacy_title),
                                stringResource(R.string.onboarding_privacy_body),
                                stringArrayResource(R.array.onboarding_privacy_points).toList(),
                            )
                            OnboardingStep.ACCESS -> {
                                StepContent(
                                    Icons.Outlined.NotificationsActive, stringResource(R.string.onboarding_access_title),
                                    stringResource(R.string.onboarding_access_body),
                                    stringArrayResource(R.array.onboarding_access_points).toList(),
                                )
                                AccessCard(state.accessGranted, onGrantAccess)
                            }
                            OnboardingStep.LOCK -> {
                                StepContent(
                                    Icons.Outlined.Fingerprint, stringResource(R.string.onboarding_lock_title),
                                    stringResource(R.string.onboarding_lock_body), emptyList(),
                                )
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                    AppLockSwitchRow(enabled = state.appLockEnabled, onEnabledChange = onAppLockChange)
                                }
                                Text(
                                    stringResource(R.string.onboarding_lock_later),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!state.isFirst) {
                    TextButton(onClick = onBack, modifier = Modifier.testTag(OnboardingTestTags.BACK)) {
                        Text(stringResource(R.string.action_back))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (state.isLast) {
                    Button(onClick = onFinish, modifier = Modifier.testTag(OnboardingTestTags.FINISH)) {
                        Text(stringResource(R.string.onboarding_finish))
                    }
                } else {
                    val label = if (state.step == OnboardingStep.ACCESS && !state.accessGranted) {
                        R.string.onboarding_skip_for_now
                    } else {
                        R.string.action_continue
                    }
                    Button(onClick = onNext, modifier = Modifier.testTag(OnboardingTestTags.NEXT)) { Text(stringResource(label)) }
                }
            }
        }
    }
}

@Composable
private fun StepContent(icon: ImageVector, title: String, body: String, points: List<String>) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
        }
    }
    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    Text(body, style = MaterialTheme.typography.bodyLarge)
    points.forEach { point ->
        Row(verticalAlignment = Alignment.Top) {
            Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(point, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun AccessCard(granted: Boolean, onGrant: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (granted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                Icon(
                    if (granted) Icons.Filled.CheckCircle else Icons.Outlined.NotificationsActive,
                    contentDescription = null,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(if (granted) R.string.onboarding_access_granted else R.string.onboarding_access_not_granted),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (granted) {
                OutlinedButton(onClick = onGrant) { Text(stringResource(R.string.onboarding_access_review)) }
            } else {
                Button(onClick = onGrant, modifier = Modifier.testTag(OnboardingTestTags.GRANT)) {
                    Text(stringResource(R.string.action_grant_notification_access))
                }
            }
        }
    }
}
