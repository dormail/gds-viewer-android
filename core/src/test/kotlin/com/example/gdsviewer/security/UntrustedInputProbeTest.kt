package com.example.gdsviewer.security

import com.example.gdsviewer.db.GdsReader
import com.example.gdsviewer.gds.GdsFormatException
import com.example.gdsviewer.gds.TestGdsWriter
import com.example.gdsviewer.geometry.Affine
import com.example.gdsviewer.scene.LayerStats
import com.example.gdsviewer.scene.LibraryIndex
import com.example.gdsviewer.scene.MeshCache
import com.example.gdsviewer.scene.SceneBuilder
import com.example.gdsviewer.scene.SceneRequest
import com.example.gdsviewer.scene.Snapping
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Probe for crashes / hangs / memory blow-ups on hostile GDS input. Prints one PROBE line per case.
 * Nothing is asserted: the outcome table is the product. Slow, so skipped unless the GDS_PROBE
 * environment variable is set:
 *   GDS_PROBE=1 ./gradlew :core:test --tests 'com.example.gdsviewer.security.UntrustedInputProbeTest' -i
 * (PROBE_ONLY=<substring> runs only the crafted cases whose name contains it.) The fast regression
 * tests for the same inputs are in [MaliciousInputTest].
 */
class UntrustedInputProbeTest {
    @Before
    fun onlyWhenAsked() = assumeTrue("GDS_PROBE not set", System.getenv("GDS_PROBE") != null)

    // ---------------------------------------------------------------- byte builders

    private fun i16(vararg v: Int) = ByteArray(v.size * 2) { (v[it / 2] shr (if (it % 2 == 0) 8 else 0)).toByte() }
    private fun i32(vararg v: Int) = ByteArray(v.size * 4) { (v[it / 4] shr (8 * (3 - it % 4))).toByte() }
    private fun str(s: String): ByteArray {
        val b = s.toByteArray(Charsets.ISO_8859_1)
        return if (b.size % 2 == 0) b else b + 0
    }
    private fun real(mant: Long, b0: Int): ByteArray {
        val b = ByteArray(8)
        b[0] = b0.toByte()
        for (i in 1..7) b[i] = (mant shr (8 * (7 - i))).toByte()
        return b
    }
    private val HUGE_REAL = real(0x10000000000000L, 0x7F) // ~ 1e75
    private val NEG_HUGE_REAL = real(0x10000000000000L, 0xFF)

    private class G {
        val w = TestGdsWriter()
        init { w.header(); w.bgnlib(); w.libname("L"); w.units() }
        fun rec(t: Int, d: Int, data: ByteArray = ByteArray(0)) = w.rec(t, d, data)
    }

    private fun G.cell(name: String, body: TestGdsWriter.() -> Unit) { w.bgnstr(name); w.body(); w.endstr() }
    private fun G.end(): ByteArray { w.endlib(); return w.bytes() }

    private fun TestGdsWriter.box10(layer: Int = 1, x: Int = 0, y: Int = 0, s: Int = 10) =
        boundary(layer, 0, x, y, x + s, y, x + s, y + s, x, y + s, x, y)

    /** AREF with arbitrary raw fields. */
    private fun TestGdsWriter.arefRaw(
        name: String, cols: Int, rows: Int, xy: IntArray, mag: ByteArray? = null, angle: ByteArray? = null, mirror: Boolean = false,
    ) {
        rec(0x0B, 0); rec(0x12, 6, str(name))
        rec(0x1A, 1, i16(if (mirror) 0x8000 else 0))
        if (mag != null) rec(0x1B, 5, mag)
        if (angle != null) rec(0x1C, 5, angle)
        rec(0x13, 2, i16(cols, rows)); rec(0x10, 3, i32(*xy)); rec(0x11, 0)
    }

    private fun TestGdsWriter.srefRaw(name: String, x: Int, y: Int, mag: ByteArray? = null, angle: ByteArray? = null) {
        rec(0x0A, 0); rec(0x12, 6, str(name))
        if (mag != null || angle != null) {
            rec(0x1A, 1, i16(0))
            if (mag != null) rec(0x1B, 5, mag)
            if (angle != null) rec(0x1C, 5, angle)
        }
        rec(0x10, 3, i32(x, y)); rec(0x11, 0)
    }

    private fun gz(raw: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        GZIPOutputStream(o).use { it.write(raw) }
        return o.toByteArray()
    }

    /** gzip of [prefix] + [block] repeated [times] + [suffix], never materialized in memory. */
    private fun gzRepeat(prefix: ByteArray, block: ByteArray, times: Int, suffix: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        object : GZIPOutputStream(o) { init { def.setLevel(Deflater.BEST_SPEED) } }.use {
            it.write(prefix)
            for (i in 0 until times) it.write(block)
            it.write(suffix)
        }
        return o.toByteArray()
    }

    private fun recBytes(type: Int, dt: Int, data: ByteArray): ByteArray {
        val l = data.size + 4
        return byteArrayOf((l shr 8).toByte(), l.toByte(), type.toByte(), dt.toByte()) + data
    }

    // ---------------------------------------------------------------- the pipeline

    class Ctl { @Volatile var cancel = false; @Volatile var stage = "start" }

    /** Read -> index -> top cell -> frame -> fit view + zoomed views (SceneBuilder, MeshCache) -> snapping. Returns a short note. */
    private fun pipeline(bytes: ByteArray, ctl: Ctl): String {
        val cancelled = { ctl.cancel }
        ctl.stage = "read"
        val lib = GdsReader.read(ByteArrayInputStream(bytes), cancelled)
        if (lib.cells.isEmpty()) return "ok(no cells)"
        ctl.stage = "index"
        val index = LibraryIndex(lib)
        val top = index.defaultTopCell() ?: return "ok(no top)"
        ctl.stage = "stats"
        LayerStats.shapeCounts(lib, top.index)
        val frame = index.frame(top.index)
        val cache = MeshCache(lib)
        val builder = SceneBuilder(index, cache)
        val w = 1080
        val h = 2400
        // As ViewerViewModel.fitToView does.
        val bw = max(frame.maxX - frame.minX, 1e-3)
        val bh = max(frame.maxY - frame.minY, 1e-3)
        val s = 0.9 * min(w / bw, h / bh)
        val cx = (frame.minX + frame.maxX) / 2
        val cy = (frame.minY + frame.maxY) / 2
        fun view(scale: Double, px: Double, py: Double) =
            Affine.translate(w / 2.0, h / 2.0) * Affine.scale(scale, -scale) * Affine.translate(-px, -py)
        val views = listOf(
            "fit" to view(s, cx, cy),
            "zoom300@center" to view(s * 300, cx, cy),
            "zoom1e5@min" to view(s * 1e5, frame.minX, frame.minY),
        )
        val sb = StringBuilder()
        for ((name, v) in views) {
            ctl.stage = "scene:$name"
            val list = builder.build(SceneRequest(frame, v, w, h), cancelled)
            ctl.stage = "mesh:$name"
            cache.ensure(list.groups.map { it.chunk }, Long.MAX_VALUE, cancelled)
            sb.append(" $name=${list.groups.size}ch/${list.stats.instances}inst/${list.stats.tinyItems}tiny")
        }
        ctl.stage = "snap"
        val visible = BooleanArray(lib.layers.size) { true }
        for (p in listOf(0.0 to 0.0, (frame.minX) to (frame.minY))) {
            Snapping.snap(index, frame, visible, p.first, p.second, 0.01 * max(bw, bh), cancelled)
        }
        return "ok cells=${lib.cells.size} layers=${lib.layers.size}$sb"
    }

    private class Result(val outcome: String, val millis: Long, val peakMb: Long)

    private fun runCase(bytes: ByteArray, timeoutMs: Long = 10_000): Result {
        System.gc()
        val ctl = Ctl()
        val rt = Runtime.getRuntime()
        val peakBox = java.util.concurrent.atomic.AtomicLong()
        val outBox = java.util.concurrent.atomic.AtomicReference("?")
        val t0 = System.nanoTime()
        val worker = Thread {
            outBox.set(try {
                pipeline(bytes, ctl)
            } catch (e: GdsFormatException) {
                "GdsFormatException(${e.message})"
            } catch (e: CancellationException) {
                "cancelled-at:${ctl.stage}"
            } catch (e: OutOfMemoryError) {
                "OOM@${ctl.stage}"
            } catch (e: StackOverflowError) {
                "StackOverflowError@${ctl.stage}"
            } catch (e: Throwable) {
                "${e.javaClass.simpleName}(${e.message?.take(80)})@${ctl.stage} " +
                    e.stackTrace.take(3).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }
            })
        }
        worker.isDaemon = true
        val sampler = Thread {
            while (!Thread.currentThread().isInterrupted) {
                peakBox.accumulateAndGet(rt.totalMemory() - rt.freeMemory()) { a, b -> max(a, b) }
                try { Thread.sleep(20) } catch (_: InterruptedException) { return@Thread }
            }
        }
        sampler.isDaemon = true
        sampler.start()
        worker.start()
        worker.join(timeoutMs)
        if (worker.isAlive) {
            ctl.cancel = true
            val stage = ctl.stage
            worker.join(3000)
            outBox.set(if (worker.isAlive) {
                "TIMEOUT(not cancellable, stuck in $stage; thread leaked)"
            } else {
                "TIMEOUT(>${timeoutMs}ms in $stage; cancellable) -> ${outBox.get()}"
            })
            if (worker.isAlive) {
                // Keep leaked busy threads from poisoning the rest of the run as much as we can.
                @Suppress("DEPRECATION") try { worker.interrupt() } catch (_: Throwable) {}
            }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        sampler.interrupt()
        return Result(outBox.get(), ms, peakBox.get() shr 20)
    }

    private fun report(name: String, bytes: ByteArray, timeoutMs: Long = 10_000) {
        val r = runCase(bytes, timeoutMs)
        println("PROBE %-34s size=%-10d %6d ms peak=%4d MB  %s".format(name, bytes.size, r.millis, r.peakMb, r.outcome))
    }

    // ---------------------------------------------------------------- cases

    private fun cases(): List<Triple<String, () -> ByteArray, Long>> {
        val c = ArrayList<Triple<String, () -> ByteArray, Long>>()
        fun add(name: String, timeout: Long = 10_000, gen: () -> ByteArray) { c += Triple(name, gen, timeout) }

        // ---- container / record level
        add("empty file") { ByteArray(0) }
        add("one byte") { byteArrayOf(0) }
        add("not gds (text)") { "hello world, this is not gds".toByteArray() }
        add("first record len 0") { ByteArray(64) }
        add("record len 1..3") { byteArrayOf(0, 2, 0, 0) }
        add("truncated mid-record") { val b = G().apply { cell("A") { box10() } }.end(); b.copyOf(b.size / 2) }
        add("truncated header only") { byteArrayOf(0, 6, 0, 2, 2, 0x58, 0, 3) }
        add("no ENDLIB") { G().apply { cell("A") { box10() } }.w.bytes() }
        add("odd record lengths") {
            val g = G(); g.rec(0x05, 2, ByteArray(3)); g.rec(0x06, 6, "AB".toByteArray() + 0x41); g.cell("A") { box10() }; g.end()
        }
        add("unknown record types") {
            val g = G(); for (t in 0x40..0xFF) g.rec(t, 0, ByteArray(t % 7)); g.cell("A") { box10() }; g.end()
        }
        add("XY 65531 bytes in BOUNDARY") {
            val g = G(); g.w.bgnstr("A")
            g.rec(0x08, 0); g.rec(0x0D, 2, i16(1)); g.rec(0x10, 3, ByteArray(65528) { (it * 7).toByte() }); g.rec(0x11, 0)
            g.w.endstr(); g.end()
        }
        add("XY odd length (len%4=2)") {
            val g = G(); g.w.bgnstr("A"); g.rec(0x08, 0); g.rec(0x10, 3, ByteArray(10)); g.rec(0x11, 0); g.w.endstr(); g.end()
        }
        add("XY before element / no ENDEL") {
            val g = G(); g.w.bgnstr("A"); g.rec(0x10, 3, i32(1, 2, 3, 4)); g.rec(0x08, 0); g.rec(0x10, 3, i32(1, 1, 5, 5, 9, 1, 1, 1)); g.w.endstr(); g.end()
        }
        add("units 0 / inf-ish / negative") {
            val g = TestGdsWriter(); g.header(); g.bgnlib(); g.rec(0x03, 5, ByteArray(16)); g.bgnstr("A"); g.box10(); g.endstr(); g.endlib(); g.bytes()
        }
        add("units 1e75 (dbu = 1e69 m)") {
            val g = TestGdsWriter(); g.header(); g.bgnlib(); g.rec(0x03, 5, HUGE_REAL + HUGE_REAL); g.bgnstr("A"); g.box10(); g.endstr(); g.endlib(); g.bytes()
        }
        add("cell name 64KB, 2 cells") {
            val g = G(); val n = "x".repeat(65000)
            g.w.bgnstr(n); g.w.box10(); g.w.endstr(); g.w.bgnstr(n + "y"); g.w.box10(); g.w.endstr(); g.end()
        }
        add("same cell defined twice") { G().apply { cell("A") { box10() }; cell("A") { box10(2) } }.end() }
        add("sref to undefined cell") { G().apply { cell("A") { box10(); sref("NOPE", 0, 0) } }.end() }
        add("only undefined refs, no shapes") { G().apply { cell("A") { sref("NOPE", 0, 0) } }.end() }
        add("empty cell only") { G().apply { cell("A") {} }.end() }

        // ---- polygons
        add("polygon 0/1/2 points") {
            G().apply { cell("A") { box10(); boundary(1, 0); boundary(1, 0, 5, 5); boundary(1, 0, 5, 5, 9, 9); boundary(1, 0, 5, 5, 5, 5, 5, 5) } }.end()
        }
        add("polygon all same point x4") { G().apply { cell("A") { boundary(1, 0, 5, 5, 5, 5, 5, 5, 5, 5) } }.end() }
        add("collinear polygon only") { G().apply { cell("A") { boundary(1, 0, 0, 0, 10, 0, 20, 0, 0, 0) } }.end() }
        add("extreme coords box") {
            G().apply { cell("A") { boundary(1, 0, Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE) } }.end()
        }
        add("extreme coords triangle+path") {
            G().apply { cell("A") {
                boundary(1, 0, Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, 0, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
                path(2, 0, 100, 0, 0, 0, Int.MIN_VALUE, 0, Int.MAX_VALUE, 0, Int.MAX_VALUE, 5)
            } }.end()
        }
        fun randomPolygon(n: Int, seed: Long, range: Int): IntArray {
            val r = Random(seed); return IntArray(2 * n) { r.nextInt(range) }
        }
        for (n in listOf(500, 2000, 4000, 8190)) {
            add("random self-intersecting poly n=$n", 15_000) {
                val xy = randomPolygon(n, 1, 1_000_000)
                G().apply { cell("A") { boundary(1, 0, *xy) } }.end()
            }
        }
        add("zigzag comb poly n=8190", 15_000) {
            val n = 8190; val xy = IntArray(2 * n)
            for (i in 0 until n) { xy[2 * i] = i * 10; xy[2 * i + 1] = if (i % 2 == 0) 0 else 1_000_000 }
            G().apply { cell("A") { boundary(1, 0, *xy) } }.end()
        }
        add("spiral poly n=8190", 15_000) {
            val n = 8190; val xy = IntArray(2 * n)
            for (i in 0 until n) { val a = i * 0.05; val rr = 100.0 + i * 20.0; xy[2 * i] = (rr * Math.cos(a)).toInt(); xy[2 * i + 1] = (rr * Math.sin(a)).toInt() }
            G().apply { cell("A") { boundary(1, 0, *xy) } }.end()
        }
        add("star-shaped (concave) poly n=8190", 15_000) {
            val n = 8190; val xy = IntArray(2 * n)
            for (i in 0 until n) { val a = 2 * Math.PI * i / n; val rr = if (i % 2 == 0) 1_000_000.0 else 500_000.0; xy[2 * i] = (rr * Math.cos(a)).toInt(); xy[2 * i + 1] = (rr * Math.sin(a)).toInt() }
            G().apply { cell("A") { boundary(1, 0, *xy) } }.end()
        }
        add("2000 random polys n=200 each", 15_000) {
            val g = G(); g.w.bgnstr("A"); for (k in 0 until 2000) g.w.boundary(1, 0, *randomPolygon(200, k.toLong(), 100_000)); g.w.endstr(); g.end()
        }

        // ---- paths
        fun walk(n: Int, seed: Long, step: Int): IntArray {
            val r = Random(seed); val xy = IntArray(2 * n); var x = 0; var y = 0
            for (i in 0 until n) { x += r.nextInt(2 * step) - step; y += r.nextInt(2 * step) - step; xy[2 * i] = x; xy[2 * i + 1] = y }
            return xy
        }
        for ((nm, wd, ty) in listOf(Triple("w=0", 0, 0), Triple("w=MIN_VALUE", Int.MIN_VALUE, 2), Triple("w=MAX_VALUE round", Int.MAX_VALUE, 1), Triple("w=-1000 ext", -1000, 2))) {
            add("path 8000pts $nm") { val xy = walk(8000, 3, 5000); G().apply { cell("A") { box10(); path(1, 0, wd, ty, 0, 0, *xy) } }.end() }
        }
        add("path type 4 ext MIN/MAX") {
            G().apply { cell("A") { box10(); path(1, 0, 100, 4, Int.MIN_VALUE, Int.MAX_VALUE, 0, 0, 1000, 0) } }.end()
        }
        add("path unknown type 77 / dup pts") {
            G().apply { cell("A") { box10(); path(1, 0, 100, 77, 0, 0, 0, 0, 0, 0, 0, 0); path(1, 0, 100, 0, 0, 0, 5, 5) } }.end()
        }
        add("path 1 point") { G().apply { cell("A") { box10(); path(1, 0, 100, 1, 0, 0, 5, 5) } }.end() }
        add("path self-overlapping sharp (reversals)") {
            val n = 8000; val xy = IntArray(2 * n); for (i in 0 until n) { xy[2 * i] = if (i % 2 == 0) 0 else 1_000_000; xy[2 * i + 1] = i / 2 }
            G().apply { cell("A") { box10(); path(1, 0, 20_000, 0, 0, 0, *xy) } }.end()
        }

        // ---- transforms / references
        add("sref mag=0") { G().apply { cell("B") { box10() }; cell("A") { box10(2, 100, 100); w.srefRaw("B", 0, 0, mag = ByteArray(8) { 0 }.also { it[1] = 1; it[0] = 0x40 }) } }.end() } // 1/2^56*1 tiny
        add("sref mag exact 0.0 (mantissa 0)") { G().apply { cell("B") { box10() }; cell("A") { box10(2, 100, 100); w.srefRaw("B", 0, 0, mag = ByteArray(8)) } }.end() }
        add("sref mag 1e75") { G().apply { cell("B") { box10() }; cell("A") { box10(2, 100, 100); w.srefRaw("B", 0, 0, mag = HUGE_REAL) } }.end() }
        add("sref mag -1e75") { G().apply { cell("B") { box10() }; cell("A") { box10(2, 100, 100); w.srefRaw("B", 0, 0, mag = NEG_HUGE_REAL) } }.end() }
        add("sref angle 1e75") { G().apply { cell("B") { box10() }; cell("A") { box10(2, 100, 100); w.srefRaw("B", 0, 0, angle = HUGE_REAL) } }.end() }
        add("sref angle 45.5 mag 0.37") { G().apply { cell("B") { box10() }; cell("A") { box10(2, 100, 100); w.srefRaw("B", 0, 0, mag = com.example.gdsviewer.gds.encodeReal8(0.37), angle = com.example.gdsviewer.gds.encodeReal8(45.5)) } }.end() }
        add("nested mag 1e75 x 10 levels") {
            val g = G(); g.cell("L10") { box10() }
            for (i in 9 downTo 0) g.cell("L$i") { box10(); srefRaw("L${i + 1}", 1, 1, mag = HUGE_REAL) }
            g.end()
        }
        add("nested mag 1e-75 x 10 levels") {
            val tiny = real(0x10000000000000L, 0x01)
            val g = G(); g.cell("L10") { box10() }
            for (i in 9 downTo 0) g.cell("L$i") { box10(); srefRaw("L${i + 1}", 1, 1, mag = tiny) }
            g.end()
        }
        add("self-reference x1") { G().apply { cell("A") { box10(); sref("A", 5, 5) } }.end() }
        add("self-reference x2 (2^64 visits?)") { G().apply { cell("A") { box10(); sref("A", 0, 0); sref("A", 1, 1) } }.end() }
        add("cycle A<->B each x2") { G().apply { cell("A") { box10(); sref("B", 0, 0); sref("B", 1, 1) }; cell("B") { box10(); sref("A", 0, 0); sref("A", 1, 1) } }.end() }
        add("DAG 40 levels x2 fan-out") {
            val g = G(); g.cell("L40") { box10() }
            for (i in 39 downTo 0) g.cell("L$i") { sref("L${i + 1}", 0, 0); sref("L${i + 1}", 3, 3) }
            g.end()
        }
        add("DAG 40 levels x2, mirrored offsets") {
            val g = G(); g.cell("L40") { box10() }
            for (i in 39 downTo 0) g.cell("L$i") { sref("L${i + 1}", 0, 0); sref("L${i + 1}", 0, 0, mirror = true) }
            g.end()
        }
        add("chain 100000 deep") {
            val g = G(); val n = 100_000
            for (i in 0 until n) g.cell("C$i") { sref("C${i + 1}", 0, 0) }
            g.cell("C$n") { box10() }
            g.end()
        }
        add("chain 100000 deep reversed order") {
            val g = G(); val n = 100_000
            g.cell("C$n") { box10() }
            for (i in n - 1 downTo 0) g.cell("C$i") { sref("C${i + 1}", 0, 0); box10(1, i, i, 1) }
            g.end()
        }
        add("ring of 50000 cells") {
            val g = G(); val n = 50_000
            for (i in 0 until n) g.cell("C$i") { box10(1, i, 0); sref("C${(i + 1) % n}", 1, 0) }
            g.end()
        }

        // ---- arrays
        val o = intArrayOf(0, 0, 0, 0, 0, 0)
        add("AREF 65535x65535 step 0") { G().apply { cell("B") { box10() }; cell("A") { box10(2, 50, 50); w.arefRaw("B", 65535, 65535, o) } }.end() }
        add("AREF 65535x65535 step 1") { G().apply { cell("B") { box10() }; cell("A") { w.arefRaw("B", 65535, 65535, intArrayOf(0, 0, 65535, 0, 0, 65535)) } }.end() }
        add("AREF 65535x65535 step 10 (dense)") { G().apply { cell("B") { box10() }; cell("A") { w.arefRaw("B", 65535, 65535, intArrayOf(0, 0, 655350, 0, 0, 655350)) } }.end() }
        add("AREF 1000x1000 step 10 (1M inst)") { G().apply { cell("B") { box10() }; cell("A") { w.arefRaw("B", 1000, 1000, intArrayOf(0, 0, 10000, 0, 0, 10000)) } }.end() }
        add("AREF 1000x1000 step 2 (overlap 1M)") { G().apply { cell("B") { box10() }; cell("A") { w.arefRaw("B", 1000, 1000, intArrayOf(0, 0, 2000, 0, 0, 2000)) } }.end() }
        add("AREF cols=0 rows=0") { G().apply { cell("B") { box10() }; cell("A") { box10(2, 50, 50); w.arefRaw("B", 0, 0, intArrayOf(0, 0, 10, 0, 0, 10)) } }.end() }
        add("AREF cols=1 rows=65535") { G().apply { cell("B") { box10() }; cell("A") { w.arefRaw("B", 1, 65535, intArrayOf(0, 0, 0, 0, 0, 65535 * 20)) } }.end() }
        add("AREF steps parallel") { G().apply { cell("B") { box10() }; cell("A") { w.arefRaw("B", 20000, 20000, intArrayOf(0, 0, 20000, 0, 40000, 0)) } }.end() }
        add("AREF extreme coords") {
            G().apply { cell("B") { box10() }; cell("A") { w.arefRaw("B", 65535, 65535, intArrayOf(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE)) } }.end()
        }
        add("AREF mag 1e75 65535^2") {
            G().apply { cell("B") { box10() }; cell("A") { box10(2, 50, 50); w.arefRaw("B", 65535, 65535, intArrayOf(0, 0, 100, 0, 0, 100), mag = HUGE_REAL, angle = com.example.gdsviewer.gds.encodeReal8(33.0)) } }.end()
        }
        add("AREF mag 0 angle 33") {
            G().apply { cell("B") { box10() }; cell("A") { box10(2, 50, 50); w.arefRaw("B", 100, 100, intArrayOf(0, 0, 100, 0, 0, 100), mag = ByteArray(8), angle = com.example.gdsviewer.gds.encodeReal8(33.0)) } }.end()
        }
        add("AREF xy only 2 points / 1 point") {
            G().apply { cell("B") { box10() }; cell("A") { box10(2, 50, 50); w.arefRaw("B", 3, 3, intArrayOf(0, 0, 10, 0)); w.arefRaw("B", 3, 3, intArrayOf(0, 0)) } }.end()
        }
        add("nested AREF 255^4 (arrays of arrays)") {
            val g = G(); g.cell("L3") { box10() }
            for (i in 2 downTo 0) g.cell("L$i") { arefRaw("L${i + 1}", 255, 255, intArrayOf(0, 0, 255 * 100, 0, 0, 255 * 100)) }
            g.end()
        }
        add("nested AREF 65535 x3, step 0") {
            val g = G(); g.cell("L3") { box10() }
            for (i in 2 downTo 0) g.cell("L$i") { arefRaw("L${i + 1}", 65535, 65535, o); box10(2, 1, 1, 1) }
            g.end()
        }
        add("nested AREF tiny elements 150x150 x3") {
            val g = G(); g.cell("L3") { box10() }
            for (i in 2 downTo 0) g.cell("L$i") { arefRaw("L${i + 1}", 150, 150, intArrayOf(0, 0, 150 * 11, 0, 0, 150 * 11)) }
            g.end()
        }
        add("snap: AREF 65535^2 step 0, empty query") {
            // B has two far-apart boxes; the middle of its bbox (where snapping looks) holds no shape.
            G().apply {
                cell("B") { box10(1, 0, 0, 10); box10(1, 1_000_000, 1_000_000, 10) }
                cell("A") { w.arefRaw("B", 65535, 65535, o) }
            }.end()
        }

        // ---- many cells / layers
        for (n in listOf(20_000, 60_000)) {
            add("cells=$n x layers=$n (box/layer)", 20_000) {
                val g = G(); val top = ArrayList<Int>()
                for (i in 0 until n) g.cell("C$i") { box10(i % 65536, 0, 0, 10) }
                g.cell("TOP") { for (i in 0 until n) sref("C$i", (i % 1000) * 100, (i / 1000) * 100) }
                g.end()
            }
        }
        add("30000 layers in 1 cell, tiny vs big", 20_000) {
            val g = G()
            g.cell("B") { for (i in 0 until 30000) boundary(i, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0) }
            g.cell("A") { boundary(1, 0, 0, 0, 100_000_000, 0, 100_000_000, 100_000_000, 0, 100_000_000, 0, 0); sref("B", 0, 0) }
            g.end()
        }
        add("65535 layers x 3 datatypes, 1 cell", 20_000) {
            val g = G(); g.w.bgnstr("A")
            for (l in 0 until 65535) for (d in 0 until 2) g.w.boundary(l, d, l, d, l + 5, d, l + 5, d + 5, l, d + 5, l, d)
            g.w.endstr(); g.end()
        }
        for (n in listOf(100_000, 300_000, 1_000_000)) {
            add("empty cells x$n", 60_000) {
                val g = G(); for (i in 0 until n) g.cell("C%07d".format(i)) {}; g.end()
            }
        }
        add("cells x300000 with 1 box each", 60_000) {
            val g = G(); for (i in 0 until 300_000) g.cell("C%07d".format(i)) { box10() }; g.end()
        }
        add("1M srefs to same cell in one cell", 60_000) {
            val g = G(); g.cell("B") { box10() }; g.cell("A") { for (i in 0 until 1_000_000) sref("B", i % 1000 * 50, i / 1000 * 50) }; g.end()
        }

        // ---- gzip
        add("gz: 1 MB of valid file", 20_000) { gz(G().apply { cell("A") { box10() } }.end()) }
        add("gz: damaged (truncated)") { val b = gz(G().apply { cell("A") { box10() } }.end()); b.copyOf(b.size - 10) }
        add("gz: garbage after magic") { byteArrayOf(0x1F, 0x8B.toByte(), 8, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3) }
        add("gz: magic only") { byteArrayOf(0x1F, 0x8B.toByte()) }
        add("gz BOMB A: 1 GiB of ignorable records", 120_000) {
            val prefix = G().w.bytes()
            val blk = ByteArrayOutputStream(); repeat(16) { blk.write(recBytes(0x99, 0, ByteArray(65531))) }
            gzRepeat(prefix, blk.toByteArray(), 1024, ByteArray(0)) // 1024 * 1 MiB = 1 GiB
        }
        add("gz BOMB B: 200 MiB of identical triangles", 120_000) {
            val prefix = G().w.apply { bgnstr("A") }.bytes()
            val tri = TestGdsWriter().apply { boundary(1, 0, 0, 0, 10, 0, 5, 9, 0, 0) }.bytes()
            val blk = ByteArrayOutputStream(); repeat(1 shl 14) { blk.write(tri) }
            val per = blk.size()
            gzRepeat(prefix, blk.toByteArray(), (200 shl 20) / per, ByteArray(0))
        }
        add("gz BOMB C: 200 MiB of identical SREFs in one cell", 120_000) {
            val prefix = G().apply { w.bgnstr("B"); w.box10(); w.endstr(); w.bgnstr("A") }.w.bytes()
            val s = TestGdsWriter().apply { sref("B", 3, 4) }.bytes()
            val blk = ByteArrayOutputStream(); repeat(1 shl 14) { blk.write(s) }
            gzRepeat(prefix, blk.toByteArray(), (200 shl 20) / blk.size(), ByteArray(0))
        }
        return c
    }

    // ---------------------------------------------------------------- fuzzing

    private fun recordOffsets(b: ByteArray): IntArray {
        val l = ArrayList<Int>(); var p = 0
        while (p + 4 <= b.size) { l += p; val n = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF); if (n < 4) break; p += n }
        return l.toIntArray()
    }

    private fun mutate(src: ByteArray, r: Random): ByteArray {
        var b = src.copyOf()
        val k = 1 + r.nextInt(4)
        repeat(k) {
            val offs = recordOffsets(b)
            when (r.nextInt(7)) {
                0 -> repeat(1 + r.nextInt(8)) { b[r.nextInt(b.size)] = r.nextInt(256).toByte() }
                1 -> b[r.nextInt(b.size)] = (b[r.nextInt(b.size)].toInt() xor (1 shl r.nextInt(8))).toByte()
                2 -> if (offs.isNotEmpty()) { // random record length
                    val p = offs[r.nextInt(offs.size)]; if (p + 1 >= b.size) return@repeat; val n = when (r.nextInt(4)) { 0 -> 0; 1 -> 4; 2 -> r.nextInt(65536); else -> 65535 }
                    b[p] = (n shr 8).toByte(); b[p + 1] = n.toByte()
                }
                3 -> b = b.copyOf(1 + r.nextInt(b.size))
                4 -> if (offs.isNotEmpty()) { // 0xFF / 0x7F / 0x80 / 0 in a data byte of a random record
                    val p = offs[r.nextInt(offs.size)]; val q = p + 4 + r.nextInt(8)
                    if (q < b.size && p + 4 <= b.size) b[q] = byteArrayOf(0xFF.toByte(), 0x7F, 0x80.toByte(), 0, 1)[r.nextInt(5)]
                }
                5 -> if (offs.isNotEmpty()) { // duplicate a record somewhere else
                    val i = r.nextInt(offs.size); val p = offs[i]; val e = if (i + 1 < offs.size) offs[i + 1] else b.size
                    val at = offs[r.nextInt(offs.size)]
                    b = b.copyOfRange(0, at) + b.copyOfRange(p, e) + b.copyOfRange(at, b.size)
                }
                else -> if (offs.isNotEmpty()) { // delete a record
                    val i = r.nextInt(offs.size); val p = offs[i]; val e = if (i + 1 < offs.size) offs[i + 1] else b.size
                    b = b.copyOfRange(0, p) + b.copyOfRange(e, b.size)
                }
            }
            if (b.isEmpty()) b = byteArrayOf(0)
        }
        return b
    }

    private fun hierarchicalSample(): ByteArray {
        val g = G()
        g.cell("LEAF") { box10(1, 0, 0, 100); boundary(2, 0, 0, 0, 80, 0, 80, 40, 40, 40, 40, 80, 0, 80, 0, 0); path(3, 0, 20, 2, 0, 0, 0, 0, 100, 0, 100, 100) }
        g.cell("MID") { box10(4, 0, 0, 300); aref("LEAF", 4, 3, 0, 0, 4 * 150, 0, 0, 3 * 150); sref("LEAF", 700, 0, mirror = true, mag = 0.5, angle = 90.0) }
        g.cell("TOP") { aref("MID", 2, 2, 0, 0, 2 * 2000, 0, 0, 2 * 2000); sref("LEAF", -500, -500, angle = 30.0); sref("MID", 5000, 5000, mag = 2.0) }
        return g.end()
    }

    private fun fuzz(label: String, seedBytes: ByteArray, iterations: Int, baseSeed: Long, budgetMs: Long) {
        val failures = ArrayList<String>()
        val start = System.nanoTime()
        var done = 0
        var slow = 0
        val ctl = Ctl()
        val iterA = java.util.concurrent.atomic.AtomicInteger(-1)
        val deadlineA = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)
        val worker = Thread {
            for (i in 0 until iterations) {
                iterA.set(i)
                if ((System.nanoTime() - start) / 1_000_000 > budgetMs) break
                val bytes = mutate(seedBytes, Random(baseSeed + i))
                ctl.cancel = false
                deadlineA.set(System.nanoTime() + 5_000_000_000L)
                val outcome = try {
                    pipeline(bytes, ctl); "ok"
                } catch (e: GdsFormatException) { "fmt" }
                catch (e: CancellationException) { "cancelled@${ctl.stage}" }
                catch (e: Throwable) { "${e.javaClass.simpleName}(${e.message?.take(60)}) " + e.stackTrace.take(3).joinToString("<") { "${it.fileName}:${it.lineNumber}" } }
                deadlineA.set(Long.MAX_VALUE)
                if (outcome != "ok" && outcome != "fmt") failures += "iter=$i seed=${baseSeed + i}: $outcome"
                done++
            }
        }
        worker.isDaemon = true
        // Watchdog: cancels a single iteration after 5 s; gives up on a stuck (non-cancellable) one after 15 s.
        worker.start()
        var lastSlow = -2
        while (worker.isAlive) {
            Thread.sleep(50)
            val dl = deadlineA.get()
            val iter = iterA.get()
            if (dl != Long.MAX_VALUE && System.nanoTime() > dl) { ctl.cancel = true; if (iter != lastSlow) { slow++; lastSlow = iter } }
            if (dl != Long.MAX_VALUE && System.nanoTime() > dl + 10_000_000_000L) { failures += "iter=$iter seed=${baseSeed + iter}: HANG (not cancellable, stage ${ctl.stage})"; break }
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        println("FUZZ $label: $done iterations in $ms ms, ${failures.size} unexpected outcomes ($slow iterations needed cancelling)")
        for (f in failures.take(40)) println("FUZZ   $f")
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun crafted() {
        val only = System.getenv("PROBE_ONLY")
        for ((name, gen, timeout) in cases()) {
            if (only != null && !name.contains(only)) continue
            val bytes = try { gen() } catch (e: Throwable) { println("PROBE $name: generator failed ${e.javaClass.simpleName}"); continue }
            report(name, bytes, timeout)
        }
    }

    @Test
    fun fuzzSampleGds() {
        val f = listOf("app/src/main/assets/sample.gds", "../app/src/main/assets/sample.gds").map { File(it) }.firstOrNull { it.exists() }
            ?: return println("FUZZ sample.gds not found")
        fuzz("sample.gds", f.readBytes(), 3000, 1_000_000L, 100_000)
    }

    @Test
    fun fuzzHierarchical() {
        fuzz("generated hierarchical", hierarchicalSample(), 3000, 2_000_000L, 100_000)
    }

    /** Times the reader on a gzip bomb of ignorable records, and the raw inflate, to show the reader has no output limit. */
    @Test
    fun gzipBombTiming() {
        val prefix = G().w.bytes()
        val blk = ByteArrayOutputStream(); repeat(16) { blk.write(recBytes(0x99, 0, ByteArray(65531))) }
        val bomb = gzRepeat(prefix, blk.toByteArray(), 1024, G().w.apply { endlib() }.bytes().copyOfRange(prefix.size, prefix.size + 4))
        var raw = 0L
        val t0 = System.nanoTime()
        java.util.zip.GZIPInputStream(ByteArrayInputStream(bomb)).use { val b = ByteArray(1 shl 16); while (true) { val n = it.read(b); if (n < 0) break; raw += n } }
        val t1 = System.nanoTime()
        var progress = 0L
        val lib = GdsReader.read(ByteArrayInputStream(bomb), { false }) { progress = it }
        val t2 = System.nanoTime()
        println("BOMBTIME gz=${bomb.size} bytes inflates to $raw bytes (ratio ${raw / bomb.size}:1); raw inflate ${(t1 - t0) / 1_000_000} ms; GdsReader.read ${(t2 - t1) / 1_000_000} ms, cells=${lib.cells.size}, lastProgress=$progress")
    }
}
