package io.github.dormail.gdsviewer.db

import io.github.dormail.gdsviewer.dxf.DxfReader
import io.github.dormail.gdsviewer.gds.GdsFormatException
import java.io.InputStream
import java.io.PushbackInputStream

/**
 * Reads a layout file of any supported format. The format is detected from the first bytes, not
 * from the file name (files from other apps often have no usable name):
 * - gzip data or anything else that is not DXF: GDSII ([GdsReader], which unpacks .gds.gz itself
 *   and reports "This is not a GDSII file" for unknown data),
 * - text starting with a DXF group code ("0" followed by "SECTION", or a "999" comment): ASCII DXF ([DxfReader]),
 * - binary DXF: refused with a message.
 */
object LayoutReader {
    private const val PEEK = 64
    private const val BINARY_DXF = "AutoCAD Binary DXF"

    /** Same contract as [GdsReader.read]: does not close [input], throws [GdsFormatException] with a user-readable message. */
    fun read(input: InputStream, isCancelled: () -> Boolean = { false }, onProgress: ((Long) -> Unit)? = null): Library {
        val pushback = PushbackInputStream(input, PEEK)
        val head = ByteArray(PEEK)
        var got = 0
        while (got < PEEK) {
            val n = pushback.read(head, got, PEEK - got)
            if (n < 0) break
            got += n
        }
        if (got > 0) pushback.unread(head, 0, got)
        return when (detect(head, got)) {
            Format.DXF -> DxfReader.read(pushback, isCancelled, onProgress)
            Format.BINARY_DXF -> throw GdsFormatException("Binary DXF files are not supported. Please save the file as ASCII DXF.")
            Format.GDS -> GdsReader.read(pushback, isCancelled, onProgress)
        }
    }

    internal enum class Format { GDS, DXF, BINARY_DXF }

    /** The format of a file starting with [head] (its first [count] bytes). */
    internal fun detect(head: ByteArray, count: Int): Format {
        val text = String(head, 0, count, Charsets.ISO_8859_1)
        if (text.startsWith(BINARY_DXF)) return Format.BINARY_DXF
        // ASCII DXF: lines of group code / value pairs. Skip a UTF-8 byte order mark and blank space.
        val lines = text.removePrefix("ï»¿").trimStart().split('\n').map { it.trim() }
        if (lines.size >= 2 && lines[0] == "0" && lines[1] == "SECTION") return Format.DXF
        if (lines.isNotEmpty() && lines[0] == "999") return Format.DXF
        return Format.GDS
    }
}
