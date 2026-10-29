package com.riyadm.unsendvault.widget

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import kotlin.concurrent.thread

class WidgetSyncEngineTest {

    /** advanceUntilIdle() ignores backgroundScope timers, so advance past the max-wait window explicitly. */
    private fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    private class Harness(scope: TestScope, placed: Set<WidgetKind>, sources: Map<WidgetKind, Flow<Any?>> = emptyMap()) {
        var placed: Set<WidgetKind> = placed
        val renders: MutableList<WidgetKind> = Collections.synchronizedList(mutableListOf())
        val subscriptions = mutableMapOf<WidgetKind, Int>()
        val active = mutableSetOf<WidgetKind>()
        var failNextRender = false

        val engine = WidgetSyncEngine(
            placedKinds = { this.placed },
            fingerprint = { kind ->
                (sources[kind] ?: emptyFlow())
                    .onStart { subscriptions[kind] = (subscriptions[kind] ?: 0) + 1; active += kind }
                    .onCompletion { active -= kind }
            },
            render = { kind ->
                if (failNextRender) {
                    failNextRender = false
                    error("boom")
                }
                renders += kind
            },
            debounceMillis = 1_500,
            maxWaitMillis = 5_000,
            clock = { scope.testScheduler.currentTime },
        )
    }

    @Test
    fun `nothing is observed or rendered while no widget is placed`() = runTest {
        val source = MutableStateFlow(1)
        val h = Harness(this, placed = emptySet(), sources = WidgetKind.entries.associateWith { source })
        h.engine.start(backgroundScope)
        h.engine.requestRefresh()
        settle()
        assertTrue(h.subscriptions.isEmpty())
        assertTrue(h.renders.isEmpty())
    }

    @Test
    fun `burst of changes is debounced into one render`() = runTest {
        val source = MutableStateFlow(0)
        val h = Harness(this, placed = setOf(WidgetKind.COUNT), sources = mapOf(WidgetKind.COUNT to source))
        h.engine.start(backgroundScope)
        settle()
        assertEquals(listOf(WidgetKind.COUNT), h.renders) // initial state after start
        h.renders.clear()

        repeat(5) {
            source.value = it + 1
            advanceTimeBy(200)
        }
        assertTrue(h.renders.isEmpty())
        advanceTimeBy(1_501)
        runCurrent()
        assertEquals(listOf(WidgetKind.COUNT), h.renders)
    }

    @Test
    fun `unchanged fingerprint does not render again`() = runTest {
        val source = MutableSharedFlow<Any?>(replay = 1)
        source.tryEmit("same")
        val h = Harness(this, placed = setOf(WidgetKind.RECENT), sources = mapOf(WidgetKind.RECENT to source))
        h.engine.start(backgroundScope)
        settle()
        h.renders.clear()
        source.emit("same")
        source.emit("same")
        settle()
        assertTrue(h.renders.isEmpty())
        source.emit("different")
        settle()
        assertEquals(listOf(WidgetKind.RECENT), h.renders)
    }

    @Test
    fun `explicit refresh renders only placed kinds once`() = runTest {
        val h = Harness(this, placed = setOf(WidgetKind.RECENT, WidgetKind.CAPTURE))
        h.engine.start(backgroundScope)
        settle()
        h.engine.requestRefresh()
        h.engine.requestRefresh()
        h.engine.requestRefresh(setOf(WidgetKind.COUNT))
        settle()
        assertEquals(listOf(WidgetKind.RECENT, WidgetKind.CAPTURE), h.renders)
    }

    @Test
    fun `refresh requests from many threads coalesce`() = runTest {
        val h = Harness(this, placed = setOf(WidgetKind.FAVORITES, WidgetKind.APP_SUMMARY))
        h.engine.start(backgroundScope)
        settle()
        (1..8).map { thread { repeat(250) { h.engine.requestRefresh() } } }.forEach { it.join() }
        settle()
        assertEquals(listOf(WidgetKind.FAVORITES, WidgetKind.APP_SUMMARY), h.renders)
    }

    @Test
    fun `placement changes start and stop observation`() = runTest {
        val source = MutableStateFlow(0)
        val h = Harness(this, placed = emptySet(), sources = mapOf(WidgetKind.FAVORITES to source))
        h.engine.start(backgroundScope)
        settle()
        assertTrue(WidgetKind.FAVORITES !in h.active)

        h.placed = setOf(WidgetKind.FAVORITES)
        h.engine.notifyPlacementChanged()
        settle()
        assertTrue(WidgetKind.FAVORITES in h.active)
        assertEquals(listOf(WidgetKind.FAVORITES), h.renders)

        h.placed = emptySet()
        h.engine.notifyPlacementChanged()
        settle()
        assertTrue(WidgetKind.FAVORITES !in h.active)
        h.renders.clear()
        source.value = 99
        settle()
        assertTrue(h.renders.isEmpty())
        assertEquals(1, h.subscriptions[WidgetKind.FAVORITES])
    }

    @Test
    fun `steady stream still renders within max wait`() = runTest {
        val source = MutableStateFlow(0)
        val h = Harness(this, placed = setOf(WidgetKind.COUNT), sources = mapOf(WidgetKind.COUNT to source))
        h.engine.start(backgroundScope)
        settle()
        h.renders.clear()
        // A change every second never leaves 1.5 s of quiet; the 5 s cap must still flush.
        repeat(12) {
            source.value = it + 1
            advanceTimeBy(1_000)
        }
        assertTrue("expected at least 2 renders, got ${h.renders.size}", h.renders.size >= 2)
        assertTrue(h.renders.size <= 4)
    }

    @Test
    fun `a failing render does not stop the engine`() = runTest {
        val h = Harness(this, placed = setOf(WidgetKind.COUNT))
        h.engine.start(backgroundScope)
        settle()
        h.failNextRender = true
        h.engine.requestRefresh()
        settle()
        assertTrue(h.renders.isEmpty())
        h.engine.requestRefresh()
        settle()
        assertEquals(listOf(WidgetKind.COUNT), h.renders)
    }
}
