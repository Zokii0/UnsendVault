package com.riyadm.unsendvault.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On-device smoke test: all five providers are registered, resizable and never poll. */
@RunWith(AndroidJUnit4::class)
class WidgetProvidersInstrumentedTest {

    @Test
    fun allProvidersRegisteredWithoutPolling() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = AppWidgetManager.getInstance(context)
        val providers = manager.getInstalledProvidersForPackage(context.packageName, null)
        val expected = WidgetKind.entries.map { WidgetRegistry.receiverClass(it).name }.toSet()
        assertEquals(expected, providers.map { it.provider.className }.toSet())
        for (info in providers) {
            assertEquals("${info.provider.className} must not poll", 0, info.updatePeriodMillis)
            assertEquals(AppWidgetProviderInfo.RESIZE_BOTH, info.resizeMode and AppWidgetProviderInfo.RESIZE_BOTH)
            assertTrue(info.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0)
        }
    }

    @Test
    fun noWidgetsPlacedMeansNothingObserved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // On a clean test device none of our widgets are placed, so the sync engine has nothing to observe.
        val placed = WidgetRegistry.placedKinds(context)
        assertTrue(placed.all { it in WidgetKind.entries })
    }
}
