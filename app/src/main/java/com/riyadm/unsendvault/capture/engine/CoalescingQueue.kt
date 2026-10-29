package com.riyadm.unsendvault.capture.engine

import com.riyadm.unsendvault.capture.NotificationSnapshot
import com.riyadm.unsendvault.capture.RemovalEvent

/**
 * Work queue that coalesces notification updates by notification key. Not thread-safe: the owner guards every call
 * with one lock. All times are monotonic milliseconds supplied by the caller (virtual time in tests).
 *
 * - A post for a key that is not pending becomes due at `now + window` (low-priority catch-up items use a longer
 *   window, so live notifications are processed first).
 * - A post for a key that is already pending replaces the snapshot ("latest wins") and keeps the original due time
 *   (or the new one if earlier, e.g. a live update of a catch-up item), so a continuous stream of updates is still
 *   processed at most `window` after its first update.
 * - A removal is processed as soon as possible, after the pending post for the same key and any earlier pending posts
 *   of the same package (so a message and a deletion marker of one app are never reordered).
 * - Capacity bounds pending + ready items; on overflow the oldest pending post is dropped (then the oldest ready
 *   item), like the previous `Channel(DROP_OLDEST)`.
 */
internal class CoalescingQueue(private val capacity: Int) {

    sealed interface Work {
        val key: String

        class Posted(val snapshot: NotificationSnapshot) : Work {
            override val key: String get() = snapshot.key
        }

        class Removed(val event: RemovalEvent) : Work {
            override val key: String get() = event.notificationKey
        }
    }

    private class Pending(var snapshot: NotificationSnapshot, var dueAt: Long)

    enum class PostResult {
        /** New pending entry: the consumer must re-evaluate its wait. */
        ADDED,

        /** Merged into a pending entry; due time unchanged (no wake-up needed). */
        MERGED,

        /** Merged and the due time moved earlier: the consumer must re-evaluate its wait. */
        MERGED_EARLIER,
    }

    /** Insertion-ordered: iteration order is first-arrival order. */
    private val pending = LinkedHashMap<String, Pending>()
    private val ready = ArrayDeque<Work>()

    val size: Int get() = pending.size + ready.size

    fun isPending(key: String): Boolean = key in pending

    /** Adds or merges a post. [onDrop] is called per overflow drop. */
    fun post(snapshot: NotificationSnapshot, now: Long, window: Long, onDrop: () -> Unit = {}): PostResult {
        val due = now + window.coerceAtLeast(0)
        val existing = pending[snapshot.key]
        if (existing != null) {
            existing.snapshot = snapshot
            if (due < existing.dueAt) {
                existing.dueAt = due
                return PostResult.MERGED_EARLIER
            }
            return PostResult.MERGED
        }
        pending[snapshot.key] = Pending(snapshot, due)
        trim(onDrop)
        return PostResult.ADDED
    }

    fun remove(event: RemovalEvent, onDrop: () -> Unit = {}) {
        val key = event.notificationKey
        if (key in pending) {
            // Earlier posts of the same package first (in arrival order), then this key's post.
            val it = pending.entries.iterator()
            while (it.hasNext()) {
                val (k, p) = it.next()
                if (k == key) {
                    ready.addLast(Work.Posted(p.snapshot))
                    it.remove()
                    break
                }
                if (p.snapshot.packageName == event.packageName) {
                    ready.addLast(Work.Posted(p.snapshot))
                    it.remove()
                }
            }
        }
        ready.addLast(Work.Removed(event))
        trim(onDrop)
    }

    /** Next item to process at [now]: ready items first, then the due pending post with the earliest due time. */
    fun next(now: Long): Work? {
        ready.removeFirstOrNull()?.let { return it }
        var bestKey: String? = null
        var bestDue = Long.MAX_VALUE
        for ((k, p) in pending) {
            if (p.dueAt < bestDue) {
                bestDue = p.dueAt
                bestKey = k
            }
        }
        if (bestKey == null || bestDue > now) return null
        return Work.Posted(pending.remove(bestKey)!!.snapshot)
    }

    /** Earliest due time among pending posts, or null when nothing is waiting. Ready items are due immediately. */
    fun nextDueAt(now: Long): Long? {
        if (ready.isNotEmpty()) return now
        var best: Long? = null
        for (p in pending.values) if (best == null || p.dueAt < best) best = p.dueAt
        return best
    }

    private fun trim(onDrop: () -> Unit) {
        while (size > capacity) {
            // Under pressure the queue is full of distinct-key posts; drop the oldest of those first.
            val oldestPending = pending.keys.firstOrNull()
            when {
                oldestPending != null -> pending.remove(oldestPending)
                ready.isNotEmpty() -> ready.removeFirst()
                else -> break
            }
            onDrop()
        }
    }
}
