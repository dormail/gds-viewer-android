package io.github.dormail.gdsviewer.data

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.dormail.gdsviewer.MainActivity
import io.github.dormail.gdsviewer.R

/**
 * Publishes the newest recent files as launcher shortcuts (shown when you long-press the app
 * icon). "Dynamic" shortcuts are created by the app at run time and can change at any time.
 */
object RecentShortcuts {
    /** Intent action that [MainActivity] handles by opening the recent file named in [EXTRA_ID]. */
    const val ACTION_OPEN_RECENT = "io.github.dormail.gdsviewer.OPEN_RECENT"
    const val EXTRA_ID = "recent_id"

    private const val MAX_SHORTCUTS = 4
    private const val MAX_LABEL = 25

    fun update(context: Context, entries: List<RecentFile>) {
        val shortcuts = entries.take(MAX_SHORTCUTS).map { e ->
            // A shortcut starts an explicit intent; the action is required for shortcut intents.
            val intent = Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_RECENT)
                .putExtra(EXTRA_ID, e.id)
            ShortcutInfoCompat.Builder(context, e.id)
                .setShortLabel(if (e.name.length <= MAX_LABEL) e.name else e.name.take(MAX_LABEL - 1) + "…")
                .setLongLabel(e.name)
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_file))
                .setIntent(intent)
                .build()
        }
        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (e: Exception) {
            // Shortcuts are a convenience (e.g. rate limiting can throw): never break the app.
        }
    }
}
