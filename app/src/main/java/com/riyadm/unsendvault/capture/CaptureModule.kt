package com.riyadm.unsendvault.capture

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.capture.adapter.AdapterRegistry
import com.riyadm.unsendvault.capture.adapter.AppLabelResolver
import com.riyadm.unsendvault.capture.engine.CaptureSink
import com.riyadm.unsendvault.capture.engine.DefaultCaptureEngine
import com.riyadm.unsendvault.capture.filter.SensitiveContentFilter
import com.riyadm.unsendvault.capture.parser.CapturePlaceholders
import com.riyadm.unsendvault.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

/** Capture engine used by [VaultNotificationListener]. Both calls must return immediately (they only enqueue work). */
interface CaptureEngine {
    fun onPosted(snapshot: NotificationSnapshot)
    fun onRemoved(event: RemovalEvent)
}

object CaptureModule {
    fun createEngine(container: AppContainer): CaptureEngine {
        val context = container.context
        val placeholders = placeholders(context)
        return DefaultCaptureEngine(
            scope = container.appScope,
            dispatcher = Dispatchers.Default.limitedParallelism(1),
            // Latest persisted value: SettingsRepository.current may still be the default before DataStore loads.
            settingsProvider = { container.settingsRepository.settings.first() },
            sink = object : CaptureSink {
                override suspend fun ingest(parsed: ParsedNotification, capturedAt: Long) {
                    container.vaultRepository.ingest(parsed, capturedAt)
                }

                override suspend fun recordRemoval(event: RemovalEvent) {
                    container.vaultRepository.recordRemoval(event)
                }
            },
            registry = AdapterRegistry.createDefault(placeholders),
            labels = AppLabelResolver { pkg -> packageLabel(context, pkg) },
            sensitiveFilter = SensitiveContentFilter(placeholders),
            ownPackage = context.packageName,
        )
    }

    fun createListenerStatus(context: Context): ListenerStatus = AndroidListenerStatus(context)

    /** Placeholder texts written into storage, localised at capture time. */
    fun placeholders(context: Context): CapturePlaceholders {
        val defaults = CapturePlaceholders()
        fun str(id: Int, fallback: String) = try {
            context.getString(id)
        } catch (e: Exception) {
            fallback
        }
        return CapturePlaceholders(
            selfSender = str(R.string.capture_self_sender, defaults.selfSender),
            systemRedacted = str(R.string.capture_placeholder_system_redacted, defaults.systemRedacted),
            otpHidden = str(R.string.capture_placeholder_otp, defaults.otpHidden),
            sensitiveHidden = str(R.string.capture_placeholder_sensitive, defaults.sensitiveHidden),
            mediaPhoto = str(R.string.capture_media_photo, defaults.mediaPhoto),
            mediaVideo = str(R.string.capture_media_video, defaults.mediaVideo),
            mediaAudio = str(R.string.capture_media_audio, defaults.mediaAudio),
            mediaAttachment = str(R.string.capture_media_attachment, defaults.mediaAttachment),
        )
    }

    /** Label of a single package (never enumerates installed packages). Null when hidden by package visibility. */
    private fun packageLabel(context: Context, packageName: String): String? {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }
        return pm.getApplicationLabel(info).toString()
    }
}
