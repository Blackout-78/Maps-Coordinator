package app.mapscoordinator

import android.content.Context
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.Inflater

/**
 * Reads an OpenStreetMap .osm.pbf extract (for example from Geofabrik) straight from a file the
 * user picked, and writes roads, trails, water and stop signs into private map cells.
 * Everything happens on the phone. Nothing is sent anywhere.
 */
class PbfImporter(private val ctx: Context, private val progress: (String) -> Unit) {

    class Result(val name: String, val centerLat: Double, val centerLon: Double, val ways: Long, val stops: Long)

    class ImportException(msg: String) : Exception(msg)

    // ---- tiny protobuf reader ----
    private class R(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
        fun more() = p < end
        fun varint(): Long {
            var r = 0L
            var s = 0
            while (true) {
                val x = b[p++].toInt()
                r = r or ((x and 0x7f).toLong() shl s)
                if (x and 0x80 == 0) return r
                s += 7
            }
        }
        fun skip(wt: Int) {
            when (wt) {
                0 -> varint()
                1 -> p += 8
                2 -> { val l = varint().toInt(); p += l }
                5 -> p += 4
                else -> throw ImportException("Unsupported file data")
            }
        }
    }

    private fun packedLongs(r: R, zig: Boolean): LongArray {
        val len = r.varint().toInt()
        val e = r.p + len
        var a = LongArray(256)
        var n = 0
        while (r.p < e) {
            var v = r.varint()
            if (zig) v = (v ushr 1) xor -(v and 1L)
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
        return a.copyOf(n)
    }

    private fun packedInts(r: R): IntArray {
        val len = r.varint().toInt()
        val e = r.p + len
        var a = IntArray(256)
        var n = 0
        while (r.p < e) {
            val v = r.varint().toInt()
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
        return a.copyOf(n)
    }

    // ---- node store: sorted by id, 16 bytes each (id, lat E7, lon E7) ----
    private val nodeTmp = File(ctx.cacheDir, "nodes.tmp")
    private var nodeOut: DataOutputStream? = null
    private var nodeCount = 0L
    private var lastNodeId = Long.MIN_VALUE
    private var unsorted = false
    private var nodeBuf: MappedByteBuffer? = null
    private var raf: RandomAccessFile? = null

    private fun writeNode(id: Long, lat: Int, lon: Int) {
        if (id <= lastNodeId) unsorted = true
        lastNodeId = id
        val o = nodeOut ?: throw ImportException("This file is not sorted (points after roads). Use a Geofabrik .osm.pbf file.")
        o.writeLong(id)
        o.writeInt(lat)
        o.writeInt(lon)
        nodeCount++
    }

    private fun openNodes() {
        if (nodeBuf != null) return
        nodeOut?.flush()
        nodeOut?.close()
        nodeOut = null
        if (unsorted) throw ImportException("This file is not sorted. Use a Geofabrik .osm.pbf file.")
        if (nodeCount == 0L) throw ImportException("No map points found in this file.")
        if (nodeCount * 16L > Int.MAX_VALUE.toLong()) {
            throw ImportException("This file is too big for one import. Use a smaller region.")
        }
        raf = RandomAccessFile(nodeTmp, "r")
        nodeBuf = raf!!.channel.map(FileChannel.MapMode.READ_ONLY, 0, nodeCount * 16L)
    }

    /** Index of node id in the store, or -1. */
    private fun find(id: Long): Int {
        val buf = nodeBuf!!
        var lo = 0
        var hi = (nodeCount - 1).toInt()
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = buf.getLong(mid * 16)
            if (v < id) lo = mid + 1 else if (v > id) hi = mid - 1 else return mid
        }
        return -1
    }

    // ---- cell output ----
    private val cells = HashMap<Long, ByteArrayOutputStream>()
    private val dcells = HashMap<Long, ByteArrayOutputStream>()
    private val ovr = ByteArrayOutputStream(1 shl 16)
    private val srch = ByteArrayOutputStream(1 shl 16)
    private val seenStreets = HashSet<Long>()

    /** One line in the offline search index: kind 1 place, 2 business, 3 address, 4 street. */
    private fun writeSearch(kind: Int, lat: Int, lon: Int, text: String) {
        var b = text.toByteArray(Charsets.UTF_8)
        if (b.size > 200) b = b.copyOf(200)
        val o = DataOutputStream(srch)
        o.writeByte(kind)
        o.writeInt(lat)
        o.writeInt(lon)
        o.writeByte(b.size)
        o.write(b)
        bufBytes += 10L + b.size
    }
    private var bufBytes = 0L
    private var wayCount = 0L
    private var stopCount = 0L
    private var minLat = Int.MAX_VALUE
    private var maxLat = Int.MIN_VALUE
    private var minLon = Int.MAX_VALUE
    private var maxLon = Int.MIN_VALUE

    private fun cellKey(lat: Int, lon: Int): Long {
        val cy = Math.floorDiv(lat, MapStore.CELL_E7)
        val cx = Math.floorDiv(lon, MapStore.CELL_E7)
        return (cy.toLong() shl 32) or (cx.toLong() and 0xFFFFFFFFL)
    }

    private fun writePiece(key: Long, type: Int, lanes: Int, speed: Int,
                           lat: IntArray, lon: IntArray, from: Int, toInclusive: Int, nameBytes: ByteArray?) {
        val count = toInclusive - from + 1
        val bo = (if (type in 12..14) dcells else cells).getOrPut(key) { ByteArrayOutputStream(1024) }
        val o = DataOutputStream(bo)
        o.writeByte(if (nameBytes != null) (type or 0x80) else type)
        o.writeByte(if (lanes > 255) 255 else lanes)
        o.writeShort(speed)
        o.writeShort(count)
        for (i in from..toInclusive) {
            o.writeInt(lat[i])
            o.writeInt(lon[i])
        }
        if (nameBytes != null) {
            o.writeByte(nameBytes.size)
            o.write(nameBytes)
        }
        bufBytes += 6L + count * 8L + (if (nameBytes != null) 1L + nameBytes.size else 0L)
        if (bufBytes > 24L * 1024 * 1024) flushCells()
    }

    private fun emit(type: Int, lanes: Int, speed: Int, lat: IntArray, lon: IntArray, n: Int, name: String? = null) {
        var nameBytes: ByteArray? = null
        if (name != null && name.isNotEmpty()) {
            var b = name.toByteArray(Charsets.UTF_8)
            if (b.size > 120) b = b.copyOf(120)
            nameBytes = b
        }
        if (type <= MapStore.T_TERTIARY || type == MapStore.T_PLACE) writeOverview(type, lanes, speed, lat, lon, n, nameBytes)
        for (i in 0 until n) {
            if (lat[i] < minLat) minLat = lat[i]
            if (lat[i] > maxLat) maxLat = lat[i]
            if (lon[i] < minLon) minLon = lon[i]
            if (lon[i] > maxLon) maxLon = lon[i]
        }
        if (n == 1) {
            writePiece(cellKey(lat[0], lon[0]), type, lanes, speed, lat, lon, 0, 0, nameBytes)
            return
        }
        var start = 0
        var cur = cellKey(lat[0], lon[0])
        for (i in 1 until n) {
            val c = cellKey(lat[i], lon[i])
            if (c != cur) {
                writePiece(cur, type, lanes, speed, lat, lon, start, i, nameBytes)
                start = i
                cur = c
            }
        }
        if (n - 1 > start) writePiece(cur, type, lanes, speed, lat, lon, start, n - 1, nameBytes)
    }

    private fun centroid(a: IntArray, n: Int): Int {
        var sum = 0L
        for (i in 0 until n) sum += a[i]
        return (sum / n).toInt()
    }

    private fun writeOverview(type: Int, lanes: Int, speed: Int, lat: IntArray, lon: IntArray, n: Int, nameBytes: ByteArray?) {
        val ol = IntArray(n)
        val oo = IntArray(n)
        var m = 0
        for (i in 0 until n) {
            if (m == 0 || i == n - 1 ||
                Math.max(Math.abs(lat[i] - ol[m - 1]), Math.abs(lon[i] - oo[m - 1])) >= 3000) {
                ol[m] = lat[i]; oo[m] = lon[i]; m++
            }
        }
        if (n >= 2 && m < 2) return
        val o = DataOutputStream(ovr)
        var from = 0
        while (from < m) {
            val to = Math.min(m, from + 60000)
            o.writeByte(if (nameBytes != null) (type or 0x80) else type)
            o.writeByte(if (lanes > 255) 255 else lanes)
            o.writeShort(speed)
            o.writeShort(to - from)
            for (i in from until to) { o.writeInt(ol[i]); o.writeInt(oo[i]) }
            if (nameBytes != null) { o.writeByte(nameBytes.size); o.write(nameBytes) }
            bufBytes += 6L + (to - from) * 8L + (if (nameBytes != null) 1L + nameBytes.size else 0L)
            if (to >= m) break
            from = to - 1
        }
    }

    private fun flushCells() {
        val dir = MapStore.dir(ctx)
        for ((key, bo) in cells) {
            val cy = (key shr 32).toInt()
            val cx = key.toInt()
            FileOutputStream(File(dir, "${cy}_${cx}.t"), true).use { bo.writeTo(it) }
        }
        cells.clear()
        for ((key, bo) in dcells) {
            val cy = (key shr 32).toInt()
            val cx = key.toInt()
            FileOutputStream(File(dir, "${cy}_${cx}.d"), true).use { bo.writeTo(it) }
        }
        dcells.clear()
        if (srch.size() > 0) {
            FileOutputStream(File(dir, "search.idx"), true).use { srch.writeTo(it) }
            srch.reset()
        }
        if (ovr.size() > 0) {
            FileOutputStream(File(dir, "overview.t"), true).use { ovr.writeTo(it) }
            ovr.reset()
        }
        bufBytes = 0L
    }

    // ---- main ----
    private var bytesRead = 0L

    private inner class CountingStream(private val inp: InputStream) : InputStream() {
        override fun read(): Int {
            val v = inp.read()
            if (v >= 0) bytesRead++
            return v
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inp.read(b, off, len)
            if (n > 0) bytesRead += n
            return n
        }
    }

    fun import(input: InputStream, name: String, totalBytes: Long): Result {
        try {
            nodeOut = DataOutputStream(java.io.BufferedOutputStream(FileOutputStream(nodeTmp), 1 shl 16))
            val din = DataInputStream(BufferedInputStream(CountingStream(input), 1 shl 16))
            var blocks = 0
            while (true) {
                val hlen = try {
                    din.readInt()
                } catch (e: EOFException) {
                    break
                }
                if (hlen <= 0 || hlen > 65536) throw ImportException("This is not a .osm.pbf file.")
                val hb = ByteArray(hlen)
                din.readFully(hb)
                var type = ""
                var dataSize = 0
                val hr = R(hb)
                while (hr.more()) {
                    val tag = hr.varint().toInt()
                    val f = tag ushr 3
                    val wt = tag and 7
                    if (f == 1 && wt == 2) {
                        val l = hr.varint().toInt()
                        type = String(hb, hr.p, l, Charsets.UTF_8)
                        hr.p += l
                    } else if (f == 3 && wt == 0) {
                        dataSize = hr.varint().toInt()
                    } else hr.skip(wt)
                }
                if (dataSize <= 0 || dataSize > 64 * 1024 * 1024) throw ImportException("This is not a .osm.pbf file.")
                val blob = ByteArray(dataSize)
                din.readFully(blob)
                if (type == "OSMData") {
                    processBlob(blob)
                    blocks++
                    if (blocks % 40 == 0) {
                        val pct = if (totalBytes > 0) " " + (bytesRead * 100 / totalBytes) + "%" else ""
                        progress("Importing$pct\n${nodeCount / 1000}k points, ${wayCount / 1000}k roads and trails")
                    }
                }
            }
            if (wayCount == 0L) throw ImportException("No roads found in this file.")
            progress("Saving…")
            flushCells()
            val cLat = ((minLat.toLong() + maxLat) / 2) / 1e7
            val cLon = ((minLon.toLong() + maxLon) / 2) / 1e7
            File(MapStore.dir(ctx), "regions.txt").appendText(
                "$name|${minLat / 1e7}|${minLon / 1e7}|${maxLat / 1e7}|${maxLon / 1e7}|$wayCount\n")
            return Result(name, cLat, cLon, wayCount, stopCount)
        } finally {
            try { nodeOut?.close() } catch (e: Exception) { }
            try { raf?.close() } catch (e: Exception) { }
            nodeBuf = null
            nodeTmp.delete()
        }
    }

    private fun processBlob(blob: ByteArray) {
        val r = R(blob)
        var raw: ByteArray? = null
        var rawSize = 0
        var zStart = -1
        var zLen = 0
        while (r.more()) {
            val tag = r.varint().toInt()
            val f = tag ushr 3
            val wt = tag and 7
            when {
                f == 1 && wt == 2 -> { val l = r.varint().toInt(); raw = blob.copyOfRange(r.p, r.p + l); r.p += l }
                f == 2 && wt == 0 -> rawSize = r.varint().toInt()
                f == 3 && wt == 2 -> { zLen = r.varint().toInt(); zStart = r.p; r.p += zLen }
                else -> r.skip(wt)
            }
        }
        val data: ByteArray = if (raw != null) raw else {
            if (zStart < 0) return
            val inf = Inflater()
            inf.setInput(blob, zStart, zLen)
            val out = ByteArray(rawSize)
            var off = 0
            while (off < rawSize) {
                val n = inf.inflate(out, off, rawSize - off)
                if (n == 0 && (inf.finished() || inf.needsInput())) break
                off += n
            }
            inf.end()
            out
        }
        processBlock(data)
    }

    private fun processBlock(d: ByteArray) {
        val r = R(d)
        val strings = ArrayList<String>(4096)
        val groups = ArrayList<IntArray>()
        var gran = 100L
        var latOff = 0L
        var lonOff = 0L
        while (r.more()) {
            val tag = r.varint().toInt()
            val f = tag ushr 3
            val wt = tag and 7
            when {
                f == 1 && wt == 2 -> {
                    val len = r.varint().toInt()
                    val e = r.p + len
                    while (r.p < e) {
                        val t2 = r.varint().toInt()
                        if ((t2 ushr 3) == 1 && (t2 and 7) == 2) {
                            val l = r.varint().toInt()
                            strings.add(String(d, r.p, l, Charsets.UTF_8))
                            r.p += l
                        } else r.skip(t2 and 7)
                    }
                    r.p = e
                }
                f == 2 && wt == 2 -> { val len = r.varint().toInt(); groups.add(intArrayOf(r.p, r.p + len)); r.p += len }
                f == 17 && wt == 0 -> gran = r.varint()
                f == 19 && wt == 0 -> latOff = r.varint()
                f == 20 && wt == 0 -> lonOff = r.varint()
                else -> r.skip(wt)
            }
        }
        for (g in groups) processGroup(d, g[0], g[1], strings, gran, latOff, lonOff)
    }

    private fun processGroup(d: ByteArray, s: Int, e: Int, strings: List<String>, gran: Long, latOff: Long, lonOff: Long) {
        val r = R(d, s, e)
        while (r.more()) {
            val tag = r.varint().toInt()
            val f = tag ushr 3
            val wt = tag and 7
            if (wt != 2) { r.skip(wt); continue }
            val len = r.varint().toInt()
            val end = r.p + len
            when (f) {
                1 -> plainNode(d, r.p, end, gran, latOff, lonOff)
                2 -> dense(d, r.p, end, strings, gran, latOff, lonOff)
                3 -> way(d, r.p, end, strings)
            }
            r.p = end
        }
    }

    private fun plainNode(d: ByteArray, s: Int, e: Int, gran: Long, latOff: Long, lonOff: Long) {
        val r = R(d, s, e)
        var id = 0L
        var la = 0L
        var lo = 0L
        while (r.more()) {
            val tag = r.varint().toInt()
            val f = tag ushr 3
            val wt = tag and 7
            if (wt == 0 && (f == 1 || f == 8 || f == 9)) {
                val v = r.varint()
                val z = (v ushr 1) xor -(v and 1L)
                when (f) { 1 -> id = z; 8 -> la = z; 9 -> lo = z }
            } else r.skip(wt)
        }
        writeNode(id, ((latOff + gran * la) / 100).toInt(), ((lonOff + gran * lo) / 100).toInt())
    }

    private fun dense(d: ByteArray, s: Int, e: Int, strings: List<String>, gran: Long, latOff: Long, lonOff: Long) {
        val r = R(d, s, e)
        var ids = LongArray(0)
        var lats = LongArray(0)
        var lons = LongArray(0)
        var kv = IntArray(0)
        while (r.more()) {
            val tag = r.varint().toInt()
            val f = tag ushr 3
            val wt = tag and 7
            if (wt == 2) {
                when (f) {
                    1 -> ids = packedLongs(r, true)
                    8 -> lats = packedLongs(r, true)
                    9 -> lons = packedLongs(r, true)
                    10 -> kv = packedInts(r)
                    else -> r.skip(wt)
                }
            } else r.skip(wt)
        }
        var id = 0L
        var la = 0L
        var lo = 0L
        var k = 0
        val one = IntArray(1)
        val oneLon = IntArray(1)
        for (i in ids.indices) {
            id += ids[i]
            la += lats[i]
            lo += lons[i]
            val latE7 = ((latOff + gran * la) / 100).toInt()
            val lonE7 = ((lonOff + gran * lo) / 100).toInt()
            writeNode(id, latE7, lonE7)
            if (kv.isNotEmpty()) {
                var isStop = false
                var name: String? = null
                var place: String? = null
                var house: String? = null
                var amenity: String? = null
                var shop: String? = null
                var tourism: String? = null
                var street: String? = null
                var city: String? = null
                while (k < kv.size && kv[k] != 0) {
                    val key = strings[kv[k]]
                    val v = strings[kv[k + 1]]
                    when (key) {
                        "highway" -> if (v == "stop") isStop = true
                        "name" -> name = v
                        "place" -> place = v
                        "addr:housenumber" -> house = v
                        "amenity" -> amenity = v
                        "shop" -> shop = v
                        "tourism" -> tourism = v
                        "addr:street" -> street = v
                        "addr:city" -> city = v
                    }
                    k += 2
                }
                k++
                one[0] = latE7
                oneLon[0] = lonE7
                if (isStop) {
                    emit(MapStore.T_STOP, 0, 0, one, oneLon, 1)
                    stopCount++
                }
                if (name != null && place != null) {
                    val rank = when (place) {
                        "city" -> 1; "town" -> 2; "village" -> 3; "hamlet" -> 4; "suburb" -> 5; else -> 0
                    }
                    if (rank > 0) { emit(MapStore.T_PLACE, rank, 0, one, oneLon, 1, name); writeSearch(1, latE7, lonE7, name) }
                } else if (name != null && (amenity != null || shop != null || tourism != null)) {
                    val cat = when {
                        amenity in foodAmenity -> 1
                        shop != null -> 2
                        amenity == "fuel" -> 3
                        tourism == "hotel" || tourism == "motel" || tourism == "hostel" -> 4
                        amenity == "hospital" || amenity == "pharmacy" || amenity == "clinic" || amenity == "doctors" -> 5
                        else -> 6
                    }
                    emit(MapStore.T_POI, cat, 0, one, oneLon, 1, name)
                    writeSearch(2, latE7, lonE7, name)
                } else if (house != null) {
                    emit(MapStore.T_HOUSE, 0, 0, one, oneLon, 1, house)
                    if (street != null) writeSearch(3, latE7, lonE7, if (city != null) "$house $street, $city" else "$house $street")
                }
            }
        }
    }

    private val foodAmenity = setOf("restaurant", "cafe", "fast_food", "bar", "pub", "ice_cream", "food_court")

    private fun parseSpeed(v: String): Int {
        val digits = v.takeWhile { it.isDigit() }
        val n = digits.toIntOrNull() ?: return 0
        return if (v.contains("mph")) n else (n / 1.609).toInt()
    }

    private val unpaved = setOf("unpaved", "dirt", "gravel", "sand", "ground", "grass", "mud", "compacted",
        "fine_gravel", "earth", "dirt/sand", "pebblestone", "rock")

    private fun way(d: ByteArray, s: Int, e: Int, strings: List<String>) {
        val r = R(d, s, e)
        var keys = IntArray(0)
        var vals = IntArray(0)
        var refs = LongArray(0)
        while (r.more()) {
            val tag = r.varint().toInt()
            val f = tag ushr 3
            val wt = tag and 7
            if (wt == 2) {
                when (f) {
                    2 -> keys = packedInts(r)
                    3 -> vals = packedInts(r)
                    8 -> refs = packedLongs(r, true)
                    else -> r.skip(wt)
                }
            } else r.skip(wt)
        }
        if (refs.size < 2 || keys.isEmpty()) return
        var hw: String? = null
        var surface: String? = null
        var waterway: String? = null
        var natural: String? = null
        var lanes = 0
        var speed = 0
        var wayName: String? = null
        var wayRef: String? = null
        var building: String? = null
        var house: String? = null
        var wStreet: String? = null
        var wCity: String? = null
        var wPoi = false
        for (i in keys.indices) {
            val k = strings[keys[i]]
            val v = strings[vals[i]]
            when (k) {
                "highway" -> hw = v
                "surface" -> surface = v
                "waterway" -> waterway = v
                "natural" -> natural = v
                "name" -> wayName = v
                "ref" -> wayRef = v
                "building" -> building = v
                "addr:street" -> wStreet = v
                "addr:city" -> wCity = v
                "amenity", "shop", "tourism" -> wPoi = true
                "addr:housenumber" -> house = v
                "lanes" -> lanes = v.toIntOrNull() ?: 0
                "maxspeed" -> speed = parseSpeed(v)
            }
        }
        var type = -1
        if (hw != null) {
            type = when (hw) {
                "motorway", "motorway_link", "trunk", "trunk_link" -> MapStore.T_MOTORWAY
                "primary", "primary_link" -> MapStore.T_PRIMARY
                "secondary", "secondary_link" -> MapStore.T_SECONDARY
                "tertiary", "tertiary_link" -> MapStore.T_TERTIARY
                "residential", "unclassified", "living_street" -> MapStore.T_LOCAL
                "service", "road" -> MapStore.T_SERVICE
                "track" -> MapStore.T_DIRT
                "path", "footway", "bridleway", "cycleway", "steps", "pedestrian" -> MapStore.T_TRAIL
                else -> -1
            }
            if (type in 3..5 && surface != null && surface in unpaved) type = MapStore.T_DIRT
        } else if (waterway == "river" || waterway == "stream" || waterway == "canal" || waterway == "drain") {
            type = MapStore.T_WATER
        } else if (natural == "water") {
            type = MapStore.T_WATER
        } else if (building != null && building != "no") {
            type = MapStore.T_BUILD
        }
        val poiArea = type < 0 && wayName != null && wPoi
        if (type < 0 && !poiArea) return
        openNodes()
        val lat = IntArray(refs.size)
        val lon = IntArray(refs.size)
        var n = 0
        var ref = 0L
        val buf = nodeBuf!!
        for (i in refs.indices) {
            ref += refs[i]
            val idx = find(ref)
            if (idx >= 0) {
                lat[n] = buf.getInt(idx * 16 + 8)
                lon[n] = buf.getInt(idx * 16 + 12)
                n++
            }
        }
        if (n < 2) return
        if (poiArea) {
            writeSearch(2, centroid(lat, n), centroid(lon, n), wayName!!)
            return
        }
        val sName = wayName ?: wayRef?.substringBefore(';')
        if (type <= MapStore.T_TRAIL && sName != null) {
            val key = sName.hashCode().toLong() * 1000003L + cellKey(lat[n / 2], lon[n / 2])
            if (seenStreets.add(key)) writeSearch(4, lat[n / 2], lon[n / 2], sName)
        } else if (type == MapStore.T_BUILD) {
            if (house != null && wStreet != null) {
                writeSearch(3, centroid(lat, n), centroid(lon, n), if (wCity != null) "$house $wStreet, $wCity" else "$house $wStreet")
            } else if (wayName != null && wPoi) {
                writeSearch(2, centroid(lat, n), centroid(lon, n), wayName)
            }
        }
        wayCount++
        val label = if (type <= MapStore.T_TRAIL) (wayName ?: wayRef?.substringBefore(';')) else if (type == MapStore.T_BUILD) house else null
        emit(type, lanes, speed, lat, lon, n, label)
    }
}
