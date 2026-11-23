package app.mapscoordinator

import kotlin.math.cos
import kotlin.math.sqrt

/** Waypoint routes: straight legs between points, with distance and an elevation profile. */
object Route {
    class Wp(val name: String, val lat: Double, val lon: Double)
    class Profile(val distM: Double, val upM: Double, val downM: Double, val dist: FloatArray, val elev: FloatArray, val minM: Float, val maxM: Float)

    fun distance(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dy = (bLat - aLat) * 110574.0
        val dx = (bLon - aLon) * 111320.0 * cos(Math.toRadians((aLat + bLat) / 2))
        return sqrt(dx * dx + dy * dy)
    }

    fun legs(w: List<Wp>): DoubleArray {
        val r = DoubleArray(maxOf(0, w.size - 1))
        for (i in r.indices) r[i] = distance(w[i].lat, w[i].lon, w[i + 1].lat, w[i + 1].lon)
        return r
    }

    /** Samples elevation about every stepM metres along the legs. Gaps (NaN) are skipped in climb totals. */
    fun profile(w: List<Wp>, elevAt: (Double, Double) -> Float, stepM: Double = 50.0): Profile? {
        if (w.size < 2) return null
        val ds = ArrayList<Float>(); val es = ArrayList<Float>()
        var run = 0.0; var up = 0.0; var down = 0.0
        var last = Float.NaN
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (i in 0 until w.size - 1) {
            val len = distance(w[i].lat, w[i].lon, w[i + 1].lat, w[i + 1].lon)
            val n = maxOf(1, Math.ceil(len / stepM).toInt())
            for (k in 0..n) {
                if (i > 0 && k == 0) continue
                val f = k.toDouble() / n
                val e = elevAt(w[i].lat + (w[i + 1].lat - w[i].lat) * f, w[i].lon + (w[i + 1].lon - w[i].lon) * f)
                val d = run + len * f
                ds.add(d.toFloat()); es.add(e)
                if (!e.isNaN()) {
                    if (e < mn) mn = e
                    if (e > mx) mx = e
                    if (!last.isNaN()) { val dz = e - last; if (dz > 0) up += dz else down -= dz }
                    last = e
                }
            }
            run += len
        }
        if (mn == Float.MAX_VALUE) { mn = 0f; mx = 0f }
        return Profile(run, up, down, ds.toFloatArray(), es.toFloatArray(), mn, mx)
    }

    fun encode(w: List<Wp>): String = w.joinToString("\n") { it.name.replace('\t', ' ').replace('\n', ' ') + "\t" + it.lat + "\t" + it.lon }

    fun decode(s: String): List<Wp> {
        val out = ArrayList<Wp>()
        for (line in s.split('\n')) {
            val p = line.split('\t')
            if (p.size < 3) continue
            val la = p[1].toDoubleOrNull() ?: continue
            val lo = p[2].toDoubleOrNull() ?: continue
            out.add(Wp(p[0], la, lo))
        }
        return out
    }
}
