package app.mapscoordinator

import android.content.Context
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream

/** Offline search: coordinates, MGRS grids, and words from the index built at import. */
object Search {
    class Hit(val kind: Int, val lat: Double, val lon: Double, val text: String, val km: Double)

    fun hasIndex(ctx: Context): Boolean = File(MapStore.dir(ctx), "search.idx").exists()

    /** "33.4484, -112.0740", "33.4484 N 112.0740 W", or an MGRS grid. Returns (lat, lon) or null. */
    fun parseCoords(text: String): Pair<Double, Double>? {
        val t = text.trim()
        val dec = Regex("^([+-]?\\d{1,3}(?:\\.\\d+)?)\\s*\u00B0?\\s*([NSns])?\\s*[,;\\s]\\s*([+-]?\\d{1,3}(?:\\.\\d+)?)\\s*\u00B0?\\s*([EWew])?$").find(t)
        if (dec != null) {
            var lat = dec.groupValues[1].toDouble()
            var lon = dec.groupValues[3].toDouble()
            if (dec.groupValues[2].equals("S", true)) lat = -Math.abs(lat)
            if (dec.groupValues[4].equals("W", true)) lon = -Math.abs(lon)
            if (Math.abs(lat) <= 90.0 && Math.abs(lon) <= 180.0) return Pair(lat, lon)
            return null
        }
        return Mgrs.parse(t)
    }

    private fun contains(buf: ByteArray, len: Int, nd: ByteArray): Boolean {
        if (nd.isEmpty()) return true
        var i = 0
        val last = len - nd.size
        while (i <= last) {
            var j = 0
            while (j < nd.size) {
                var c = buf[i + j].toInt()
                if (c >= 65 && c <= 90) c += 32
                if (c != nd[j].toInt()) break
                j++
            }
            if (j == nd.size) return true
            i++
        }
        return false
    }

    /** Words must all be found in a line. Nearest to (cLat, cLon) first. */
    fun run(ctx: Context, query: String, cLat: Double, cLon: Double, cancelled: () -> Boolean, max: Int = 25): List<Hit> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val needles = words.map { it.toByteArray(Charsets.UTF_8) }
        val f = File(MapStore.dir(ctx), "search.idx")
        if (!f.exists()) return emptyList()
        val best = ArrayList<Hit>()
        val buf = ByteArray(256)
        val cosLat = Math.cos(Math.toRadians(cLat))
        var count = 0
        try {
            DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 17)).use { inp ->
                while (true) {
                    val kind = try { inp.readUnsignedByte() } catch (e: EOFException) { break }
                    val la = inp.readInt()
                    val lo = inp.readInt()
                    val len = inp.readUnsignedByte()
                    inp.readFully(buf, 0, len)
                    if ((++count and 4095) == 0 && cancelled()) return emptyList()
                    var ok = true
                    for (nd in needles) if (!contains(buf, len, nd)) { ok = false; break }
                    if (!ok) continue
                    val latD = la / 1e7
                    val lonD = lo / 1e7
                    val dy = (latD - cLat) * 110.574
                    val dx = (lonD - cLon) * 111.32 * cosLat
                    val km = Math.sqrt(dx * dx + dy * dy)
                    if (best.size >= max && km >= best[best.size - 1].km) continue
                    val text = String(buf, 0, len, Charsets.UTF_8)
                    if (kind == 4 || kind == 1) {
                        val idx = best.indexOfFirst { it.kind == kind && it.text == text }
                        if (idx >= 0) {
                            if (km >= best[idx].km) continue
                            best.removeAt(idx)
                        }
                    }
                    val hit = Hit(kind, latD, lonD, text, km)
                    var pos = best.size
                    while (pos > 0 && best[pos - 1].km > km) pos--
                    best.add(pos, hit)
                    if (best.size > max) best.removeAt(best.size - 1)
                }
            }
        } catch (e: Exception) {
        }
        return best
    }
}
