package app.mapscoordinator

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream

/** Offline elevation from SRTM .hgt tiles (big-endian int16 grids). No network. */
object Dem {
    class DemException(msg: String) : Exception(msg)

    private class Tile(val buf: MappedByteBuffer, val n: Int)

    private const val N1 = 3601
    private const val N3 = 1201
    @Volatile private var folder: File? = null
    private val tiles = HashMap<Int, Tile?>()
    @Volatile private var count = -1

    fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "dem")
        if (!d.exists()) d.mkdirs()
        folder = d
        return d
    }

    fun use(ctx: Context) { dir(ctx) }

    fun tileCount(ctx: Context): Int = dir(ctx).listFiles { f -> f.name.endsWith(".hgt") }?.size ?: 0

    fun hasAny(ctx: Context): Boolean {
        if (count < 0) count = tileCount(ctx)
        return count > 0
    }

    fun sizeBytes(ctx: Context): Long = dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    @Synchronized fun clear(ctx: Context) {
        tiles.clear()
        dir(ctx).listFiles()?.forEach { it.delete() }
        count = 0
    }

    fun tileName(lat: Int, lon: Int): String =
        String.format("%s%02d%s%03d", if (lat >= 0) "N" else "S", Math.abs(lat), if (lon >= 0) "E" else "W", Math.abs(lon))

    /** Saves one tile. Accepts .hgt or .hgt.gz named like N33W112. Returns the tile name. */
    @Synchronized fun importTile(ctx: Context, fileName: String, input: InputStream): String {
        val m = Regex("([NSns])(\\d{2})([EWew])(\\d{3})").find(fileName)
            ?: throw DemException("File name must look like N33W112.hgt (that is the tile's south-west corner).")
        val name = (m.groupValues[1] + m.groupValues[2] + m.groupValues[3] + m.groupValues[4]).uppercase()
        val src = if (fileName.lowercase().endsWith(".gz")) GZIPInputStream(input, 1 shl 16) else input
        val tmp = File(dir(ctx), "$name.tmp")
        var total = 0L
        FileOutputStream(tmp).use { out ->
            val b = ByteArray(1 shl 16)
            while (true) {
                val r = src.read(b)
                if (r < 0) break
                out.write(b, 0, r)
                total += r
            }
        }
        if (total != 2L * N1 * N1 && total != 2L * N3 * N3) {
            tmp.delete()
            throw DemException("$fileName is not a valid elevation tile (wrong size).")
        }
        val dst = File(dir(ctx), "$name.hgt")
        tiles.clear()
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) { tmp.delete(); throw DemException("Could not save $name.") }
        count = tileCount(ctx)
        return name
    }

    @Synchronized private fun tile(la: Int, lo: Int): Tile? {
        val key = (la + 100) * 1000 + (lo + 400)
        if (tiles.containsKey(key)) return tiles[key]
        val d = folder ?: return null
        var t: Tile? = null
        val f = File(d, tileName(la, lo) + ".hgt")
        if (f.exists()) {
            try {
                val n = if (f.length() == 2L * N1 * N1) N1 else N3
                FileChannel.open(f.toPath(), java.nio.file.StandardOpenOption.READ).use { ch ->
                    val mb = ch.map(FileChannel.MapMode.READ_ONLY, 0, f.length())
                    mb.order(ByteOrder.BIG_ENDIAN)
                    t = Tile(mb, n)
                }
            } catch (e: Throwable) { t = null }
        }
        tiles[key] = t
        return t
    }

    private fun at(t: Tile, r: Int, c: Int): Float {
        val v = t.buf.getShort((r * t.n + c) * 2).toInt()
        return if (v == -32768) Float.NaN else v.toFloat()
    }

    /** Metres above sea level, or NaN when no tile covers the point. */
    fun elevation(lat: Double, lon: Double): Float {
        val la = Math.floor(lat).toInt()
        val lo = Math.floor(lon).toInt()
        val t = tile(la, lo) ?: return Float.NaN
        val s = (t.n - 1).toDouble()
        val fy = (la + 1 - lat) * s
        val fx = (lon - lo) * s
        var r = fy.toInt(); var c = fx.toInt()
        if (r >= t.n - 1) r = t.n - 2
        if (c >= t.n - 1) c = t.n - 2
        if (r < 0 || c < 0) return Float.NaN
        val dy = (fy - r).toFloat(); val dx = (fx - c).toFloat()
        val a = at(t, r, c); val b = at(t, r, c + 1)
        val d = at(t, r + 1, c); val e = at(t, r + 1, c + 1)
        val top = a + (b - a) * dx
        val bot = d + (e - d) * dx
        return top + (bot - top) * dy
    }
}
