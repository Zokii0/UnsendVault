package com.riyadm.unsendvault.capture

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lets the archive answer a chat from inside this app, the way a notification's own "Reply" button does.
 *
 * Messenger apps attach an inline-reply action (a [RemoteInput] plus a [PendingIntent]) to the notification of each
 * conversation. Firing it with the typed text sends the message through the chat app without opening it, so the chat
 * app does not see the user "come online".
 *
 * A PendingIntent belongs to the chat app, not to the notification, so it normally stays usable after the notification
 * was swiped away or cancelled. The latest reply action of every conversation is therefore kept (in memory only, bounded
 * to [MAX_ENTRIES], never the message text) until the process ends or the chat app's own PendingIntent is cancelled. A
 * send that fails is reported in the UI. Note a process restart forgets all actions; they come back with the next
 * message of each chat.
 */
object ReplyRegistry {
    private const val MAX_ENTRIES = 256

    private class Entry(
        val packageName: String,
        val action: PendingIntent,
        val inputs: Array<RemoteInput>,
        var conversationKey: String?,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>() // notification key -> reply action, oldest first

    private val _available = MutableStateFlow<Set<String>>(emptySet())

    /** [token]s of conversations that can currently be answered. */
    val available: StateFlow<Set<String>> get() = _available

    fun token(packageName: String, conversationKey: String): String = "$packageName\u0000$conversationKey"

    /**
     * Called for every notification the listener admits. Replaces the stored action for the same notification. A
     * notification without a reply action leaves an earlier one alone (the chat app may swap actions while sending).
     */
    fun register(sbn: StatusBarNotification) {
        val key = sbn.key ?: return
        val pkg = sbn.packageName ?: return
        val action = pickReplyAction(sbn.notification?.actions) ?: return
        val pending = action.actionIntent ?: return
        val inputs = action.remoteInputs ?: return
        synchronized(lock) {
            val previous = entries.remove(key)
            entries[key] = Entry(pkg, pending, inputs, previous?.conversationKey)
            while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
            publishLocked()
        }
    }

    /** Called after a notification was stored: ties its reply action to the conversation it belongs to. */
    fun bind(notificationKey: String, conversationKey: String) {
        synchronized(lock) {
            val entry = entries[notificationKey] ?: return
            entry.conversationKey = conversationKey
            publishLocked()
        }
    }

    fun clear() {
        synchronized(lock) {
            if (entries.isEmpty()) return
            entries.clear()
            publishLocked()
        }
    }

    /** Sends [text] through the chat app's own reply action. False when there is no action or it was cancelled. */
    fun send(context: Context, packageName: String, conversationKey: String, text: String): Boolean {
        val entry = synchronized(lock) {
            entries.values.lastOrNull { it.packageName == packageName && it.conversationKey == conversationKey }
        } ?: return false
        return try {
            val results = Bundle()
            for (input in entry.inputs) {
                if (input.allowFreeFormInput) results.putCharSequence(input.resultKey, text)
            }
            val fill = Intent()
            RemoteInput.addResultsToIntent(entry.inputs, fill, results)
            entry.action.send(context, 0, fill)
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun publishLocked() {
        _available.value = entries.values.mapNotNullTo(HashSet()) { e -> e.conversationKey?.let { token(e.packageName, it) } }
    }

    private fun pickReplyAction(actions: Array<Notification.Action>?): Notification.Action? {
        if (actions.isNullOrEmpty()) return null
        val candidates = actions.filter { a ->
            a.actionIntent != null && a.remoteInputs?.any { it.allowFreeFormInput } == true
        }
        if (candidates.isEmpty()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            candidates.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }?.let { return it }
        }
        return candidates.first()
    }
}
