package app.mapscoordinator

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** WGS 84 latitude/longitude to MGRS, worked out on the phone. No network. */
object Mgrs {
    private const val A = 6378137.0
    private const val F = 1.0 / 298.257223563
    private const val K0 = 0.9996
    private const val BANDS = "CDEFGHJKLMNPQRSTUVWX"
    private val COLS = arrayOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
    private const val ROWS = "ABCDEFGHJKLMNPQRSTUV"

    /** Returns e.g. "12S VC 00179 01514", or "outside MGRS range" near the poles. */
    fun format(lat: Double, lon: Double): String {
        if (lat < -80.0 || lat > 84.0) return "outside MGRS range"
        val e2 = F * (2 - F)
        val ep2 = e2 / (1 - e2)
        var zone = floor((lon + 180.0) / 6.0).toInt() + 1
        if (zone < 1) zone = 1
        if (zone > 60) zone = 60
        if (lat >= 56.0 && lat < 64.0 && lon >= 3.0 && lon < 12.0) zone = 32
        val lon0 = Math.toRadians(((zone - 1) * 6 - 180 + 3).toDouble())
        val p = Math.toRadians(lat)
        val l = Math.toRadians(lon)
        val sp = sin(p)
        val cp = cos(p)
        val tp = tan(p)
        val n = A / sqrt(1 - e2 * sp * sp)
        val t = tp * tp
        val c = ep2 * cp * cp
        val a = (l - lon0) * cp
        val m = A * ((1 - e2 / 4 - 3 * e2.pow(2) / 64 - 5 * e2.pow(3) / 256) * p
                - (3 * e2 / 8 + 3 * e2.pow(2) / 32 + 45 * e2.pow(3) / 1024) * sin(2 * p)
                + (15 * e2.pow(2) / 256 + 45 * e2.pow(3) / 1024) * sin(4 * p)
                - (35 * e2.pow(3) / 3072) * sin(6 * p))
        val x = K0 * n * (a + (1 - t + c) * a.pow(3) / 6 +
                (5 - 18 * t + t * t + 72 * c - 58 * ep2) * a.pow(5) / 120) + 500000.0
        var y = K0 * (m + n * tp * (a * a / 2 + (5 - t + 9 * c + 4 * c * c) * a.pow(4) / 24 +
                (61 - 58 * t + t * t + 600 * c - 330 * ep2) * a.pow(6) / 720))
        if (lat < 0) y += 10000000.0
        var bandIndex = floor((lat + 80.0) / 8.0).toInt()
        if (bandIndex < 0) bandIndex = 0
        if (bandIndex > 19) bandIndex = 19
        val band = BANDS[bandIndex]
        val colSet = COLS[(zone - 1) % 3]
        var ci = floor(x / 100000.0).toInt() - 1
        if (ci < 0) ci = 0
        if (ci > 7) ci = 7
        val ri = (floor(y / 100000.0).toInt() + (if (zone % 2 == 0) 5 else 0)) % 20
        val e = (floor(x).toLong() % 100000L).toInt()
        val nn = (floor(y).toLong() % 100000L).toInt()
        return String.format("%d%c %c%c %05d %05d", zone, band, colSet[ci], ROWS[abs(ri)], e, nn)
    }

    /** Reads an MGRS grid like "12S VC 00179 01514" and returns (lat, lon), or null if it is not one. */
    fun parse(text: String): Pair<Double, Double>? {
        val t = text.uppercase().replace(" ", "").replace("-", "")
        val mt = Regex("^(\\d{1,2})([C-HJ-NP-X])([A-HJ-NP-Z])([A-HJ-NP-V])(\\d{2,10})$").find(t) ?: return null
        val zone = mt.groupValues[1].toInt()
        if (zone < 1 || zone > 60) return null
        val band = mt.groupValues[2][0]
        val colL = mt.groupValues[3][0]
        val rowL = mt.groupValues[4][0]
        val digits = mt.groupValues[5]
        if (digits.length % 2 != 0) return null
        val half = digits.length / 2
        val scale = 10.0.pow(5 - half)
        val e = digits.substring(0, half).toDouble() * scale + scale / 2
        val n = digits.substring(half).toDouble() * scale + scale / 2
        val colIdx = COLS[(zone - 1) % 3].indexOf(colL)
        if (colIdx < 0) return null
        val easting = (colIdx + 1) * 100000.0 + e
        val rowIdx = ROWS.indexOf(rowL)
        if (rowIdx < 0) return null
        val bandIdx = BANDS.indexOf(band)
        val north = band >= 'N'
        val base = ((rowIdx - (if (zone % 2 == 0) 5 else 0)) % 20 + 20) % 20 * 100000.0
        for (k in 0..4) {
            val nn = k * 2000000.0 + base + n
            val ll = utmToLatLon(zone, north, easting, nn) ?: continue
            val minLat = -80.0 + 8.0 * bandIdx
            val maxLat = if (bandIdx == 19) 84.0 else minLat + 8.0
            if (ll.first >= minLat - 0.0001 && ll.first <= maxLat + 0.0001) return ll
        }
        return null
    }

    private fun utmToLatLon(zone: Int, north: Boolean, easting: Double, northing: Double): Pair<Double, Double>? {
        val e2 = F * (2 - F)
        val ep2 = e2 / (1 - e2)
        val x = easting - 500000.0
        val y = if (north) northing else northing - 10000000.0
        val m = y / K0
        val mu = m / (A * (1 - e2 / 4 - 3 * e2.pow(2) / 64 - 5 * e2.pow(3) / 256))
        val e1 = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))
        val phi1 = mu + (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
                (21 * e1.pow(2) / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
                (151 * e1.pow(3) / 96) * sin(6 * mu) + (1097 * e1.pow(4) / 512) * sin(8 * mu)
        val sp = sin(phi1)
        val cp = cos(phi1)
        val tp = tan(phi1)
        val n1 = A / sqrt(1 - e2 * sp * sp)
        val t1 = tp * tp
        val c1 = ep2 * cp * cp
        val r1 = A * (1 - e2) / (1 - e2 * sp * sp).pow(1.5)
        val d = x / (n1 * K0)
        val lat = phi1 - (n1 * tp / r1) * (d * d / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * d.pow(4) / 24 +
                (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * d.pow(6) / 720)
        val lon0 = Math.toRadians(((zone - 1) * 6 - 180 + 3).toDouble())
        val lon = lon0 + (d - (1 + 2 * t1 + c1) * d.pow(3) / 6 +
                (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * d.pow(5) / 120) / cp
        val la = Math.toDegrees(lat)
        val lo = Math.toDegrees(lon)
        if (la.isNaN() || lo.isNaN()) return null
        return Pair(la, lo)
    }
}
