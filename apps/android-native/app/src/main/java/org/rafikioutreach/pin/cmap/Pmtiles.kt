package org.rafikioutreach.pin.cmap

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

/**
 * Reads map tiles from a PMTiles v3 file (one file holds a whole country's vector tiles; the spec:
 * github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md). Only what RAFIKI's country files use: gzip or no
 * compression, directories in the file, and tiles found by their number along the Hilbert curve.
 */
class Pmtiles(val file: File) : Closeable {
    class Header(
        val rootOff: Long, val rootLen: Long, val leafOff: Long, val dataOff: Long,
        val inner: Int, val comp: Int, val type: Int, val minZoom: Int, val maxZoom: Int,
        val west: Double, val south: Double, val east: Double, val north: Double,
    )
    class Dir(val ids: LongArray, val runs: LongArray, val lens: LongArray, val offs: LongArray)

    private val raf = RandomAccessFile(file, "r")
    val header: Header
    private val root: Dir
    private val leaves = object : LinkedHashMap<Long, Dir>(16, .75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, Dir>?) = size > 48 }

    init {
        val h = read(0, 127)
        require(String(h, 0, 7, Charsets.ISO_8859_1) == "PMTiles" && h[7].toInt() == 3) { "not a PMTiles v3 file" }
        fun u64(o: Int): Long { var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (h[o + i].toLong() and 0xFF); return v }
        fun i32(o: Int): Int = (h[o].toInt() and 0xFF) or ((h[o + 1].toInt() and 0xFF) shl 8) or ((h[o + 2].toInt() and 0xFF) shl 16) or (h[o + 3].toInt() shl 24)
        header = Header(u64(8), u64(16), u64(40), u64(56), h[97].toInt(), h[98].toInt(), h[99].toInt(), h[100].toInt() and 0xFF, h[101].toInt() and 0xFF,
            i32(102) / 1e7, i32(106) / 1e7, i32(110) / 1e7, i32(114) / 1e7)
        root = parseDir(unzip(read(header.rootOff, header.rootLen.toInt()), header.inner))
    }

    @Synchronized fun read(off: Long, len: Int): ByteArray {
        val b = ByteArray(len); raf.seek(off); raf.readFully(b); return b
    }

    /** The tile's bytes (a Mapbox vector tile, unzipped), or null when the file has nothing there. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < header.minZoom || z > header.maxZoom) return null
        val id = tileId(z, x, y)
        var dir = root
        repeat(4) {
            val i = find(dir, id); if (i < 0) return null
            if (dir.runs[i] == 0L) {
                val key = dir.offs[i]
                dir = synchronized(leaves) { leaves[key] } ?: parseDir(unzip(read(header.leafOff + dir.offs[i], dir.lens[i].toInt()), header.inner)).also { d -> synchronized(leaves) { leaves[key] = d } }
            } else {
                if (id - dir.ids[i] >= dir.runs[i]) return null
                return unzip(read(header.dataOff + dir.offs[i], dir.lens[i].toInt()), header.comp)
            }
        }
        return null
    }

    override fun close() { runCatching { raf.close() } }

    companion object {
        /** The tile's number along the Hilbert curve, counting every tile of the zooms above it first. */
        fun tileId(z: Int, x0: Int, y0: Int): Long {
            var acc = 0L
            for (k in 0 until z) acc += 1L shl (2 * k)
            var x = x0.toLong(); var y = y0.toLong(); var d = 0L
            var s = (1L shl z) / 2
            while (s > 0) {
                val rx = if (x and s > 0) 1L else 0L; val ry = if (y and s > 0) 1L else 0L
                d += s * s * ((3 * rx) xor ry)
                if (ry == 0L) { if (rx == 1L) { x = s - 1 - x; y = s - 1 - y }; val t = x; x = y; y = t }
                s /= 2
            }
            return acc + d
        }
        private fun varint(b: ByteArray, p: IntArray): Long {
            var v = 0L; var shift = 0
            while (true) { val x = b[p[0]++].toInt() and 0xFF; v = v or ((x and 0x7F).toLong() shl shift); if (x and 0x80 == 0) return v; shift += 7 }
        }
        fun parseDir(b: ByteArray): Dir {
            val p = intArrayOf(0); val n = varint(b, p).toInt()
            val ids = LongArray(n); val runs = LongArray(n); val lens = LongArray(n); val offs = LongArray(n)
            var last = 0L
            for (i in 0 until n) { last += varint(b, p); ids[i] = last }
            for (i in 0 until n) runs[i] = varint(b, p)
            for (i in 0 until n) lens[i] = varint(b, p)
            for (i in 0 until n) { val v = varint(b, p); offs[i] = if (v == 0L && i > 0) offs[i - 1] + lens[i - 1] else v - 1 }
            return Dir(ids, runs, lens, offs)
        }
        /** The last entry at or before [id], or -1. */
        fun find(d: Dir, id: Long): Int {
            var lo = 0; var hi = d.ids.size - 1; var best = -1
            while (lo <= hi) { val m = (lo + hi) ushr 1; if (d.ids[m] <= id) { best = m; lo = m + 1 } else hi = m - 1 }
            return best
        }
        fun unzip(b: ByteArray, how: Int): ByteArray = if (how == 2) GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() } else b
    }
}
