package com.riyadm.unsendvault.capture

import android.content.Intent
import kotlinx.coroutines.flow.StateFlow

/** Notification-access state exposed to the UI and widgets. Implemented by the capture layer. */
interface ListenerStatus {
    /** True when the user has granted Notification Access to UnsendVault's listener component. */
    fun isAccessGranted(): Boolean

    /** True while the system has the listener bound (between onListenerConnected and onListenerDisconnected). */
    val connected: StateFlow<Boolean>

    /** Asks the system to rebind the listener (API 24+), e.g. after an OEM killed it. No-op without access. */
    fun requestRebind()

    /** Intent that opens the best available Notification Access settings screen for this device/API level. */
    fun accessSettingsIntent(): Intent
}
