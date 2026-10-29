package com.riyadm.unsendvault.widget

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Event-driven widget refresh (no polling).
 *
 * - Only widget kinds that have placed instances ([placedKinds]) have their data sources collected, so with no widgets
 *   on the home screen nothing is observed and the database is never opened on behalf of widgets.
 *   [notifyPlacementChanged] (called by the receivers) re-evaluates the placed set.
 * - Every source is reduced to a display fingerprint and passed through distinctUntilChanged, so unrelated table
 *   changes do not re-render widgets.
 * - Changes and [requestRefresh] calls only mark kinds dirty; a single flush loop renders after [debounceMillis] of
 *   quiet, but never later than [maxWaitMillis] after the first pending change (so a steady stream of messages
 *   can't starve the widgets).
 * - The engine remembers the fingerprint it last rendered per kind: a source that re-emits the same display state
 *   (re-subscription after a placement change, privacy-mode switch with identical output, a change and its revert
 *   inside one debounce window) never triggers `updateAll`. Explicit [requestRefresh] calls always render.
 * - Before rendering, [awaitVisible] suspends while the screen is off (production: until ACTION_SCREEN_ON), so
 *   messages arriving at night or in Doze cost no Glance session / WorkManager job; the latest state is rendered once
 *   when the user can see it. Rendering never writes the observed tables, so a render can't re-trigger itself.
 *
 * [requestRefresh] and [notifyPlacementChanged] are non-suspending, lock-free and safe to call from any thread.
 */
class WidgetSyncEngine(
    private val placedKinds: suspend () -> Set<WidgetKind>,
    private val fingerprint: (WidgetKind) -> Flow<Any?>,
    private val render: suspend (WidgetKind) -> Unit,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MS,
    private val maxWaitMillis: Long = DEFAULT_MAX_WAIT_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val awaitVisible: suspend () -> Unit = {},
) {
    private val placementSignal = MutableStateFlow(0L)
    private val dirty = MutableStateFlow<Set<WidgetKind>>(emptySet())
    private val forced = MutableStateFlow<Set<WidgetKind>>(emptySet())
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    /** Latest fingerprint seen per kind, and the one that was current when the kind last rendered successfully. */
    private val latest = ConcurrentHashMap<WidgetKind, Any>()
    private val rendered = ConcurrentHashMap<WidgetKind, Any>()

    /** Number of render calls made (diagnostics/tests). */
    @Volatile
    var renderCount: Long = 0
        private set

    fun notifyPlacementChanged() {
        placementSignal.update { it + 1 }
    }

    /** Explicit refresh: renders the placed [kinds] even if their fingerprint did not change. */
    fun requestRefresh(kinds: Set<WidgetKind> = ALL) {
        if (kinds.isEmpty()) return
        forced.update { it + kinds }
        markDirty(kinds)
    }

    private fun markDirty(kinds: Set<WidgetKind>) {
        dirty.update { it + kinds }
        wakeups.trySend(Unit)
    }

    private fun onFingerprint(kind: WidgetKind, value: Any?) {
        val v = value ?: NULL_FINGERPRINT
        latest[kind] = v
        if (rendered[kind] != v) markDirty(setOf(kind))
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        launch { observeSources() }
        launch { flushLoop() }
    }

    private suspend fun observeSources() = coroutineScope {
        val jobs = HashMap<WidgetKind, Job>()
        placementSignal
            .map { safePlacedKinds() }
            .distinctUntilChanged()
            .collect { placed ->
                (jobs.keys - placed).forEach { kind ->
                    jobs.remove(kind)?.cancel()
                    // A kind placed again later must render its first state.
                    latest.remove(kind)
                    rendered.remove(kind)
                }
                (placed - jobs.keys).forEach { kind ->
                    jobs[kind] = launch {
                        fingerprint(kind)
                            .distinctUntilChanged()
                            .catch { e -> Log.w(TAG, "source for $kind failed: ${e.javaClass.simpleName}") }
                            .collect { onFingerprint(kind, it) }
                    }
                }
            }
    }

    private suspend fun flushLoop() {
        while (true) {
            wakeups.receive()
            val deadline = clock() + maxWaitMillis
            while (true) {
                val remaining = minOf(debounceMillis, deadline - clock())
                if (remaining <= 0) break
                withTimeoutOrNull(remaining) { wakeups.receive() } ?: break
            }
            // Screen off: wait (no CPU, no timers) until the user can see the widgets; changes keep accumulating.
            awaitVisible()
            flush()
        }
    }

    private suspend fun flush() {
        val batch = dirty.getAndUpdate { emptySet() }
        val explicit = forced.getAndUpdate { emptySet() }
        if (batch.isEmpty()) return
        val placed = safePlacedKinds()
        for (kind in WidgetKind.entries) {
            if (kind !in batch || kind !in placed) continue
            val target = latest[kind]
            if (kind !in explicit && target != null && rendered[kind] == target) continue // output unchanged
            try {
                renderCount++
                render(kind)
                if (target != null) rendered[kind] = target else rendered.remove(kind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "render $kind failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun safePlacedKinds(): Set<WidgetKind> = try {
        placedKinds()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "placed widget lookup failed: ${e.javaClass.simpleName}")
        emptySet()
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 1_500L
        const val DEFAULT_MAX_WAIT_MS = 5_000L
        private const val TAG = "UvWidgetSync"
        private val NULL_FINGERPRINT = Any()
        val ALL: Set<WidgetKind> = EnumSet.allOf(WidgetKind::class.java)
    }
}
