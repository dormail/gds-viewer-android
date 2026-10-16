package com.example.gdsviewer.data

import android.content.Context
import android.content.SharedPreferences

/**
 * The app's one SharedPreferences file (a small key/value store kept on disk).
 * Every setting lives here under its own key, e.g. [RecentFiles] uses "recent_files".
 * New settings (such as a theme choice) should reuse this file instead of creating another.
 */
object AppPrefs {
    private const val FILE_NAME = "gds_viewer_settings"

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
