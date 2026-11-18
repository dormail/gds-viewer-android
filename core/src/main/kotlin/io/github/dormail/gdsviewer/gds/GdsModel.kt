package io.github.dormail.gdsviewer.gds

/**
 * Identifies a drawing layer.
 *
 * GDS layers are a layer number plus a datatype, written as "layer/datatype" (e.g. "1/0"); their
 * [name] is null. DXF layers have a [name] instead (shown as is). A DXF name that is a whole number
 * from 0 to 65535 (e.g. a gray value "83") also sets [layer] to that number, so such layers sort
 * by value; other names get [layer] -1 and sort before them, by name.
 *
 * [file] is 0 for a single file. When several files are shown on top of each other (overlay), the
 * layers of file k (counting from 1) have `file = k` and are written with a suffix, e.g. "83/0@2"
 * or "Defpoints@3". Layers sort by file first, so each file's layers are grouped and later files
 * are drawn on top.
 */
data class LayerKey(val layer: Int, val datatype: Int, val name: String? = null, val file: Int = 0) : Comparable<LayerKey> {
    override fun compareTo(other: LayerKey): Int = when {
        file != other.file -> file.compareTo(other.file)
        layer != other.layer -> layer.compareTo(other.layer)
        datatype != other.datatype -> datatype.compareTo(other.datatype)
        else -> compareValues(name, other.name)
    }

    override fun toString() = (name ?: "$layer/$datatype") + if (file > 0) "@$file" else ""
}

/** Thrown when a file is not a valid layout file (GDSII or DXF). The message is shown to the user. */
class GdsFormatException(message: String) : Exception(message)
