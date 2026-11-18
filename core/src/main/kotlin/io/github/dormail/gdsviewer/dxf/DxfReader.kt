package io.github.dormail.gdsviewer.dxf

import io.github.dormail.gdsviewer.db.Library
import io.github.dormail.gdsviewer.gds.GdsFormatException
import java.io.InputStream

/** Reads ASCII DXF files into a [Library]. (Placeholder: being implemented.) */
object DxfReader {
    /** Same contract as [io.github.dormail.gdsviewer.db.GdsReader.read]. */
    fun read(input: InputStream, isCancelled: () -> Boolean = { false }, onProgress: ((Long) -> Unit)? = null): Library =
        throw GdsFormatException("DXF files are not supported yet.")
}
