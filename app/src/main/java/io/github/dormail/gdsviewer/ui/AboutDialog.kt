package io.github.dormail.gdsviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/** App name, version, author, links and credits; scrolls when it does not fit. */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GDS Viewer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (version.isNotEmpty()) {
                    Text("Version $version", style = MaterialTheme.typography.titleSmall)
                }
                Text("A read-only viewer for GDSII chip layouts.", Modifier.padding(top = 8.dp))
                Text("Made by Matthias Maile.", Modifier.padding(top = 8.dp))
                Text(
                    "Built entirely with Claude, Anthropic's AI coding assistant. The author is a researcher " +
                        "who works with GDS files a lot, not an Android developer; the app is also an " +
                        "experiment in what coding agents can do.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AboutLink("Source code on GitHub", "https://github.com/dormail/gds-viewer-android")
                AboutLink("Report a problem", "https://github.com/dormail/gds-viewer-android/issues")
                AboutLink("Privacy policy", "https://dormail.github.io/gds-viewer-android/privacy/")
                Text("Privacy: the app collects no data and has no internet access.", Modifier.padding(top = 8.dp))
                Text("License: MIT", Modifier.padding(top = 8.dp))
                AboutLink("Read the license", "https://github.com/dormail/gds-viewer-android/blob/main/LICENSE")
                Text(
                    "Credits", Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "The handling of large layouts follows the design of KLayout by Matthias Köfferlein " +
                        "(no KLayout code is used).",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                AboutLink("KLayout on GitHub", "https://github.com/KLayout/klayout")
                Text(
                    "Icons: Material Symbols by Google, Apache License 2.0.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                AboutLink("Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0")
                Text(
                    "Built with Kotlin, Jetpack Compose and AndroidX, Apache License 2.0.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A tappable text row that opens [url] in the browser; does nothing if no browser can handle it. */
@Composable
private fun AboutLink(label: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Text(
        label,
        modifier = Modifier.fillMaxWidth()
            .clickable { try { uriHandler.openUri(url) } catch (e: Exception) { } }
            .padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
    )
}
