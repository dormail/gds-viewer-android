package com.example.gdsviewer.bench

import com.example.gdsviewer.db.GdsReader
import com.example.gdsviewer.geometry.Affine
import com.example.gdsviewer.scene.LayerStats
import com.example.gdsviewer.scene.LibraryIndex
import com.example.gdsviewer.scene.MeshCache
import com.example.gdsviewer.scene.SceneBuilder
import com.example.gdsviewer.scene.SceneRequest
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.min

/**
 * Opens big GDS files from the folder in the STRESS_DIR environment variable (made with
 * tools/make_stress_gds.py) and prints how long each step takes. Skipped when STRESS_DIR is not set.
 */
class StressBenchmark {
    @Test
    fun openAndBuildFirstView() {
        val dir = System.getenv("STRESS_DIR")
        assumeTrue("STRESS_DIR not set", dir != null)
        val files = File(dir!!).listFiles { f -> f.name.endsWith(".gds") || f.name.endsWith(".gds.gz") }!!.sorted()
        for (file in files) {
            val t0 = System.nanoTime()
            val lib = file.inputStream().use { GdsReader.read(it) }
            val t1 = System.nanoTime()
            val index = LibraryIndex(lib)
            val top = index.defaultTopCell()!!
            val t2 = System.nanoTime()
            LayerStats.shapeCounts(lib, top.index)
            val frame = index.frame(top.index)
            // A phone-sized view (1080 x 2400 px) fitting the whole cell, as the app does.
            val w = 1080
            val h = 2400
            val s = 0.9 * min(w / (frame.maxX - frame.minX), h / (frame.maxY - frame.minY))
            val view = Affine.translate(w / 2.0, h / 2.0) * Affine.scale(s, -s)
            val builder = SceneBuilder(index, MeshCache(lib))
            val times = (1..3).map {
                val a = System.nanoTime()
                val list = builder.build(SceneRequest(frame, view, w, h))
                val ms = (System.nanoTime() - a) / 1_000_000
                "${ms} ms (${list.groups.size} chunks, ${list.stats.instances} inst, ${list.stats.tinyItems} tiny, " +
                    "trav ${list.stats.traversalMillis} ms, mesh ${list.stats.meshMillis} ms)"
            }
            println(
                "BENCH ${file.name}: read ${(t1 - t0) / 1_000_000} ms, index ${(t2 - t1) / 1_000_000} ms, " +
                    "${lib.cells.size} cells; first view: ${times.joinToString(" | ")}",
            )
        }
    }
}
