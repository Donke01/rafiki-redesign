package org.rafikioutreach.pin.cmap

/**
 * Decodes Mapbox vector tiles (github.com/mapbox/vector-tile-spec): layers of points, lines and areas with their
 * attributes. Geometry stays in tile units (0..extent); each part is x0, y0, x1, y1, ...
 */
class MvtFeature(val type: Int, val props: Map<String, Any?>, val parts: List<IntArray>) {
    fun str(k: String): String? = props[k]?.toString()
    fun num(k: String): Double = (props[k] as? Number)?.toDouble() ?: props[k]?.toString()?.toDoubleOrNull() ?: 0.0
    companion object { const val POINT = 1; const val LINE = 2; const val AREA = 3 }
}
class MvtLayer(val name: String, val extent: Int, val features: List<MvtFeature>)

object Mvt {
    private class Pbf(val b: ByteArray, var i: Int, val end: Int) {
        fun more() = i < end
        fun v(): Long { var r = 0L; var s = 0; while (true) { val x = b[i++].toInt() and 0xFF; r = r or ((x and 0x7F).toLong() shl s); if (x and 0x80 == 0) return r; s += 7 } }
        fun sub(): Pbf { val n = v().toInt(); val p = Pbf(b, i, i + n); i += n; return p }
        fun str(): String { val n = v().toInt(); val s = String(b, i, n, Charsets.UTF_8); i += n; return s }
        fun skip(w: Int) { when (w) { 0 -> v(); 1 -> i += 8; 2 -> i += v().toInt(); 5 -> i += 4; else -> error("bad wire type") } }
        fun packed(): IntArray { val n = v().toInt(); val e = i + n; val out = ArrayList<Int>(n); while (i < e) out.add(v().toInt()); return out.toIntArray() }
        fun f32(): Float { val bits = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or (b[i + 3].toInt() shl 24); i += 4; return Float.fromBits(bits) }
        fun f64(): Double { var r = 0L; for (k in 7 downTo 0) r = (r shl 8) or (b[i + k].toLong() and 0xFF); i += 8; return Double.fromBits(r) }
    }

    fun decode(bytes: ByteArray): Map<String, MvtLayer> {
        val r = Pbf(bytes, 0, bytes.size); val out = HashMap<String, MvtLayer>()
        while (r.more()) {
            val t = r.v().toInt()
            if (t shr 3 != 3) { r.skip(t and 7); continue }
            val l = r.sub(); var name = ""; var ext = 4096
            val keys = ArrayList<String>(); val vals = ArrayList<Any?>(); val feats = ArrayList<Pbf>()
            while (l.more()) {
                val u = l.v().toInt()
                when (u shr 3) { 1 -> name = l.str(); 2 -> feats.add(l.sub()); 3 -> keys.add(l.str()); 4 -> vals.add(value(l.sub())); 5 -> ext = l.v().toInt(); else -> l.skip(u and 7) }
            }
            out[name] = MvtLayer(name, ext, feats.map { f ->
                var type = 0; val props = HashMap<String, Any?>(); var geom: List<IntArray> = emptyList()
                while (f.more()) {
                    val w = f.v().toInt()
                    when (w shr 3) {
                        2 -> { val tg = f.packed(); var j = 0; while (j + 1 < tg.size) { props[keys[tg[j]]] = vals[tg[j + 1]]; j += 2 } }
                        3 -> type = f.v().toInt()
                        4 -> geom = geometry(f.packed())
                        else -> f.skip(w and 7)
                    }
                }
                MvtFeature(type, props, geom)
            })
        }
        return out
    }

    private fun value(r: Pbf): Any? {
        var v: Any? = null
        while (r.more()) {
            val t = r.v().toInt()
            v = when (t shr 3) {
                1 -> r.str(); 2 -> r.f32().toDouble(); 3 -> r.f64(); 4, 5 -> r.v()
                6 -> { val z = r.v(); (z ushr 1) xor -(z and 1) }
                7 -> r.v() != 0L
                else -> { r.skip(t and 7); v }
            }
        }
        return v
    }

    /** MoveTo / LineTo / ClosePath commands into parts of absolute coordinates. */
    fun geometry(cmd: IntArray): List<IntArray> {
        val parts = ArrayList<IntArray>(); var cur: ArrayList<Int>? = null; var x = 0; var y = 0; var i = 0
        fun flush() { cur?.let { parts.add(it.toIntArray()) }; cur = null }
        while (i < cmd.size) {
            val c = cmd[i++]; val id = c and 7; val n = c ushr 3
            if (id == 7) continue
            repeat(n) {
                val dx = cmd[i++]; val dy = cmd[i++]
                x += (dx ushr 1) xor -(dx and 1); y += (dy ushr 1) xor -(dy and 1)
                if (id == 1) { flush(); cur = arrayListOf(x, y) } else cur?.apply { add(x); add(y) }
            }
        }
        flush(); return parts
    }
}
