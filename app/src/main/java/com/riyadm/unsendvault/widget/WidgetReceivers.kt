package com.riyadm.unsendvault.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** Re-evaluates which widget kinds are placed whenever the launcher adds, updates or removes an instance. */
abstract class VaultWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetModule.onPlacementChanged(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetModule.onPlacementChanged(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetModule.onPlacementChanged(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetModule.onPlacementChanged(context)
    }
}

class ArchiveCountWidgetReceiver : VaultWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ArchiveCountWidget()
}

class RecentWidgetReceiver : VaultWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecentWidget()
}

class FavoritesWidgetReceiver : VaultWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FavoritesWidget()
}

class AppSummaryWidgetReceiver : VaultWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AppSummaryWidget()
}

class CaptureControlWidgetReceiver : VaultWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CaptureControlWidget()
}
