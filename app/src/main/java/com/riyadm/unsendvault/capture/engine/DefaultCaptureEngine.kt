package com.riyadm.unsendvault.capture.engine

import com.riyadm.unsendvault.capture.CaptureEngine
import com.riyadm.unsendvault.capture.CaptureLog
import com.riyadm.unsendvault.capture.NotificationSnapshot
import com.riyadm.unsendvault.capture.ParsedNotification
import com.riyadm.unsendvault.capture.RemovalEvent
import com.riyadm.unsendvault.capture.adapter.AdapterRegistry
import com.riyadm.unsendvault.capture.adapter.AppAdapter
import com.riyadm.unsendvault.capture.adapter.AppLabelResolver
import com.riyadm.unsendvault.capture.filter.SensitiveContentFilter
import com.riyadm.unsendvault.capture.parser.ParseOutcome
import com.riyadm.unsendvault.core.model.VaultSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Where processed captures go. Production wraps [com.riyadm.unsendvault.data.VaultRepository]. */
interface CaptureSink {
    suspend fun ingest(parsed: ParsedNotification, capturedAt: Long)
    suspend fun recordRemoval(event: RemovalEvent)
}

/**
 * snapshot → settings gates → adapter registry → sensitive filter → [CaptureSink].
 *
 * [onPosted] / [onRemoved] only enqueue into a bounded channel (oldest work dropped under pressure) so listener
 * callbacks return immediately. A single consumer on [dispatcher] processes items in order; every item is isolated
 * with try/catch so one bad notification never stops the pipeline.
 */
class DefaultCaptureEngine(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    /** Latest persisted settings (production: `settingsRepository.settings.first()`, never the possibly-default snapshot). */
    private val settingsProvider: suspend () -> VaultSettings,
    private val sink: CaptureSink,
    private val registry: AdapterRegistry,
    private val labels: AppLabelResolver,
    private val sensitiveFilter: SensitiveContentFilter,
    private val ownPackage: String,
    private val clock: () -> Long = System::currentTimeMillis,
    capacity: Int = DEFAULT_CAPACITY,
) : CaptureEngine {

    private sealed interface Work {
        data class Posted(val snapshot: NotificationSnapshot) : Work
        data class Removed(val event: RemovalEvent) : Work
    }

    private val queue = Channel<Work>(capacity, BufferOverflow.DROP_OLDEST)

    /** notificationKey → fingerprint of the last ingested content; skips identical re-posts. Consumer-confined. */
    private val lastIngested = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > FINGERPRINT_CACHE_SIZE
    }

    init {
        scope.launch(dispatcher) {
            for (work in queue) {
                try {
                    when (work) {
                        is Work.Posted -> handlePosted(work.snapshot)
                        is Work.Removed -> handleRemoved(work.event)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    CaptureLog.d("capture item failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    override fun onPosted(snapshot: NotificationSnapshot) {
        queue.trySend(Work.Posted(snapshot))
    }

    override fun onRemoved(event: RemovalEvent) {
        queue.trySend(Work.Removed(event))
    }

    private suspend fun handlePosted(snapshot: NotificationSnapshot) {
        val settings = settingsProvider()
        val parsed = process(snapshot, settings) ?: return
        val fingerprint = 31 * parsed.messages.hashCode() + parsed.deletionMarkers.hashCode()
        if (lastIngested[parsed.notificationKey] == fingerprint) return
        sink.ingest(parsed, clock())
        lastIngested[parsed.notificationKey] = fingerprint
    }

    private suspend fun handleRemoved(event: RemovalEvent) {
        val settings = settingsProvider()
        if (!settings.captureEnabled || event.packageName == ownPackage || event.packageName in settings.excludedPackages) return
        lastIngested.remove(event.notificationKey)
        sink.recordRemoval(event)
    }

    /**
     * Pure decision + parse step (no I/O besides the cached label lookup). Returns null when the notification must not
     * be stored: capture paused, own/excluded package, ongoing, rejected by the messages-only gate, or nothing parsed.
     */
    fun process(snapshot: NotificationSnapshot, settings: VaultSettings): ParsedNotification? {
        if (!settings.captureEnabled) return null
        val pkg = snapshot.packageName
        if (pkg == ownPackage || pkg in settings.excludedPackages) return null
        if (snapshot.isOngoing) return null
        val adapter = registry.adapterFor(pkg)
        if (settings.messagesOnly && !safeIsMessage(adapter, snapshot)) return null

        val result = registry.parse(snapshot)
        val parsed = result.outcome as? ParseOutcome.Parsed ?: return null
        val messages = parsed.messages.map { sensitiveFilter.apply(it, settings) }
        if (messages.isEmpty() && parsed.deletionMarkers.isEmpty()) return null
        return ParsedNotification(
            packageName = pkg,
            appLabel = labels.labelFor(pkg),
            notificationKey = snapshot.key,
            postTime = snapshot.postTime,
            adapterId = result.adapterId,
            messages = messages,
            deletionMarkers = parsed.deletionMarkers,
        )
    }

    private fun safeIsMessage(adapter: AppAdapter, s: NotificationSnapshot): Boolean =
        try {
            adapter.isMessageNotification(s)
        } catch (e: Exception) {
            registry.generic.isMessageNotification(s)
        }

    companion object {
        const val DEFAULT_CAPACITY = 256
        private const val FINGERPRINT_CACHE_SIZE = 256
    }
}
