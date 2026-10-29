package com.riyadm.unsendvault.widget

import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProviders
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import com.riyadm.unsendvault.R
import java.text.NumberFormat

/** Fallback scheme for API < 31 (no dynamic color): navy #1B3A5C / teal #7FD1C7, light and dark. */
internal object WidgetColors {
    private val navy = Color(0xFF1B3A5C)
    private val teal = Color(0xFF7FD1C7)

    val fallback: ColorProviders = ColorProviders(
        light = lightColorScheme(
            primary = navy, onPrimary = Color.White,
            primaryContainer = Color(0xFFCFE6F2), onPrimaryContainer = Color(0xFF0B1F33),
            secondary = Color(0xFF2E6A63), onSecondary = Color.White,
            secondaryContainer = teal, onSecondaryContainer = Color(0xFF00201C),
            background = Color(0xFFF4F7FB), onBackground = Color(0xFF1A1C1F),
            surface = Color(0xFFF4F7FB), onSurface = Color(0xFF1A1C1F),
            surfaceVariant = Color(0xFFDDE3EA), onSurfaceVariant = Color(0xFF44474E),
            outline = Color(0xFF74777F),
        ),
        dark = darkColorScheme(
            primary = teal, onPrimary = Color(0xFF00201C),
            primaryContainer = navy, onPrimaryContainer = Color(0xFFD3E4FF),
            secondary = Color(0xFFA6C8FF), onSecondary = Color(0xFF0B1F33),
            secondaryContainer = Color(0xFF244A72), onSecondaryContainer = Color(0xFFD3E4FF),
            background = Color(0xFF141A22), onBackground = Color(0xFFE2E2E6),
            surface = Color(0xFF141A22), onSurface = Color(0xFFE2E2E6),
            surfaceVariant = Color(0xFF43474E), onSurfaceVariant = Color(0xFFC4C6CF),
            outline = Color(0xFF8E9099),
        ),
    )
}

/** The handful of color roles widgets use. */
internal class WidgetPalette(
    val background: ColorProvider,
    val onBackground: ColorProvider,
    val onBackgroundVariant: ColorProvider,
    val accent: ColorProvider,
    val accentContainer: ColorProvider,
    val onAccentContainer: ColorProvider,
)

private val supportsDynamicWidgets: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
internal fun WidgetTheme(content: @Composable () -> Unit) {
    GlanceTheme(colors = if (supportsDynamicWidgets) GlanceTheme.colors else WidgetColors.fallback, content = content)
}

/**
 * API 31+: roles from the (dynamic) Glance theme. Older: fixed day/night pairs mirroring colors_widget.xml (values and values-night).
 * Pre-31 Glance resolves day/night when the widget is rendered, so the rounded background drawable is tinted with the
 * same provider: text and background always come from the same night-mode decision (never dark-on-dark).
 */
@Composable
internal fun widgetPalette(): WidgetPalette = if (supportsDynamicWidgets) {
    val c = GlanceTheme.colors
    WidgetPalette(c.widgetBackground, c.onSurface, c.onSurfaceVariant, c.primary, c.primaryContainer, c.onPrimaryContainer)
} else {
    LegacyPalette
}

private val LegacyPalette = WidgetPalette(
    background = ColorProvider(day = Color(0xFFF4F7FB), night = Color(0xFF141A22)),
    onBackground = ColorProvider(day = Color(0xFF1A1C1F), night = Color(0xFFE2E2E6)),
    onBackgroundVariant = ColorProvider(day = Color(0xFF44474E), night = Color(0xFFC4C6CF)),
    accent = ColorProvider(day = Color(0xFF1B3A5C), night = Color(0xFF7FD1C7)),
    accentContainer = ColorProvider(day = Color(0xFFCFE6F2), night = Color(0xFF1B3A5C)),
    onAccentContainer = ColorProvider(day = Color(0xFF0B1F33), night = Color(0xFFD3E4FF)),
)

/** Rounded background: system radius on API 31+, a tinted rounded drawable before that. */
internal fun GlanceModifier.roundedBackground(color: ColorProvider, @DimenRes radius: Int, @DrawableRes legacyShape: Int): GlanceModifier =
    if (supportsDynamicWidgets) {
        background(color).cornerRadius(radius)
    } else {
        background(ImageProvider(legacyShape), colorFilter = ColorFilter.tint(color))
    }

// ---- text helpers -------------------------------------------------------------------------------------------------

@Composable
internal fun str(@StringRes id: Int, vararg args: Any): String = LocalContext.current.getString(id, *args)

/** Quantity string whose single placeholder is the locale-formatted [count]. */
@Composable
internal fun countText(@PluralsRes id: Int, count: Int): String = countText(LocalContext.current, id, count)

internal fun countText(context: Context, @PluralsRes id: Int, count: Int): String =
    context.resources.getQuantityString(id, count, formatCount(count))

internal fun formatCount(n: Int): String = NumberFormat.getIntegerInstance().format(n)

/** "Just now" under a minute (and for clock skew), otherwise an abbreviated, localized relative time. */
internal fun relativeTime(context: Context, then: Long, now: Long): String =
    if (now - then < DateUtils.MINUTE_IN_MILLIS) {
        context.getString(R.string.widget_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(then, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }

/** Rows are sized for the default font; scale them so large font settings don't overflow the widget. */
@Composable
internal fun fontScale(): Float = LocalContext.current.resources.configuration.fontScale.coerceIn(1f, 2f)

// ---- building blocks ----------------------------------------------------------------------------------------------

internal val WidgetPadding: Dp = 12.dp

@Composable
internal fun WidgetRoot(
    onClick: Action?,
    contentDescription: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = widgetPalette()
    var modifier = GlanceModifier.fillMaxSize().appWidgetBackground()
        .roundedBackground(palette.background, R.dimen.widget_corner_radius, R.drawable.widget_background)
        .padding(WidgetPadding)
    if (onClick != null) modifier = modifier.clickable(onClick)
    if (contentDescription != null) modifier = modifier.semantics { this.contentDescription = contentDescription }
    Column(modifier = modifier, content = content)
}

@Composable
internal fun WidgetHeader(
    @DrawableRes icon: Int,
    title: String,
    trailingIcon: Int? = null,
    trailingIconDescription: String? = null,
    onClick: Action? = null,
) {
    val palette = widgetPalette()
    var modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)
    if (onClick != null) modifier = modifier.clickable(onClick)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        WidgetIcon(icon, null, palette.accent, 18.dp)
        Spacer(GlanceModifier.width(6.dp))
        Text(
            text = title,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(color = palette.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        if (trailingIcon != null) WidgetIcon(trailingIcon, trailingIconDescription, palette.onBackgroundVariant, 16.dp)
    }
}

@Composable
internal fun WidgetIcon(@DrawableRes icon: Int, contentDescription: String?, tint: ColorProvider, size: Dp) {
    Image(
        provider = ImageProvider(icon),
        contentDescription = contentDescription,
        modifier = GlanceModifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

/** Centered empty / error / locked message filling the remaining space. */
@Composable
internal fun StatusMessage(
    title: String,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    iconDescription: String? = null,
) {
    val palette = widgetPalette()
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            WidgetIcon(icon, iconDescription, palette.accent, 22.dp)
            Spacer(GlanceModifier.height(4.dp))
        }
        Text(
            text = title,
            style = TextStyle(color = palette.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
            maxLines = 2,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = TextStyle(color = palette.onBackgroundVariant, fontSize = 12.sp, textAlign = TextAlign.Center),
                maxLines = 3,
            )
        }
    }
}

@Composable
internal fun FailedContent(compact: Boolean = false) {
    WidgetRoot(onClick = WidgetIntents.openDestination(WidgetIntents.DESTINATION_HOME)) {
        if (compact) {
            StatusMessage(title = str(R.string.widget_open_app))
        } else {
            StatusMessage(
                title = str(R.string.widget_open_app),
                subtitle = str(R.string.widget_error_title),
                icon = R.drawable.widget_ic_warning,
            )
        }
    }
}

/** One single-line list row: leading label (takes remaining width) and a trailing secondary label. */
@Composable
internal fun ListRow(
    label: String,
    trailing: String?,
    onClick: Action,
    contentDescription: String,
    secondLine: String? = null,
    secondLineMuted: Boolean = false,
) {
    val palette = widgetPalette()
    Column(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp).clickable(onClick)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = palette.onBackground, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            if (trailing != null) {
                Spacer(GlanceModifier.width(6.dp))
                Text(text = trailing, style = TextStyle(color = palette.onBackgroundVariant, fontSize = 12.sp), maxLines = 1)
            }
        }
        if (secondLine != null) {
            Text(
                text = secondLine,
                style = TextStyle(
                    color = palette.onBackgroundVariant,
                    fontSize = 12.sp,
                    fontStyle = if (secondLineMuted) androidx.glance.text.FontStyle.Italic else androidx.glance.text.FontStyle.Normal,
                ),
                maxLines = 1,
            )
        }
    }
}

internal val NumberSize: TextUnit = 28.sp
