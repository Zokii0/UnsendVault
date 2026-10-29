package com.riyadm.unsendvault.widget

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Widget updates are skipped when the derived display state did not change, and held while the screen is off. */
class WidgetRenderSuppressionTest {

    private fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    private class Rig(
        scope: TestScope,
        var placed: Set<WidgetKind>,
        sources: Map<WidgetKind, Flow<Any?>>,
        awaitVisible: suspend () -> Unit = {},
    ) {
        val renders = mutableListOf<WidgetKind>()
        val engine = WidgetSyncEngine(
            placedKinds = { placed },
            fingerprint = { kind -> sources[kind] ?: emptyFlow() },
            render = { renders += it },
            debounceMillis = 1_500,
            maxWaitMillis = 5_000,
            clock = { scope.testScheduler.currentTime },
            awaitVisible = awaitVisible,
        )
    }

    @Test
    fun changeAndRevertWithinTheDebounceWindowDoesNotRender() = runTest {
        val source = MutableStateFlow("A")
        val rig = Rig(this, setOf(WidgetKind.COUNT), mapOf(WidgetKind.COUNT to source))
        rig.engine.start(backgroundScope)
        settle()
        assertEquals(listOf(WidgetKind.COUNT), rig.renders)
        rig.renders.clear()

        source.value = "B"
        advanceTimeBy(300)
        source.value = "A" // e.g. a message arrived and was deleted again before the flush
        settle()
        assertTrue("display state equals what is on screen: no updateAll", rig.renders.isEmpty())
        assertEquals(1L, rig.engine.renderCount)
    }

    @Test
    fun placementChurnOfAnotherKindDoesNotReRenderUnchangedKinds() = runTest {
        val count = MutableStateFlow(1)
        val recent = MutableStateFlow("r")
        val rig = Rig(this, setOf(WidgetKind.COUNT), mapOf(WidgetKind.COUNT to count, WidgetKind.RECENT to recent))
        rig.engine.start(backgroundScope)
        settle()
        rig.renders.clear()
        // A second widget kind is placed: only it renders; COUNT is re-evaluated but unchanged.
        rig.placed = setOf(WidgetKind.COUNT, WidgetKind.RECENT)
        rig.engine.notifyPlacementChanged()
        settle()
        assertEquals(listOf(WidgetKind.RECENT), rig.renders)
    }

    @Test
    fun screenOffHoldsRendersAndFlushesOnceWhenVisible() = runTest {
        val screenOn = CompletableDeferred<Unit>()
        val source = MutableStateFlow(0)
        val rig = Rig(this, setOf(WidgetKind.RECENT), mapOf(WidgetKind.RECENT to source), awaitVisible = { screenOn.await() })
        rig.engine.start(backgroundScope)
        settle()
        assertTrue("screen off: nothing rendered yet", rig.renders.isEmpty())

        repeat(30) { // a busy night: a message every 20 s for 10 minutes
            source.value = it + 1
            advanceTimeBy(20_000)
        }
        runCurrent()
        assertTrue(rig.renders.isEmpty())

        screenOn.complete(Unit)
        settle()
        assertEquals("one render with the latest state", listOf(WidgetKind.RECENT), rig.renders)

        // Screen stays on: normal debounced behaviour.
        source.value = 100
        settle()
        assertEquals(2, rig.renders.size)
    }

    @Test
    fun explicitRefreshStillRendersUnchangedState() = runTest {
        val source = MutableStateFlow("A")
        val rig = Rig(this, setOf(WidgetKind.CAPTURE), mapOf(WidgetKind.CAPTURE to source))
        rig.engine.start(backgroundScope)
        settle()
        rig.renders.clear()
        rig.engine.requestRefresh(setOf(WidgetKind.CAPTURE))
        settle()
        assertEquals(listOf(WidgetKind.CAPTURE), rig.renders)
    }

    @Test
    fun receiverClassNamesMatchTheReceivers() {
        assertEquals(
            WidgetKind.entries.map { WidgetRegistry.receiverClass(it).name },
            WidgetRegistry.RECEIVER_CLASS_NAMES,
        )
    }
}
