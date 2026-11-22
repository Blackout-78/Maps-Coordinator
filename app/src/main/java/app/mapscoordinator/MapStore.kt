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

    class Poly(val type: Int, val lanes: Int, val speed: Int, val lat: IntArray, val lon: IntArray)

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

    fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "maps")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun cellFile(ctx: Context, cx: Int, cy: Int): File = File(dir(ctx), "${cy}_${cx}.t")

    fun loadCell(ctx: Context, cx: Int, cy: Int): List<Poly> {
        val f = cellFile(ctx, cx, cy)
        if (!f.exists()) return emptyList()
        val out = ArrayList<Poly>()
        try {
            DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 16)).use { inp ->
                while (true) {
                    val type = try {
                        inp.readUnsignedByte()
                    } catch (e: EOFException) {
                        break
                    }
                    val lanes = inp.readUnsignedByte()
                    val speed = inp.readUnsignedShort()
                    val n = inp.readUnsignedShort()
                    val lat = IntArray(n)
                    val lon = IntArray(n)
                    for (i in 0 until n) {
                        lat[i] = inp.readInt()
                        lon[i] = inp.readInt()
                    }
                    out.add(Poly(type, lanes, speed, lat, lon))
                }
            }
        } catch (e: Exception) {
            // A damaged cell file shows what could be read and nothing more.
        }
        return out
    }

    fun cellCount(ctx: Context): Int = dir(ctx).listFiles { f -> f.name.endsWith(".t") }?.size ?: 0

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
