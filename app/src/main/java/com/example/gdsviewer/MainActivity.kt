package com.example.gdsviewer

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import com.example.gdsviewer.ui.ViewerScreen
import com.example.gdsviewer.ui.ViewerViewModel
import com.example.gdsviewer.ui.theme.GdsViewerTheme

/** The app's single screen: hosts the Compose UI. */
class MainActivity : ComponentActivity() {
    // Same instance that ViewerScreen() gets via viewModel() (activity-scoped).
    private val vm: ViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the status/navigation bars; light icons on a transparent background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent { GdsViewerTheme { ViewerScreen() } }
        // Only on a fresh start: after rotation/folding the ViewModel still holds the document.
        if (savedInstanceState == null) handleIntent(intent)
    }

    // Called when the app is already running and another "Open with" arrives.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Opens the file from an "Open with" (VIEW) or "Share" (SEND) intent; ignores a normal launch. */
    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) vm.openUri(uri)
    }
}
