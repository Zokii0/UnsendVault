package com.riyadm.unsendvault.widget

import android.app.Application
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.unsendvault.core.model.VaultSettings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** API 30: exercises the non-dynamic palette and drawable-based rounded backgrounds. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], application = Application::class)
class WidgetLegacyApiContentTest {

    @Test
    fun countAndCaptureRenderWithFallbackTheme() {
        runGlanceAppWidgetUnitTest {
            setContext(ApplicationProvider.getApplicationContext())
            setAppWidgetSize(DpSize(180.dp, 40.dp))
            provideComposable { WidgetTheme { CaptureControlContent(WidgetLoad.Ready(CaptureState.PAUSED, WidgetTestData.NOW)) } }
            onNode(hasText("Paused")).assertExists()
            onNode(hasText("Resume")).assertExists()
        }
        runGlanceAppWidgetUnitTest {
            setContext(ApplicationProvider.getApplicationContext())
            setAppWidgetSize(DpSize(250.dp, 180.dp))
            val load = WidgetLoad.Ready(WidgetPrivacy.messageList(WidgetTestData.messages, WidgetPrivacy.mode(VaultSettings())), WidgetTestData.NOW)
            provideComposable { WidgetTheme { MessageListContent(MessageListType.RECENT, load) } }
            onNode(hasText("WhatsApp")).assertExists()
            onAllNodes(hasText(WidgetTestData.SECRET_TEXT)).assertCountEquals(0)
        }
    }
}
