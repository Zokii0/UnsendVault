package com.riyadm.unsendvault.capture

import android.util.Log
import com.riyadm.unsendvault.BuildConfig

/** Debug-only logging for the capture layer. Callers must never pass message content, titles or names. */
internal object CaptureLog {
    private const val TAG = "UnsendVaultCapture"

    fun d(message: String) {
        if (BuildConfig.DEBUG) {
            try {
                Log.d(TAG, message)
            } catch (e: RuntimeException) {
                // android.util.Log is not available in plain JVM unit tests.
            }
        }
    }
}
