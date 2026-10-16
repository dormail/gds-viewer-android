package com.example.gdsviewer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * One entry of the "recent files" list.
 *
 * @property uri the content:// address the file was opened from.
 * @property cachedPath absolute path of our private copy, or null if the [uri] itself stays readable.
 * @property sizeBytes size of the private copy (null when there is no copy).
 */
data class RecentFile(
    val id: String,
    val uri: String,
    val name: String,
    val openedAt: Long,
    val cachedPath: String?,
    val sizeBytes: Long?,
)

/**
 * The list of recently opened files, newest first, saved in [AppPrefs] as JSON.
 *
 * Android only lets an app read a file chosen by the user for a limited time. Two cases:
 *  - Files picked with the in-app picker can get a *persistable* URI permission: we keep just the
 *    URI and always read the current file.
 *  - "Open with" / "Share" usually grants only temporary access (SecurityException when asking
 *    for a persistable one), so we copy the file into `filesDir/recent/<id>` right away.
 *
 * All methods block (disk access): call them from a background thread (Dispatchers.IO).
 * [entries] is Compose state, so the UI redraws when it changes.
 */
class RecentFiles(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = AppPrefs.of(appContext)
    private val cacheDir = File(appContext.filesDir, "recent")
    private val lock = Any()

    /** Newest first, at most [MAX_ENTRIES]. */
    var entries: List<RecentFile> by mutableStateOf(load()); private set

    init {
        // Make the launcher shortcuts match the stored list after every app start.
        RecentShortcuts.update(appContext, entries)
    }

    /**
     * Remembers a file that was just opened successfully. Must be called while access to [uri]
     * is still valid. Does nothing if the file needs a copy but is larger than [MAX_FILE_BYTES].
     */
    suspend fun record(uri: Uri, name: String) {
        val uriString = uri.toString()
        val id = UUID.randomUUID().toString()
        var cached: File? = null
        try {
            try {
                appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                cached = copyToCache(uri, id) ?: return // too large: do not record
            } catch (e: IllegalArgumentException) {
                // Not a content:// URI, so there is nothing to persist: use a copy as well.
                cached = copyToCache(uri, id) ?: return
            }
            synchronized(lock) {
                val entry = RecentFile(id, uriString, name, System.currentTimeMillis(), cached?.absolutePath, cached?.length())
                // Same file again: replace the old entry. Keep the URI permission we just got.
                val old = entries.filter { it.uri == uriString }
                old.forEach { delete(it, release = false) }
                var list = listOf(entry) + entries.filter { it.uri != uriString }
                // Too many entries or too many bytes in copies: drop the oldest.
                while (list.size > MAX_ENTRIES || cachedTotal(list) > MAX_TOTAL_BYTES) {
                    delete(list.last(), release = true)
                    list = list.dropLast(1)
                }
                commit(list)
                cached = null // now owned by the list
            }
        } finally {
            cached?.delete() // something went wrong or we were cancelled: remove the stray copy
        }
    }

    /** Moves an entry to the top (it was opened again). No new copy is made. */
    fun touch(id: String) = synchronized(lock) {
        val entry = entries.find { it.id == id } ?: return@synchronized
        commit(listOf(entry.copy(openedAt = System.currentTimeMillis())) + entries.filter { it.id != id })
    }

    /** Removes an entry, deletes its copy and gives back the URI permission. */
    fun remove(id: String) = synchronized(lock) {
        val entry = entries.find { it.id == id } ?: return@synchronized
        delete(entry, release = true)
        commit(entries.filter { it.id != id })
    }

    fun find(id: String): RecentFile? = entries.find { it.id == id }

    /** Opens the file's bytes: from our copy if there is one, otherwise from the URI. */
    fun openStream(entry: RecentFile): InputStream {
        val path = entry.cachedPath
        if (path != null) return File(path).inputStream()
        return appContext.contentResolver.openInputStream(Uri.parse(entry.uri))
            ?: throw IOException("Cannot open the file.")
    }

    // ---------------------------------------------------------------- internals

    /** Copies [uri] to `recent/<id>`. Returns null (and leaves nothing behind) if it is too large. */
    private suspend fun copyToCache(uri: Uri, id: String): File? {
        cacheDir.mkdirs()
        val target = File(cacheDir, id)
        var ok = false
        try {
            val input = appContext.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the file.")
            input.use { src ->
                target.outputStream().use { dst ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive() // stop copying if a newer load cancelled us
                        val n = src.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_FILE_BYTES) return null
                        dst.write(buffer, 0, n)
                    }
                }
            }
            ok = true
            return target
        } finally {
            if (!ok) target.delete()
        }
    }

    private fun cachedTotal(list: List<RecentFile>) = list.sumOf { it.sizeBytes ?: 0L }

    private fun delete(entry: RecentFile, release: Boolean) {
        entry.cachedPath?.let { File(it).delete() }
        if (release) {
            try {
                appContext.contentResolver.releasePersistableUriPermission(
                    Uri.parse(entry.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: Exception) {
                // Nothing was granted (copy entry) or already gone: fine.
            }
        }
    }

    /** Publishes the new list, saves it and refreshes the launcher shortcuts. */
    private fun commit(list: List<RecentFile>) {
        entries = list
        val array = JSONArray()
        for (e in list) {
            array.put(
                JSONObject()
                    .put("id", e.id).put("uri", e.uri).put("name", e.name).put("openedAt", e.openedAt)
                    .put("cachedPath", e.cachedPath ?: JSONObject.NULL)
                    .put("sizeBytes", e.sizeBytes ?: JSONObject.NULL),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
        RecentShortcuts.update(appContext, list)
    }

    private fun load(): List<RecentFile> = try {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            RecentFile(
                id = o.getString("id"), uri = o.getString("uri"), name = o.getString("name"),
                openedAt = o.getLong("openedAt"),
                cachedPath = if (o.isNull("cachedPath")) null else o.getString("cachedPath"),
                sizeBytes = if (o.isNull("sizeBytes")) null else o.getLong("sizeBytes"),
            )
        }
    } catch (e: Exception) {
        emptyList() // damaged data: start with an empty list
    }

    private companion object {
        const val KEY = "recent_files"
        const val MAX_ENTRIES = 8
        const val MAX_FILE_BYTES = 100L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 300L * 1024 * 1024
    }
}
