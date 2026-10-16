package com.example.gdsviewer.data

import android.content.Context

/** The user's theme choice: follow the phone's dark mode setting, or force light or dark. */
enum class ThemeMode(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark"),
    ;

    companion object {
        private const val KEY = "theme"

        /** Reads the saved choice from [AppPrefs]; defaults to [SYSTEM]. */
        fun load(context: Context): ThemeMode {
            val saved = AppPrefs.of(context).getString(KEY, null)
            return entries.firstOrNull { it.name == saved } ?: SYSTEM
        }

        fun save(context: Context, mode: ThemeMode) {
            AppPrefs.of(context).edit().putString(KEY, mode.name).apply()
        }
    }
}
