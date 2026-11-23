package app.mapscoordinator

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Hill shading and contour lines from a grid of elevations (metres, NaN = no data). Pure maths. */
object Terrain {
    /** ARGB overlay: light slopes facing north-west, dark slopes facing away. cell = grid spacing in metres. */
    fun shade(z: FloatArray, gw: Int, gh: Int, cell: Double, out: IntArray) {
        val az = Math.toRadians(315.0)
        val alt = Math.toRadians(42.0)
        val lx = (sin(az) * cos(alt)).toFloat()
        val ly = (-cos(az) * cos(alt)).toFloat()   // screen y grows downward
        val lz = sin(alt).toFloat()
        val exag = 2.0f
        val flat = lz
        for (j in 0 until gh) for (i in 0 until gw) {
            val i0 = if (i > 0) i - 1 else i; val i1 = if (i < gw - 1) i + 1 else i
            val j0 = if (j > 0) j - 1 else j; val j1 = if (j < gh - 1) j + 1 else j
            val a = z[j * gw + i0]; val b = z[j * gw + i1]; val c = z[j0 * gw + i]; val d = z[j1 * gw + i]
            if (a.isNaN() || b.isNaN() || c.isNaN() || d.isNaN()) { out[j * gw + i] = 0; continue }
            val dzdx = (b - a) / ((i1 - i0) * cell.toFloat()) * exag
            val dzdy = (d - c) / ((j1 - j0) * cell.toFloat()) * exag   // y down
            val nx = -dzdx; val ny = -dzdy; val nz = 1f
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            val s = (nx * lx + ny * ly + nz * lz) / len
            val dlt = s - flat
            out[j * gw + i] = if (dlt >= 0f) {
                val al = (dlt * 220f).toInt().coerceIn(0, 70)
                (al shl 24) or 0xC8D2EC
            } else {
                val al = (-dlt * 300f).toInt().coerceIn(0, 120)
                (al shl 24) or 0x02040A
            }
        }
    }

    /** Marching squares. Calls emit(x1, y1, x2, y2, level) in grid units. Values are compared as given (use feet or metres). */
    fun contours(z: FloatArray, gw: Int, gh: Int, interval: Float, emit: (Float, Float, Float, Float, Float) -> Unit) {
        for (j in 0 until gh - 1) for (i in 0 until gw - 1) {
            val za = z[j * gw + i]; val zb = z[j * gw + i + 1]
            val zc = z[(j + 1) * gw + i + 1]; val zd = z[(j + 1) * gw + i]
            if (za.isNaN() || zb.isNaN() || zc.isNaN() || zd.isNaN()) continue
            val lo = minOf(minOf(za, zb), minOf(zc, zd))
            val hi = maxOf(maxOf(za, zb), maxOf(zc, zd))
            var k = Math.floor((lo / interval).toDouble()).toInt() + 1
            while (k * interval <= hi) {
                val lv = k * interval
                var idx = 0
                if (za >= lv) idx = idx or 1
                if (zb >= lv) idx = idx or 2
                if (zc >= lv) idx = idx or 4
                if (zd >= lv) idx = idx or 8
                fun tx() = i + (lv - za) / (zb - za)
                fun ry() = j + (lv - zb) / (zc - zb)
                fun bx() = i + (lv - zd) / (zc - zd)
                fun ly() = j + (lv - za) / (zd - za)
                val x0 = i.toFloat(); val x1 = (i + 1).toFloat(); val y0 = j.toFloat(); val y1 = (j + 1).toFloat()
                when (idx) {
                    1, 14 -> emit(x0, ly(), tx(), y0, lv)
                    2, 13 -> emit(tx(), y0, x1, ry(), lv)
                    3, 12 -> emit(x0, ly(), x1, ry(), lv)
                    4, 11 -> emit(x1, ry(), bx(), y1, lv)
                    5 -> { emit(x0, ly(), tx(), y0, lv); emit(x1, ry(), bx(), y1, lv) }
                    6, 9 -> emit(tx(), y0, bx(), y1, lv)
                    7, 8 -> emit(x0, ly(), bx(), y1, lv)
                    10 -> { emit(tx(), y0, x1, ry(), lv); emit(x0, ly(), bx(), y1, lv) }
                }
                k++
            }
        }
    }

    /** Doubles the spacing until the line count is drawable (steep or noisy terrain). */
    fun fitInterval(z: FloatArray, gw: Int, gh: Int, start: Float, maxSegs: Int = 100000): Float {
        var iv = start
        for (k in 0 until 4) {
            var n = 0
            contours(z, gw, gh, iv) { _, _, _, _, _ -> n++ }
            if (n <= maxSegs) return iv
            iv *= 2
        }
        return iv
    }

    /** Contour spacing in feet for a zoom level. */
    fun intervalFeet(mpp: Double): Float = when {
        mpp < 1.5 -> 10f
        mpp < 4 -> 20f
        mpp < 12 -> 50f
        mpp < 35 -> 100f
        mpp < 80 -> 200f
        else -> 500f
    }
}
