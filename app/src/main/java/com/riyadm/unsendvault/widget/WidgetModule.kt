package com.riyadm.unsendvault.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import com.riyadm.unsendvault.BuildConfig
import com.riyadm.unsendvault.data.WidgetRefresher
import com.riyadm.unsendvault.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.EnumSet

/** Entry points used by the rest of the app (AppContainer / UnsendVaultApp). */
object WidgetModule {
    @Volatile
    private var engine: WidgetSyncEngine? = null

    /** Debounced and thread-safe: only marks every placed widget dirty; rendering happens on the sync engine. */
    fun createRefresher(context: Context): WidgetRefresher = WidgetRefresher { engine?.requestRefresh() }

    /**
     * Process-start entry point. Starts [startSync] only when at least one widget is placed (checked off the main
     * thread with plain AppWidgetManager lookups by class *name*, so neither Glance nor the receivers are class-loaded
     * and no DataStore/database is touched otherwise). A widget placed later starts sync through its receiver
     * ([onPlacementChanged]). Also schedules the one-time picker-preview publication (API 35+), which is independent of
     * placement.
     */
    fun startSyncIfPlaced(container: AppContainer) {
        container.appScope.launch {
            val anyPlaced = try {
                WidgetRegistry.anyPlaced(container.context)
            } catch (e: Exception) {
                true // can't tell: fall back to the previous always-on behaviour (it observes nothing when none are placed)
            }
            if (anyPlaced) startSync(container)
        }
        WidgetPreviewPublisher.publishIfNeeded(container.context, container.appScope)
    }

    /**
     * Starts the event-driven (never polling) sync that refreshes widgets when archive data, privacy settings, capture
     * state or listener connection change. Idempotent. Nothing is observed while no widget is placed.
     */
    fun startSync(container: AppContainer) {
        synchronized(this) {
            if (engine != null) return
            val context = container.context
            val screen = ScreenOnGate(context)
            engine = WidgetSyncEngine(
                placedKinds = { WidgetRegistry.placedKinds(context) },
                fingerprint = { kind ->
                    WidgetFingerprints.of(
                        kind,
                        vault = { container.vaultRepository },
                        settings = { container.settingsRepository },
                        listener = { container.listenerStatus },
                    )
                },
                render = { kind -> WidgetRegistry.render(context, kind) },
                awaitVisible = screen::await,
            ).also { it.start(container.appScope) }
        }
    }

    /** Called by the receivers when instances are added/updated/removed; starts sync on the first placed widget. */
    internal fun onPlacementChanged(context: Context) {
        val current = engine
        if (current != null) {
            current.notifyPlacementChanged()
            return
        }
        val container = WidgetLoaders.containerOrNull(context) ?: return
        startSync(container) // the new engine reads the placement itself
    }
}

internal object WidgetRegistry {
    fun receiverClass(kind: WidgetKind): Class<out GlanceAppWidgetReceiver> = when (kind) {
        WidgetKind.COUNT -> ArchiveCountWidgetReceiver::class.java
        WidgetKind.RECENT -> RecentWidgetReceiver::class.java
        WidgetKind.FAVORITES -> FavoritesWidgetReceiver::class.java
        WidgetKind.APP_SUMMARY -> AppSummaryWidgetReceiver::class.java
        WidgetKind.CAPTURE -> CaptureControlWidgetReceiver::class.java
    }

    fun newWidget(kind: WidgetKind): GlanceAppWidget = when (kind) {
        WidgetKind.COUNT -> ArchiveCountWidget()
        WidgetKind.RECENT -> RecentWidget()
        WidgetKind.FAVORITES -> FavoritesWidget()
        WidgetKind.APP_SUMMARY -> AppSummaryWidget()
        WidgetKind.CAPTURE -> CaptureControlWidget()
    }

    /** Receiver class names, kept in sync with [receiverClass] (unit-tested); used to avoid class-loading Glance. */
    val RECEIVER_CLASS_NAMES: List<String> = listOf(
        "com.riyadm.unsendvault.widget.ArchiveCountWidgetReceiver",
        "com.riyadm.unsendvault.widget.RecentWidgetReceiver",
        "com.riyadm.unsendvault.widget.FavoritesWidgetReceiver",
        "com.riyadm.unsendvault.widget.AppSummaryWidgetReceiver",
        "com.riyadm.unsendvault.widget.CaptureControlWidgetReceiver",
    )

    /** True when any UnsendVault widget is placed. One binder call per provider, nothing else. */
    fun anyPlaced(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        return RECEIVER_CLASS_NAMES.any { name ->
            manager.getAppWidgetIds(ComponentName(context.packageName, name))?.isNotEmpty() == true
        }
    }

    /** Synchronous binder call per provider; no Glance DataStore read. Empty on devices without app widgets. */
    fun placedKinds(context: Context): Set<WidgetKind> {
        val manager = AppWidgetManager.getInstance(context) ?: return emptySet()
        val placed = EnumSet.noneOf(WidgetKind::class.java)
        for (kind in WidgetKind.entries) {
            val ids = manager.getAppWidgetIds(ComponentName(context, receiverClass(kind)))
            if (ids != null && ids.isNotEmpty()) placed += kind
        }
        return placed
    }

    /** Signals running sessions to re-read their data and (re)starts sessions for every instance of [kind]. */
    suspend fun render(context: Context, kind: WidgetKind) {
        WidgetRenderSignals.bump(kind)
        newWidget(kind).updateAll(context)
    }
}

/** Publishes generated (synthetic-data) picker previews on API 35+, once per app version. */
internal object WidgetPreviewPublisher {
    private const val PREFS = "uv_widget_previews"
    private const val KEY_VERSION = "published_version_code"
    private const val START_DELAY_MS = 15_000L

    fun publishIfNeeded(context: Context, scope: CoroutineScope) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        scope.launch {
            delay(START_DELAY_MS) // keep it off the cold-start path (the listener may have started the process)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) publish(context)
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private suspend fun publish(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = BuildConfig.VERSION_CODE.toLong()
        if (prefs.getLong(KEY_VERSION, -1L) == version) return
        val manager = GlanceAppWidgetManager(context)
        var allPublished = true
        for (kind in WidgetKind.entries) {
            val result = try {
                manager.setWidgetPreviews(WidgetRegistry.receiverClass(kind).kotlin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("UvWidget", "preview for $kind failed: ${e.javaClass.simpleName}")
                -1
            }
            if (result != GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) allPublished = false
        }
        if (allPublished) prefs.edit { putLong(KEY_VERSION, version) }
    }
}
