package com.riyadm.unsendvault.widget

import android.app.Application
import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasStartActivityClickAction
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riyadm.unsendvault.MainActivity
import com.riyadm.unsendvault.core.model.AppSummary
import com.riyadm.unsendvault.core.model.VaultSettings
import com.riyadm.unsendvault.widget.WidgetTestData.FILTERED_TEXT
import com.riyadm.unsendvault.widget.WidgetTestData.GROUP_TITLE
import com.riyadm.unsendvault.widget.WidgetTestData.MIN
import com.riyadm.unsendvault.widget.WidgetTestData.NOW
import com.riyadm.unsendvault.widget.WidgetTestData.REDACTED_TEXT
import com.riyadm.unsendvault.widget.WidgetTestData.SECRET_TEXT
import com.riyadm.unsendvault.widget.WidgetTestData.SENDER
import com.riyadm.unsendvault.widget.WidgetTestData.messages
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Renders each widget's content composable with Glance's unit-test host (Robolectric for resources). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class WidgetContentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val large = DpSize(250.dp, 260.dp)
    private val small = DpSize(110.dp, 40.dp)

    private fun GlanceAppWidgetUnitTest.setUp(size: DpSize) {
        setContext(context)
        setAppWidgetSize(size)
    }

    private fun GlanceAppWidgetUnitTest.assertNoMessageContent() {
        for (secret in listOf(SECRET_TEXT, SENDER, GROUP_TITLE, "meet at 6", "Bob Sample", REDACTED_TEXT, FILTERED_TEXT, "Carol", "Dave")) {
            onAllNodes(hasText(secret, ignoreCase = true)).assertCountEquals(0)
            onAllNodes(hasContentDescription(secret, ignoreCase = true)).assertCountEquals(0)
        }
    }

    private fun listFor(settings: VaultSettings, lockedCount: Int = 0) =
        WidgetLoad.Ready(WidgetPrivacy.messageList(messages, WidgetPrivacy.mode(settings), lockedCount), NOW)

    // ---- Archive count ------------------------------------------------------------------------------------------

    @Test
    fun countShowsTotalsAndOpensApp() = runGlanceAppWidgetUnitTest {
        setUp(DpSize(180.dp, 110.dp))
        provideComposable { WidgetTheme { ArchiveCountContent(WidgetLoad.Ready(CountDisplay(1284, 96, 7, locked = false), NOW)) } }
        onNode(hasText("1,284")).assertExists()
        onNode(hasText("messages archived")).assertExists()
        onNode(hasText("96 conversations")).assertExists()
        onNode(hasText("7 apps")).assertExists()
        onNode(hasContentDescription("1,284 messages archived")).assertExists()
        onAllNodes(hasStartActivityClickAction<MainActivity>(actionParametersOf(WidgetIntents.DestinationKey to WidgetIntents.DESTINATION_HOME)))
            .assertCountEquals(1)
    }

    @Test
    fun countCompactUsesSingularAndShowsLockHint() = runGlanceAppWidgetUnitTest {
        setUp(small)
        provideComposable { WidgetTheme { ArchiveCountContent(WidgetLoad.Ready(CountDisplay(1, 1, 1, locked = true), NOW)) } }
        onNode(hasText("message archived")).assertExists()
        // Lock icon + the root's spoken summary.
        onAllNodes(hasContentDescription("App lock on")).assertCountEquals(2)
    }

    @Test
    fun countEmptyState() = runGlanceAppWidgetUnitTest {
        setUp(DpSize(110.dp, 110.dp))
        provideComposable { WidgetTheme { ArchiveCountContent(WidgetLoad.Ready(CountDisplay(0, 0, 0, locked = false), NOW)) } }
        onNode(hasText("No messages yet")).assertExists()
    }

    @Test
    fun failedLoadAsksToOpenApp() = runGlanceAppWidgetUnitTest {
        setUp(DpSize(110.dp, 110.dp))
        provideComposable { WidgetTheme { ArchiveCountContent(WidgetLoad.Failed) } }
        onNode(hasText("Open UnsendVault")).assertExists()
    }

    // ---- Recent / Favorites -------------------------------------------------------------------------------------

    @Test
    fun recentDefaultShowsAppsCountsAndTimesButNoContent() = runGlanceAppWidgetUnitTest {
        setUp(large)
        provideComposable { WidgetTheme { MessageListContent(MessageListType.RECENT, listFor(VaultSettings())) } }
        onNode(hasText("Recent")).assertExists()
        onNode(hasText("WhatsApp")).assertExists()
        onNode(hasText("Signal")).assertExists()
        onNode(hasText("4 recent")).assertExists()
        onNode(hasText("1 min. ago")).assertExists()
        onAllNodes(hasStartActivityClickAction<MainActivity>(actionParametersOf(
            WidgetIntents.DestinationKey to WidgetIntents.DESTINATION_APP,
            WidgetIntents.PackageNameKey to "com.whatsapp",
        ))).assertCountEquals(1)
        assertNoMessageContent()
    }

    @Test
    fun recentContentHiddenWhenAppLockEnabledEvenIfOptedIn() = runGlanceAppWidgetUnitTest {
        setUp(large)
        val load = listFor(VaultSettings(widgetShowContent = true, appLockEnabled = true), lockedCount = 5)
        provideComposable { WidgetTheme { MessageListContent(MessageListType.RECENT, load) } }
        onNode(hasText("5 messages")).assertExists()
        onNode(hasText("App lock is on", ignoreCase = true)).assertExists()
        onNode(hasContentDescription("App lock on")).assertExists()
        onAllNodes(hasText("WhatsApp")).assertCountEquals(0)
        onAllNodes(hasText("Signal")).assertCountEquals(0)
        assertNoMessageContent()
    }

    @Test
    fun recentShowsContentOnlyWhenAllowedAndNeverRedactedKinds() = runGlanceAppWidgetUnitTest {
        setUp(large)
        provideComposable {
            WidgetTheme { MessageListContent(MessageListType.RECENT, listFor(VaultSettings(widgetShowContent = true))) }
        }
        onAllNodes(hasText(SENDER)).assertCountEquals(2) // messages 1 and 5
        onNode(hasText(SECRET_TEXT)).assertExists()
        onAllNodes(hasText("Content hidden")).assertCountEquals(2) // the redacted and the filtered message
        for (hidden in listOf(REDACTED_TEXT, FILTERED_TEXT, "Carol", "Dave")) {
            onAllNodes(hasText(hidden)).assertCountEquals(0)
            onAllNodes(hasContentDescription(hidden)).assertCountEquals(0)
        }
        onAllNodes(hasStartActivityClickAction<MainActivity>(actionParametersOf(
            WidgetIntents.DestinationKey to WidgetIntents.DESTINATION_MESSAGE,
            WidgetIntents.MessageIdKey to 1L,
        ))).assertCountEquals(1)
    }

    @Test
    fun recentEmptyState() = runGlanceAppWidgetUnitTest {
        setUp(large)
        provideComposable {
            WidgetTheme { MessageListContent(MessageListType.RECENT, WidgetLoad.Ready(MessageListDisplay.Empty, NOW)) }
        }
        onNode(hasText("No messages yet")).assertExists()
        onNode(hasText("Open UnsendVault")).assertDoesNotExist()
    }

    @Test
    fun recentRowCountAdaptsToSize() = runGlanceAppWidgetUnitTest {
        setUp(DpSize(110.dp, 110.dp))
        val many = (1L..8L).map { WidgetTestData.message(it, packageName = "pkg.$it", appLabel = "App$it") }
        val load = WidgetLoad.Ready(WidgetPrivacy.messageList(many, WidgetPrivacyMode.METADATA), NOW)
        provideComposable { WidgetTheme { MessageListContent(MessageListType.RECENT, load) } }
        onNode(hasText("App1")).assertExists()
        onAllNodes(hasText("App8")).assertCountEquals(0)
    }

    @Test
    fun favoritesDefaultHidesContent() = runGlanceAppWidgetUnitTest {
        setUp(large)
        val favorites = messages.map { it.copy(isFavorite = true) }
        val load = WidgetLoad.Ready(WidgetPrivacy.messageList(favorites, WidgetPrivacyMode.METADATA), NOW)
        provideComposable { WidgetTheme { MessageListContent(MessageListType.FAVORITES, load) } }
        onNode(hasText("Favorites")).assertExists()
        onNode(hasText("4 favorites")).assertExists()
        assertNoMessageContent()
    }

    @Test
    fun favoritesEmptyAndLocked() = runGlanceAppWidgetUnitTest {
        setUp(large)
        provideComposable {
            WidgetTheme { MessageListContent(MessageListType.FAVORITES, WidgetLoad.Ready(MessageListDisplay.Locked(3), NOW)) }
        }
        onNode(hasText("3 favorites")).assertExists()
        onNode(hasText("App lock is on", ignoreCase = true)).assertExists()
    }

    // ---- App summary --------------------------------------------------------------------------------------------

    @Test
    fun appSummaryShowsPerAppCounts() = runGlanceAppWidgetUnitTest {
        setUp(large)
        val summaries = listOf(
            AppSummary("com.whatsapp", "WhatsApp", 120, 8, NOW - 5 * MIN),
            AppSummary("org.thoughtcrime.securesms", "Signal", 1, 1, NOW - 90 * MIN),
        )
        val load = WidgetLoad.Ready(WidgetPrivacy.appSummary(summaries, WidgetPrivacyMode.METADATA, null), NOW)
        provideComposable { WidgetTheme { AppSummaryContent(load) } }
        onNode(hasText("Top apps")).assertExists()
        onNode(hasText("WhatsApp")).assertExists()
        onNode(hasText("120 messages", ignoreCase = true)).assertExists()
        onNode(hasText("5 min. ago")).assertExists()
        onNode(hasText("1 message")).assertExists()
        onAllNodes(hasStartActivityClickAction<MainActivity>(actionParametersOf(
            WidgetIntents.DestinationKey to WidgetIntents.DESTINATION_APP,
            WidgetIntents.PackageNameKey to "org.thoughtcrime.securesms",
        ))).assertCountEquals(1)
    }

    @Test
    fun appSummaryLockedShowsCountsOnly() = runGlanceAppWidgetUnitTest {
        setUp(large)
        provideComposable { WidgetTheme { AppSummaryContent(WidgetLoad.Ready(AppSummaryDisplay.Locked(550, 10), NOW)) } }
        onNode(hasText("550 messages")).assertExists()
        onNode(hasText("10 apps")).assertExists()
        onNode(hasText("App lock is on", ignoreCase = true)).assertExists()
    }

    @Test
    fun appSummaryEmptyState() = runGlanceAppWidgetUnitTest {
        setUp(large)
        provideComposable { WidgetTheme { AppSummaryContent(WidgetLoad.Ready(AppSummaryDisplay.Empty, NOW)) } }
        onNode(hasText("No messages yet")).assertExists()
    }

    // ---- Capture control ----------------------------------------------------------------------------------------

    private fun captureTest(state: CaptureState, size: DpSize = DpSize(180.dp, 40.dp), checks: GlanceAppWidgetUnitTest.() -> Unit) =
        runGlanceAppWidgetUnitTest {
            setUp(size)
            provideComposable { WidgetTheme { CaptureControlContent(WidgetLoad.Ready(state, NOW)) } }
            checks()
        }

    @Test
    fun captureActiveOffersPause() = captureTest(CaptureState.ACTIVE) {
        onNode(hasText("Active")).assertExists()
        onNode(hasText("Pause")).assertExists()
        onNode(hasContentDescription("Capture is active. Double tap to pause.")).assertExists()
        onAllNodes(hasRunCallbackClickAction<ToggleCaptureAction>(actionParametersOf(CaptureActions.TargetEnabledKey to false)))
            .assertCountEquals(1)
    }

    @Test
    fun capturePausedOffersResume() = captureTest(CaptureState.PAUSED) {
        onNode(hasText("Paused")).assertExists()
        onNode(hasText("Resume")).assertExists()
        onNode(hasContentDescription("Capture is paused. Double tap to resume.")).assertExists()
        onAllNodes(hasRunCallbackClickAction<ToggleCaptureAction>(actionParametersOf(CaptureActions.TargetEnabledKey to true)))
            .assertCountEquals(1)
    }

    @Test
    fun captureWithoutAccessOpensAppInsteadOfToggling() = captureTest(CaptureState.ACCESS_NEEDED) {
        onNode(hasText("Notification access needed")).assertExists()
        onAllNodes(hasStartActivityClickAction<MainActivity>(actionParametersOf(
            WidgetIntents.DestinationKey to WidgetIntents.DESTINATION_NOTIFICATION_ACCESS,
        ))).assertCountEquals(1)
        onAllNodes(hasRunCallbackClickAction<ToggleCaptureAction>(actionParametersOf(CaptureActions.TargetEnabledKey to false)))
            .assertCountEquals(0)
        onAllNodes(hasRunCallbackClickAction<ToggleCaptureAction>(actionParametersOf(CaptureActions.TargetEnabledKey to true)))
            .assertCountEquals(0)
    }

    @Test
    fun captureDisconnectedOffersReconnect() = captureTest(CaptureState.DISCONNECTED, size = small) {
        onNode(hasText("Listener disconnected")).assertExists()
        onNode(hasText("Reconnect")).assertDoesNotExist() // no action pill at 2x1 width
        onNode(hasContentDescription("disconnected", ignoreCase = true)).assertExists()
        onAllNodes(hasRunCallbackClickAction<RebindListenerAction>()).assertCountEquals(1)
    }

    @Test
    fun captureFailedLoad() = runGlanceAppWidgetUnitTest {
        setUp(small)
        provideComposable { WidgetTheme { CaptureControlContent(WidgetLoad.Failed) } }
        onNode(hasText("Open UnsendVault")).assertExists()
    }

    // ---- Generated previews use synthetic data only ------------------------------------------------------------

    @Test
    fun samplesRenderWithoutRealData() = runGlanceAppWidgetUnitTest {
        setUp(large)
        provideComposable { WidgetTheme { MessageListContent(MessageListType.RECENT, WidgetSamples.recent) } }
        onNode(hasText("Messages")).assertExists()
        assertNoMessageContent()
    }
}
