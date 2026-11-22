package app.mapscoordinator

import android.content.Context
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream

/**
 * Imported map data lives only in this app's private storage.
 * One small file per map cell (0.02 degrees, about 2 km). All coordinates are degrees x 10,000,000.
 */
object MapStore {
    const val CELL_E7 = 200000

    class Poly(val type: Int, val lanes: Int, val speed: Int, val lat: IntArray, val lon: IntArray, val name: String? = null) {
        val minLat: Int
        val maxLat: Int
        val minLon: Int
        val maxLon: Int

        init {
            var a = Int.MAX_VALUE; var b = Int.MIN_VALUE; var c = Int.MAX_VALUE; var e = Int.MIN_VALUE
            for (i in lat.indices) {
                val la = lat[i]; val lo = lon[i]
                if (la < a) a = la
                if (la > b) b = la
                if (lo < c) c = lo
                if (lo > e) e = lo
            }
            minLat = a; maxLat = b; minLon = c; maxLon = e
        }
    }

    // type codes
    const val T_MOTORWAY = 0
    const val T_PRIMARY = 1
    const val T_SECONDARY = 2
    const val T_TERTIARY = 3
    const val T_LOCAL = 4
    const val T_SERVICE = 5
    const val T_DIRT = 7
    const val T_TRAIL = 8
    const val T_WATER = 9
    const val T_STOP = 10
    const val T_PLACE = 11   // city/town name, lanes = rank 1 city .. 5 suburb
    const val T_POI = 12     // business, lanes = category (detail file)
    const val T_HOUSE = 13   // house number point (detail file)
    const val T_BUILD = 14   // building outline, name = house number (detail file)

    fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "maps")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun cellFile(ctx: Context, cx: Int, cy: Int): File = File(dir(ctx), "${cy}_${cx}.t")

    fun loadCell(ctx: Context, cx: Int, cy: Int): List<Poly> = loadFile(File(dir(ctx), "${cy}_${cx}.t"))

    /** Buildings, house numbers and businesses: only loaded when zoomed in. */
    fun loadDetail(ctx: Context, cx: Int, cy: Int): List<Poly> = loadFile(File(dir(ctx), "${cy}_${cx}.d"))

    private fun loadFile(f: File): List<Poly> {
        if (!f.exists()) return emptyList()
        val out = ArrayList<Poly>()
        try {
            DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 16)).use { inp ->
                while (true) {
                    val rawType = try {
                        inp.readUnsignedByte()
                    } catch (e: EOFException) {
                        break
                    }
                    val type = rawType and 0x7F
                    val named = (rawType and 0x80) != 0
                    val lanes = inp.readUnsignedByte()
                    val speed = inp.readUnsignedShort()
                    val n = inp.readUnsignedShort()
                    val lat = IntArray(n)
                    val lon = IntArray(n)
                    for (i in 0 until n) {
                        lat[i] = inp.readInt()
                        lon[i] = inp.readInt()
                    }
                    var name: String? = null
                    if (named) {
                        val len = inp.readUnsignedByte()
                        val b = ByteArray(len)
                        inp.readFully(b)
                        name = String(b, Charsets.UTF_8)
                    }
                    out.add(Poly(type, lanes, speed, lat, lon, name))
                }
            }
        } catch (e: Exception) {
            // A damaged cell file shows what could be read and nothing more.
        }
        return out
    }

    /** Fast check: is there at least one file? Does not list the whole folder. */
    fun hasAny(ctx: Context): Boolean = try {
        java.nio.file.Files.newDirectoryStream(dir(ctx).toPath()).use { it.iterator().hasNext() }
    } catch (e: Exception) {
        false
    }

    /** Major roads and place names for the zoomed-out view. Written during import. */
    fun loadOverview(ctx: Context): List<Poly> = loadFile(File(dir(ctx), "overview.t"))

    /** Builds overview.t from the existing cell files (for maps imported before it existed). One time. */
    fun buildOverview(ctx: Context) {
        val d = dir(ctx)
        val tmp = File(d, "overview.tmp")
        java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(tmp), 1 shl 16)).use { o ->
            val files = d.listFiles { f -> f.name.endsWith(".t") && f.name != "overview.t" } ?: emptyArray()
            for (f in files) {
                for (p in loadFile(f)) {
                    if (p.type > T_TERTIARY && p.type != T_PLACE) continue
                    val n = p.lat.size
                    if (n == 0 || n > 60000) continue
                    val la = IntArray(n)
                    val lo = IntArray(n)
                    var m = 0
                    for (i in 0 until n) {
                        if (m == 0 || i == n - 1 ||
                            Math.max(Math.abs(p.lat[i] - la[m - 1]), Math.abs(p.lon[i] - lo[m - 1])) >= 3000) {
                            la[m] = p.lat[i]; lo[m] = p.lon[i]; m++
                        }
                    }
                    if (n >= 2 && m < 2) continue
                    val nb = p.name?.toByteArray(Charsets.UTF_8)?.let { if (it.size > 120) it.copyOf(120) else it }
                    o.writeByte(if (nb != null) (p.type or 0x80) else p.type)
                    o.writeByte(if (p.lanes > 255) 255 else p.lanes)
                    o.writeShort(p.speed)
                    o.writeShort(m)
                    for (i in 0 until m) { o.writeInt(la[i]); o.writeInt(lo[i]) }
                    if (nb != null) { o.writeByte(nb.size); o.write(nb) }
                }
            }
        }
        val dst = File(d, "overview.t")
        dst.delete()
        tmp.renameTo(dst)
    }

    fun cellCount(ctx: Context): Int =
        dir(ctx).listFiles { f -> f.name.endsWith(".t") && f.name != "overview.t" }?.size ?: 0

    fun sizeBytes(ctx: Context): Long {
        var s = 0L
        dir(ctx).listFiles()?.forEach { s += it.length() }
        return s
    }

    fun regions(ctx: Context): List<String> {
        val f = File(dir(ctx), "regions.txt")
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.map { it.substringBefore('|') }
    }

    fun clear(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }
}
