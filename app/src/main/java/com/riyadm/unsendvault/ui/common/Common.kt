package com.riyadm.unsendvault.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.riyadm.unsendvault.appContainer
import com.riyadm.unsendvault.di.AppContainer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

/** Creates a ViewModel scoped to the current ViewModelStoreOwner (nav back-stack entry) from the [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalContext.current.appContainer
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

/**
 * Invoked right before the app launches a user-initiated external activity (system picker, share sheet, settings),
 * so the app lock can grant a short grace period instead of locking the user out mid-task.
 */
val LocalExternalActivityGuard = staticCompositionLocalOf<() -> Unit> { {} }

/** Insets screens must still apply themselves; the app chrome (navigation bar / rail) consumes the rest. */
val LocalScreenInsets = staticCompositionLocalOf<WindowInsets> { WindowInsets(0, 0, 0, 0) }

val defaultScreenInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/** Starts each intent in order until one resolves. Returns false when none could be opened. */
fun Context.startFirstAvailable(vararg intents: Intent): Boolean {
    for (intent in intents) {
        if (!(this is android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }
    return false
}

/** App details page; resolvable on every supported device, used as the final fallback for settings deep links. */
fun Context.appDetailsIntent(): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))

/** Locale- and 12/24h-aware formatting of epoch millis. */
class TimeFormatter(private val context: Context, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val time = DateFormat.getTimeFormat(context)
    private val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    private val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val fullDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

    fun time(millis: Long): String = time.format(Date(millis))

    fun dateTime(millis: Long): String = dateTime.format(Instant.ofEpochMilli(millis).atZone(zone))

    fun date(millis: Long): String = date.format(Instant.ofEpochMilli(millis).atZone(zone))

    fun dayHeader(millis: Long, today: LocalDate = LocalDate.now(zone)): String {
        val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        return when (day) {
            // "Today" / "Yesterday", localized by the platform.
            today, today.minusDays(1) ->
                DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()
            else -> fullDate.format(day)
        }
    }

    /** "14:32" today, otherwise a short date + time; suitable for list rows. */
    fun short(millis: Long, today: LocalDate = LocalDate.now(zone)): String {
        val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        return if (day == today) time(millis) else DateUtils.formatDateTime(
            context, millis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or
                (if (day.year == today.year) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR),
        )
    }

    fun localDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}

@Composable
fun rememberTimeFormatter(): TimeFormatter {
    val context = LocalContext.current
    return remember(context) { TimeFormatter(context) }
}

/** True when the user turned animations off (Developer options / accessibility "Remove animations"). */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
