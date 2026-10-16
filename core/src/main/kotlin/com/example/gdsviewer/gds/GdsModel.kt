package com.example.gdsviewer.gds

/** Identifies a drawing layer: GDS layer number plus datatype, written as "layer/datatype" (e.g. "1/0"). */
data class LayerKey(val layer: Int, val datatype: Int) : Comparable<LayerKey> {
    override fun compareTo(other: LayerKey): Int =
        if (layer != other.layer) layer.compareTo(other.layer) else datatype.compareTo(other.datatype)

    override fun toString() = "$layer/$datatype"
}

/** Thrown when a file is not a valid GDSII file. The message is shown to the user. */
class GdsFormatException(message: String) : Exception(message)
