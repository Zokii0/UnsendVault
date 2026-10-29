package com.riyadm.unsendvault.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Suspends while the screen is off. Widgets are invisible then, so the sync engine holds its dirty set and renders
 * once on ACTION_SCREEN_ON instead of starting a Glance session per message batch at night or in Doze. The receiver is
 * registered only while someone is waiting, and nothing polls: no timer, alarm or wake lock is involved.
 */
internal class ScreenOnGate(context: Context) {
    private val app = context.applicationContext

    suspend fun await() {
        val power = app.getSystemService(PowerManager::class.java) ?: return
        if (power.isInteractive) return
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            lateinit var receiver: BroadcastReceiver
            fun finish() {
                if (!done.compareAndSet(false, true)) return
                try {
                    app.unregisterReceiver(receiver)
                } catch (e: IllegalArgumentException) {
                    // Already unregistered.
                }
                if (cont.isActive) cont.resume(Unit)
            }
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = finish()
            }
            try {
                ContextCompat.registerReceiver(
                    app, receiver, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            } catch (e: RuntimeException) {
                done.set(true)
                cont.resume(Unit) // can't wait for the screen: render now, like before
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { finish() }
            // The screen may have turned on between the check and the registration.
            if (power.isInteractive) finish()
        }
    }
}
