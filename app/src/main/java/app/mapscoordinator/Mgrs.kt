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
}
