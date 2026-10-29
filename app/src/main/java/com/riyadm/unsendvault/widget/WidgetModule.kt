package com.riyadm.unsendvault.widget

import android.content.Context
import com.riyadm.unsendvault.data.WidgetRefresher
import com.riyadm.unsendvault.di.AppContainer

/** SKELETON STUB — replaced by the widget layer. */
object WidgetModule {
    fun createRefresher(context: Context): WidgetRefresher = WidgetRefresher { }

    /** Starts the event-driven (never polling) sync that refreshes widgets when archive data or settings change. */
    fun startSync(container: AppContainer) = Unit
}
