package com.riyadm.unsendvault.widget

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.data.SettingsRepository

/** Shows whether capture is running and toggles it. Without notification access, tapping opens the app instead. */
class CaptureControlWidget : VaultGlanceWidget<CaptureState>(WidgetKind.CAPTURE, SIZES) {
    override suspend fun load(context: Context): WidgetLoad<CaptureState> =
        WidgetLoaders.containerOrNull(context)?.let { WidgetLoaders.capture(it.settingsRepository, it.listenerStatus) }
            ?: WidgetLoad.Failed

    override fun previewData(): WidgetLoad<CaptureState> = WidgetSamples.capture

    @Composable
    override fun Content(load: WidgetLoad<CaptureState>) = CaptureControlContent(load)

    companion object {
        val SIZES = setOf(DpSize(110.dp, 40.dp), DpSize(180.dp, 40.dp))
    }
}

private class CaptureSpec(
    @DrawableRes val icon: Int,
    @StringRes val state: Int,
    @StringRes val action: Int,
    @StringRes val accessibility: Int,
    val onClick: Action,
)

private fun specFor(state: CaptureState): CaptureSpec = when (state) {
    CaptureState.ACTIVE -> CaptureSpec(
        R.drawable.widget_ic_vault, R.string.widget_capture_active, R.string.widget_capture_action_pause,
        R.string.widget_capture_a11y_active, CaptureActions.toggle(targetEnabled = false),
    )
    CaptureState.PAUSED -> CaptureSpec(
        R.drawable.widget_ic_pause, R.string.widget_capture_paused, R.string.widget_capture_action_resume,
        R.string.widget_capture_a11y_paused, CaptureActions.toggle(targetEnabled = true),
    )
    CaptureState.ACCESS_NEEDED -> CaptureSpec(
        R.drawable.widget_ic_warning, R.string.widget_capture_access_needed, R.string.widget_capture_action_grant,
        R.string.widget_capture_a11y_access, WidgetIntents.openDestination(WidgetIntents.DESTINATION_NOTIFICATION_ACCESS),
    )
    CaptureState.DISCONNECTED -> CaptureSpec(
        R.drawable.widget_ic_warning, R.string.widget_capture_disconnected, R.string.widget_capture_action_reconnect,
        R.string.widget_capture_a11y_disconnected, actionRunCallback<RebindListenerAction>(),
    )
}

@Composable
fun CaptureControlContent(load: WidgetLoad<CaptureState>) {
    if (load !is WidgetLoad.Ready) {
        FailedContent(compact = true)
        return
    }
    val spec = specFor(load.value)
    val palette = widgetPalette()
    val wide = LocalSize.current.width >= 180.dp

    WidgetRoot(onClick = spec.onClick, contentDescription = str(spec.accessibility)) {
        Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = chipModifier(palette).size(32.dp), contentAlignment = Alignment.Center) {
                WidgetIcon(spec.icon, null, palette.onAccentContainer, 18.dp)
            }
            Spacer(GlanceModifier.width(8.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = str(R.string.widget_capture_title),
                    style = TextStyle(color = palette.onBackgroundVariant, fontSize = 12.sp),
                    maxLines = 1,
                )
                Text(
                    text = str(spec.state),
                    style = TextStyle(color = palette.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    maxLines = 2,
                )
            }
            if (wide) {
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    text = str(spec.action),
                    modifier = chipModifier(palette).padding(horizontal = 10.dp, vertical = 6.dp),
                    style = TextStyle(color = palette.onAccentContainer, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
        }
    }
}

private fun chipModifier(palette: WidgetPalette): GlanceModifier =
    GlanceModifier.roundedBackground(palette.accentContainer, R.dimen.widget_inner_radius, R.drawable.widget_chip_background)

object CaptureActions {
    /** Explicit target state, so a double tap on a stale widget can't flip capture twice. */
    val TargetEnabledKey = ActionParameters.Key<Boolean>("com.riyadm.unsendvault.widget.CAPTURE_TARGET_ENABLED")

    fun toggle(targetEnabled: Boolean): Action =
        actionRunCallback<ToggleCaptureAction>(actionParametersOf(TargetEnabledKey to targetEnabled))

    /**
     * Applies a toggle. Returns the new captureEnabled value, or null (no change) when notification access is missing:
     * the widget then re-renders into its "access needed" state, whose tap opens the app.
     */
    suspend fun apply(settings: SettingsRepository, accessGranted: Boolean, targetEnabled: Boolean?): Boolean? {
        if (!accessGranted) return null
        var result = false
        settings.update { current ->
            current.copy(captureEnabled = targetEnabled ?: !current.captureEnabled).also { result = it.captureEnabled }
        }
        return result
    }
}

/** Pauses/resumes capture via settingsRepository.update. */
class ToggleCaptureAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val container = WidgetLoaders.containerOrNull(context) ?: return
        CaptureActions.apply(container.settingsRepository, container.listenerStatus.isAccessGranted(), parameters[CaptureActions.TargetEnabledKey])
        // Immediate feedback; the sync engine would otherwise only catch up after its debounce.
        WidgetRegistry.render(context, WidgetKind.CAPTURE)
    }
}

/** Asks the system to rebind the notification listener (e.g. after an OEM task killer unbound it). */
class RebindListenerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val container = WidgetLoaders.containerOrNull(context) ?: return
        container.listenerStatus.requestRebind()
        WidgetRegistry.render(context, WidgetKind.CAPTURE)
    }
}
