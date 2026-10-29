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
) {
    private val placementSignal = MutableStateFlow(0L)
    private val dirty = MutableStateFlow<Set<WidgetKind>>(emptySet())
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    fun notifyPlacementChanged() {
        placementSignal.update { it + 1 }
    }

    fun requestRefresh(kinds: Set<WidgetKind> = ALL) {
        if (kinds.isEmpty()) return
        dirty.update { it + kinds }
        wakeups.trySend(Unit)
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
                (jobs.keys - placed).forEach { jobs.remove(it)?.cancel() }
                (placed - jobs.keys).forEach { kind ->
                    jobs[kind] = launch {
                        fingerprint(kind)
                            .distinctUntilChanged()
                            .catch { e -> Log.w(TAG, "source for $kind failed: ${e.javaClass.simpleName}") }
                            .collect { requestRefresh(setOf(kind)) }
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
            flush()
        }
    }

    private suspend fun flush() {
        val batch = dirty.getAndUpdate { emptySet() }
        if (batch.isEmpty()) return
        val placed = safePlacedKinds()
        for (kind in WidgetKind.entries) {
            if (kind !in batch || kind !in placed) continue
            try {
                render(kind)
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
        val ALL: Set<WidgetKind> = EnumSet.allOf(WidgetKind::class.java)
    }
}
