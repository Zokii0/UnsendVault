package com.riyadm.unsendvault.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.riyadm.unsendvault.BuildConfig
import com.riyadm.unsendvault.R
import com.riyadm.unsendvault.ui.components.AppScaffold

/** Third-party components bundled in the app, with their licenses. */
data class LicenseEntry(val name: String, val owner: String, val license: String)

val OpenSourceLicenses: List<LicenseEntry> = listOf(
    LicenseEntry("AndroidX (Activity, Biometric, Core, DataStore, Fragment, Lifecycle, Navigation, Room, SQLite, WorkManager)", "The Android Open Source Project", "Apache License 2.0"),
    LicenseEntry("Jetpack Compose, Material 3 and Material Icons", "The Android Open Source Project", "Apache License 2.0"),
    LicenseEntry("Jetpack Glance", "The Android Open Source Project", "Apache License 2.0"),
    LicenseEntry("Kotlin standard library", "JetBrains s.r.o. and contributors", "Apache License 2.0"),
    LicenseEntry("kotlinx.coroutines", "JetBrains s.r.o. and contributors", "Apache License 2.0"),
    LicenseEntry("kotlinx.serialization", "JetBrains s.r.o. and contributors", "Apache License 2.0"),
)

@Composable
fun AboutRoute(onBack: () -> Unit) {
    AppScaffold(title = stringResource(R.string.about_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, Build.VERSION.RELEASE.orEmpty(), Build.VERSION.SDK_INT))
                Text(stringResource(R.string.about_intro), style = MaterialTheme.typography.bodyLarge)

                Heading(stringResource(R.string.about_saves_title))
                Bullets(stringArrayResource(R.array.about_saves).toList())

                Heading(stringResource(R.string.about_not_saved_title))
                Bullets(stringArrayResource(R.array.about_not_saved).toList())

                Heading(stringResource(R.string.about_deleted_title))
                Text(stringResource(R.string.about_deleted_body), style = MaterialTheme.typography.bodyMedium)

                Heading(stringResource(R.string.about_android_title))
                Bullets(stringArrayResource(R.array.about_android_notes).toList())

                Heading(stringResource(R.string.about_privacy_title))
                Bullets(stringArrayResource(R.array.about_privacy).toList())

                Heading(stringResource(R.string.about_licenses_title))
                OpenSourceLicenses.forEach { entry ->
                    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                        Text(entry.name, style = MaterialTheme.typography.titleSmall)
                        Text("${entry.owner} · ${entry.license}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
                Text(stringResource(R.string.about_trademarks), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp).semantics { heading() },
    )
}

@Composable
private fun Bullets(points: List<String>) {
    points.forEach { point ->
        Row {
            Text("•", color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(point, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}
