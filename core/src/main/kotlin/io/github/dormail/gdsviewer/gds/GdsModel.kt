package io.github.dormail.gdsviewer.gds

/**
 * Identifies a drawing layer.
 *
 * GDS layers are a layer number plus a datatype, written as "layer/datatype" (e.g. "1/0"); their
 * [name] is null. DXF layers have a [name] instead (shown as is). A DXF name that is a whole number
 * from 0 to 65535 (e.g. a gray value "83") also sets [layer] to that number, so such layers sort
 * by value; other names get [layer] -1 and sort before them, by name.
 */
data class LayerKey(val layer: Int, val datatype: Int, val name: String? = null) : Comparable<LayerKey> {
    override fun compareTo(other: LayerKey): Int = when {
        layer != other.layer -> layer.compareTo(other.layer)
        datatype != other.datatype -> datatype.compareTo(other.datatype)
        else -> compareValues(name, other.name)
    }

    override fun toString() = name ?: "$layer/$datatype"
}

/** Thrown when a file is not a valid layout file (GDSII or DXF). The message is shown to the user. */
class GdsFormatException(message: String) : Exception(message)
