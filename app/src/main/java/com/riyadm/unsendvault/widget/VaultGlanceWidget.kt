package com.riyadm.unsendvault.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import com.riyadm.unsendvault.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.EnumMap

/**
 * Per-kind render counters. Glance's update() only recomposes an already-running session (it does not re-run
 * provideGlance), so widgets re-read their data when their counter changes.
 */
internal object WidgetRenderSignals {
    private val versions = EnumMap<WidgetKind, MutableStateFlow<Long>>(WidgetKind::class.java).apply {
        WidgetKind.entries.forEach { put(it, MutableStateFlow(0L)) }
    }

    fun flow(kind: WidgetKind): StateFlow<Long> = versions.getValue(kind)

    fun bump(kind: WidgetKind) = versions.getValue(kind).update { it + 1 }
}

/**
 * Base for all UnsendVault widgets: one bounded one-shot read before the first frame, a re-read whenever the sync
 * engine signals a change, a static error layout if composition fails, and synthetic-data generated previews.
 */
abstract class VaultGlanceWidget<T : Any>(
    private val kind: WidgetKind,
    sizes: Set<DpSize>,
) : GlanceAppWidget(errorUiLayout = R.layout.widget_error) {

    override val sizeMode: SizeMode = SizeMode.Responsive(sizes)

    protected abstract suspend fun load(context: Context): WidgetLoad<T>

    /** Synthetic data for generated previews (API 35+ widget picker). Never user data. */
    protected abstract fun previewData(): WidgetLoad<T>

    @Composable
    protected abstract fun Content(load: WidgetLoad<T>)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val signal = WidgetRenderSignals.flow(kind)
        val initialVersion = signal.value
        val initial = load(context)
        provideContent {
            val version by signal.collectAsState()
            var current by remember { mutableStateOf(initial) }
            LaunchedEffect(version) {
                if (version != initialVersion) current = load(context)
            }
            WidgetTheme { Content(current) }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val sample = previewData()
        provideContent { WidgetTheme { Content(sample) } }
    }
}
