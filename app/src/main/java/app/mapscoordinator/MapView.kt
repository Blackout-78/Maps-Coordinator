package app.mapscoordinator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.max

/** Night-style map. North is up. Pan with one finger, zoom with two. */
class MapView(ctx: Context) : View(ctx) {

    var centerLat = 33.4484
    var centerLon = -112.0740
    var mpp = 4.0 // metres per pixel
    var follow = true
    var hasFix = false
    var myLat = 0.0
    var myLon = 0.0
    var myBearing = -1f
    var myAccuracy = 0f
    var onUserPan: (() -> Unit)? = null
    var hasMaps = false

    private val cache = object : LinkedHashMap<Long, List<MapStore.Poly>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<MapStore.Poly>>?): Boolean = size > 120
    }
    private val loading = HashSet<Long>()
    private val dcache = object : LinkedHashMap<Long, List<MapStore.Poly>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<MapStore.Poly>>?): Boolean = size > 16
    }
    private val dloading = HashSet<Long>()
    private val exec = Executors.newSingleThreadExecutor()

    private val bg = Color.parseColor("#0E1424")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C5D0EA")
        textSize = 15f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()

    private val casing = Color.parseColor("#0A0F1C")
    private val roadFill = intArrayOf(
        Color.parseColor("#5A6FA8"), Color.parseColor("#4E639C"), Color.parseColor("#44578C"),
        Color.parseColor("#3C4D7E"), Color.parseColor("#34436F"), Color.parseColor("#2E3B63")
    )
    private val widthM = floatArrayOf(14f, 11f, 9f, 8f, 7f, 4f)

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val cosLat = cos(Math.toRadians(centerLat))
            centerLon += dx * mpp / (111320.0 * cosLat)
            centerLat -= dy * mpp / 110574.0
            if (follow) { follow = false; onUserPan?.invoke() }
            invalidate()
            return true
        }
    })
    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            mpp = (mpp / d.scaleFactor).coerceIn(0.3, 80.0)
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    fun zoomBy(f: Double) {
        mpp = (mpp / f).coerceIn(0.3, 80.0)
        invalidate()
    }

    fun clearCache() {
        synchronized(cache) { cache.clear() }
        loading.clear()
        synchronized(dcache) { dcache.clear() }
        dloading.clear()
        invalidate()
    }


    private fun getCell(cx: Int, cy: Int): List<MapStore.Poly>? {
        val key = (cy.toLong() shl 32) or (cx.toLong() and 0xFFFFFFFFL)
        synchronized(cache) {
            val c = cache[key]
            if (c != null) return c
            if (loading.add(key)) {
                val appCtx = context.applicationContext
                exec.execute {
                    val polys = MapStore.loadCell(appCtx, cx, cy)
                    synchronized(cache) { cache[key] = polys; loading.remove(key) }
                    postInvalidate()
                }
            }
        }
        return null
    }

    private fun getDetail(cx: Int, cy: Int): List<MapStore.Poly>? {
        val key = (cy.toLong() shl 32) or (cx.toLong() and 0xFFFFFFFFL)
        synchronized(dcache) {
            val c = dcache[key]
            if (c != null) return c
            if (dloading.add(key)) {
                val appCtx = context.applicationContext
                exec.execute {
                    val polys = MapStore.loadDetail(appCtx, cx, cy)
                    synchronized(dcache) { dcache[key] = polys; dloading.remove(key) }
                    postInvalidate()
                }
            }
        }
        return null
    }

    // ---- labels ----
    private val dens = resources.displayMetrics.scaledDensity
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6ECFA")
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0E1424")
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeJoin = Paint.Join.ROUND
        textAlign = Paint.Align.LEFT
    }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        textSize = 17f * dens
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        textSize = 7f * dens
    }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG)
    private val placed = ArrayList<RectF>()
    private val usedNames = ArrayList<Triple<String, Float, Float>>()
    private val signPos = ArrayList<Triple<Int, Float, Float>>()
    private var fw = 0f
    private var fh = 0f
    private var fcLat = 0.0
    private var fcLon = 0.0
    private var fkx = 1.0
    private var vMinLat = 0
    private var vMaxLat = 0
    private var vMinLon = 0
    private var vMaxLon = 0

    private fun visible(p: MapStore.Poly): Boolean =
        p.maxLat >= vMinLat && p.minLat <= vMaxLat && p.maxLon >= vMinLon && p.minLon <= vMaxLon
    private var fky = 1.0

    private fun sx(lon: Int): Float = (fw / 2 + (lon - fcLon) * fkx).toFloat()
    private fun sy(lat: Int): Float = (fh / 2 - (lat - fcLat) * fky).toFloat()

    private fun free(r: RectF): Boolean {
        for (q in placed) if (RectF.intersects(q, r)) return false
        placed.add(r)
        return true
    }

    private fun setSize(sp: Float) {
        labelPaint.textSize = sp * dens
        haloPaint.textSize = sp * dens
    }

    private fun maxMppForName(t: Int): Double = when (t) {
        0, 1, 2 -> 25.0
        3 -> 15.0
        4 -> 8.0
        else -> 3.0
    }

    private fun drawStreetNames(c: Canvas, polys: List<MapStore.Poly>) {
        setSize(12f)
        val ts = labelPaint.textSize
        for (p in polys) {
            val name = p.name ?: continue
            if (p.type > MapStore.T_TRAIL || mpp > maxMppForName(p.type)) continue
            var best = -1
            var bl = 0f
            for (i in 0 until p.lat.size - 1) {
                val x1 = sx(p.lon[i]); val y1 = sy(p.lat[i])
                val x2 = sx(p.lon[i + 1]); val y2 = sy(p.lat[i + 1])
                if ((x1 < 0 && x2 < 0) || (x1 > fw && x2 > fw) || (y1 < 0 && y2 < 0) || (y1 > fh && y2 > fh)) continue
                val len = hypot(x2 - x1, y2 - y1)
                if (len > bl) { bl = len; best = i }
            }
            if (best < 0 || bl < 40f) continue
            val tw = labelPaint.measureText(name)
            if (bl < tw + 12f) continue
            var ax = sx(p.lon[best]); var ay = sy(p.lat[best])
            var bx = sx(p.lon[best + 1]); var by = sy(p.lat[best + 1])
            if (bx < ax) { val tx = ax; ax = bx; bx = tx; val ty = ay; ay = by; by = ty }
            val mx = (ax + bx) / 2; val my = (ay + by) / 2
            if (mx < 0 || mx > fw || my < 0 || my > fh) continue
            var dup = false
            for (u in usedNames) if (u.first == name && hypot(u.second - mx, u.third - my) < 260f) { dup = true; break }
            if (dup) continue
            val cs = abs(bx - ax) / bl
            val sn = abs(by - ay) / bl
            val r = RectF(mx - (tw / 2 * cs + ts / 2 * sn), my - (tw / 2 * sn + ts / 2 * cs),
                mx + (tw / 2 * cs + ts / 2 * sn), my + (tw / 2 * sn + ts / 2 * cs))
            if (!free(r)) continue
            usedNames.add(Triple(name, mx, my))
            path.reset()
            path.moveTo(ax, ay)
            path.lineTo(bx, by)
            val h = (bl - tw) / 2
            c.drawTextOnPath(name, path, h, ts * 0.35f, haloPaint)
            c.drawTextOnPath(name, path, h, ts * 0.35f, labelPaint)
        }
    }

    private fun drawSpeedSigns(c: Canvas, polys: List<MapStore.Poly>) {
        val sw = 22f * dens
        val shh = 29f * dens
        for (p in polys) {
            if (p.speed <= 0 || p.type > MapStore.T_LOCAL || p.lat.size < 2) continue
            val i = p.lat.size / 2
            val x = sx(p.lon[i]) + 14f * dens
            val y = sy(p.lat[i]) - 14f * dens
            if (x < 0 || x > fw || y < 0 || y > fh) continue
            var near = false
            for (q in signPos) if (q.first == p.speed && hypot(q.second - x, q.third - y) < 280f) { near = true; break }
            if (near) continue
            val r = RectF(x - sw / 2, y - shh / 2, x + sw / 2, y + shh / 2)
            if (!free(r)) continue
            signPos.add(Triple(p.speed, x, y))
            box.style = Paint.Style.FILL; box.color = Color.WHITE
            c.drawRoundRect(r, 4f, 4f, box)
            box.style = Paint.Style.STROKE; box.color = Color.BLACK; box.strokeWidth = 2f
            c.drawRoundRect(r, 4f, 4f, box)
            c.drawText("SPEED", x, r.top + 9f * dens, small)
            c.drawText("LIMIT", x, r.top + 15f * dens, small)
            c.drawText(p.speed.toString(), x, r.bottom - 4f * dens, num)
        }
    }

    private fun poiColor(cat: Int): Int = when (cat) {
        1 -> Color.parseColor("#E8884A")
        2 -> Color.parseColor("#5AB0E8")
        3 -> Color.parseColor("#E8D24A")
        4 -> Color.parseColor("#A98AE8")
        5 -> Color.parseColor("#E85A7A")
        else -> Color.parseColor("#8AA0C0")
    }

    private fun drawBusinesses(c: Canvas, polys: List<MapStore.Poly>) {
        setSize(11f)
        val ts = labelPaint.textSize
        for (p in polys) {
            if (p.type != MapStore.T_POI) continue
            val x = sx(p.lon[0]); val y = sy(p.lat[0])
            if (x < -20 || x > fw + 20 || y < -20 || y > fh + 20) continue
            fillPaint.color = poiColor(p.lanes)
            c.drawCircle(x, y, 5f * dens, fillPaint)
            val name = p.name ?: continue
            val tw = labelPaint.measureText(name)
            val r = RectF(x - 6f * dens, y - ts, x + 10f * dens + tw, y + ts / 2)
            if (!free(r)) continue
            c.drawText(name, x + 8f * dens, y + ts * 0.35f, haloPaint)
            c.drawText(name, x + 8f * dens, y + ts * 0.35f, labelPaint)
        }
    }

    private fun drawHouseNumbers(c: Canvas, polys: List<MapStore.Poly>) {
        setSize(10f)
        val ts = labelPaint.textSize
        labelPaint.textAlign = Paint.Align.CENTER
        haloPaint.textAlign = Paint.Align.CENTER
        for (p in polys) {
            val name = p.name ?: continue
            if (p.type != MapStore.T_HOUSE && p.type != MapStore.T_BUILD) continue
            var ax = 0.0; var ay = 0.0
            for (i in p.lat.indices) { ax += sx(p.lon[i]); ay += sy(p.lat[i]) }
            val x = (ax / p.lat.size).toFloat(); val y = (ay / p.lat.size).toFloat()
            if (x < 0 || x > fw || y < 0 || y > fh) continue
            val tw = labelPaint.measureText(name)
            val r = RectF(x - tw / 2, y - ts / 2, x + tw / 2, y + ts / 2)
            if (!free(r)) continue
            c.drawText(name, x, y + ts * 0.35f, haloPaint)
            c.drawText(name, x, y + ts * 0.35f, labelPaint)
        }
        labelPaint.textAlign = Paint.Align.LEFT
        haloPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawPlaces(c: Canvas, polys: List<MapStore.Poly>) {
        val maxMpp = doubleArrayOf(0.0, 80.0, 40.0, 20.0, 10.0, 8.0)
        val sizes = floatArrayOf(0f, 20f, 17f, 15f, 13f, 12f)
        labelPaint.textAlign = Paint.Align.CENTER
        haloPaint.textAlign = Paint.Align.CENTER
        for (p in polys.sortedBy { it.lanes }) {
            val rank = p.lanes
            val name = p.name ?: continue
            if (rank < 1 || rank > 5 || mpp > maxMpp[rank]) continue
            setSize(sizes[rank])
            val x = sx(p.lon[0]); val y = sy(p.lat[0])
            if (x < 0 || x > fw || y < 0 || y > fh) continue
            val tw = labelPaint.measureText(name)
            val ts = labelPaint.textSize
            val r = RectF(x - tw / 2, y - ts, x + tw / 2, y + ts / 2)
            if (!free(r)) continue
            c.drawText(name, x, y, haloPaint)
            c.drawText(name, x, y, labelPaint)
        }
        labelPaint.textAlign = Paint.Align.LEFT
        haloPaint.textAlign = Paint.Align.LEFT
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(bg)
        val w = width.toFloat()
        val h = height.toFloat()
        val cosLat = cos(Math.toRadians(centerLat))
        val cLat = centerLat * 1e7
        val cLon = centerLon * 1e7
        val kx = 1e-7 * 111320.0 * cosLat / mpp
        val ky = 1e-7 * 110574.0 / mpp
        fw = w; fh = h; fcLat = cLat; fcLon = cLon; fkx = kx; fky = ky
        placed.clear(); usedNames.clear(); signPos.clear()

        val halfLon = (w / 2 * mpp) / (111320.0 * cosLat) * 1e7
        val halfLat = (h / 2 * mpp) / 110574.0 * 1e7
        vMinLat = (cLat - halfLat - 2000).toInt(); vMaxLat = (cLat + halfLat + 2000).toInt()
        vMinLon = (cLon - halfLon - 2000).toInt(); vMaxLon = (cLon + halfLon + 2000).toInt()
        val cx0 = Math.floorDiv((cLon - halfLon).toLong(), MapStore.CELL_E7.toLong()).toInt()
        val cx1 = Math.floorDiv((cLon + halfLon).toLong(), MapStore.CELL_E7.toLong()).toInt()
        val cy0 = Math.floorDiv((cLat - halfLat).toLong(), MapStore.CELL_E7.toLong()).toInt()
        val cy1 = Math.floorDiv((cLat + halfLat).toLong(), MapStore.CELL_E7.toLong()).toInt()
        val tooWide = (cx1 - cx0 + 1) * (cy1 - cy0 + 1) > 160

        if (hasMaps && !tooWide) {
            val byType = Array(15) { ArrayList<MapStore.Poly>() }
            for (cy in cy0..cy1) for (cx in cx0..cx1) {
                val polys = getCell(cx, cy) ?: continue
                for (p in polys) {
                    if (!visible(p)) continue
                    if (p.type == MapStore.T_TRAIL && mpp > 10) continue
                    if (p.type == MapStore.T_SERVICE && mpp > 12) continue
                    if (p.type == MapStore.T_LOCAL && mpp > 25) continue
                    byType[p.type].add(p)
                }
            }
            drawLines(c, byType[MapStore.T_WATER], Color.parseColor("#3B6FA0"), maxOf(2f, (3f / mpp).toFloat()), null, w, h, cLat, cLon, kx, ky)
            // detail layer: buildings, house numbers, businesses (only when zoomed in)
            val detail = ArrayList<MapStore.Poly>()
            if (mpp < 5) {
                for (cy in cy0..cy1) for (cx in cx0..cx1) {
                    val d = getDetail(cx, cy) ?: continue
                    for (q in d) if (visible(q)) detail.add(q)
                }
            }
            if (mpp < 3) {
                fillPaint.color = Color.parseColor("#1A2340")
                paint.color = Color.parseColor("#3A4670")
                paint.strokeWidth = 1.5f
                for (p in detail) {
                    if (p.type != MapStore.T_BUILD || p.lat.size < 3) continue
                    path.reset()
                    for (i in p.lat.indices) {
                        val x = sx(p.lon[i]); val y = sy(p.lat[i])
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    c.drawPath(path, fillPaint)
                    c.drawPath(path, paint)
                }
            }
            val trailDash = DashPathEffect(floatArrayOf(14f, 10f), 0f)
            drawLines(c, byType[MapStore.T_TRAIL], Color.parseColor("#B88A5A"), 3f, trailDash, w, h, cLat, cLon, kx, ky)
            // dirt roads: two-track look
            val dirtPx = maxOf(5f, (4f / mpp).toFloat())
            drawLines(c, byType[MapStore.T_DIRT], Color.parseColor("#C8A96E"), dirtPx + 2f, null, w, h, cLat, cLon, kx, ky)
            drawLines(c, byType[MapStore.T_DIRT], Color.parseColor("#1B2A30"), dirtPx - 2f, null, w, h, cLat, cLon, kx, ky)
            // paved roads, small to big
            for (t in intArrayOf(5, 4, 3, 2, 1, 0)) {
                val px = maxOf(3f, (widthM[t] / mpp).toFloat())
                drawLines(c, byType[t], casing, px + 3f, null, w, h, cLat, cLon, kx, ky)
                drawLines(c, byType[t], roadFill[t], px, null, w, h, cLat, cLon, kx, ky)
            }
            // lane centre line when zoomed in on a road with 2+ lanes
            if (mpp < 2.5) {
                val dash = DashPathEffect(floatArrayOf(18f, 14f), 0f)
                for (t in 0..4) {
                    val list = ArrayList<MapStore.Poly>()
                    for (p in byType[t]) if (p.lanes >= 2) list.add(p)
                    drawLines(c, list, Color.parseColor("#F2B632"), 1.6f, dash, w, h, cLat, cLon, kx, ky)
                }
            }
            // stop signs
            if (mpp < 5) {
                for (p in byType[MapStore.T_STOP]) {
                    val x = (w / 2 + (p.lon[0] - cLon) * kx).toFloat()
                    val y = (h / 2 - (p.lat[0] - cLat) * ky).toFloat()
                    fillPaint.color = Color.parseColor("#D93636")
                    c.drawCircle(x, y, 9f, fillPaint)
                    paint.pathEffect = null
                    paint.color = Color.WHITE
                    paint.strokeWidth = 2f
                    c.drawCircle(x, y, 9f, paint)
                }
            }

            // labels, most important first
            val roads = ArrayList<MapStore.Poly>()
            for (t in 0..8) roads.addAll(byType[t])
            roads.sortBy { it.type }
            drawStreetNames(c, roads)
            if (mpp < 3) drawSpeedSigns(c, roads)
            if (mpp < 1.5) drawHouseNumbers(c, detail)
            if (mpp < 5) drawBusinesses(c, detail)
            drawPlaces(c, byType[MapStore.T_PLACE])
        } else if (hasMaps && tooWide) {
            c.drawText("Zoom in to see the map", w / 2, h / 2, textPaint)
        }

        if (hasFix) {
            val x = (w / 2 + (myLon * 1e7 - cLon) * kx).toFloat()
            val y = (h / 2 - (myLat * 1e7 - cLat) * ky).toFloat()
            val accPx = (myAccuracy / mpp).toFloat()
            if (accPx > 12f) {
                fillPaint.color = Color.argb(40, 77, 163, 255)
                c.drawCircle(x, y, accPx, fillPaint)
            }
            c.save()
            c.translate(x, y)
            if (myBearing >= 0f) c.rotate(myBearing)
            path.reset()
            path.moveTo(0f, -22f)
            path.lineTo(14f, 16f)
            path.lineTo(0f, 9f)
            path.lineTo(-14f, 16f)
            path.close()
            fillPaint.color = Color.parseColor("#EAF0FF")
            c.drawPath(path, fillPaint)
            fillPaint.color = Color.parseColor("#4DA3FF")
            c.drawCircle(0f, 0f, 6f, fillPaint)
            c.restore()
        }
    }

    private fun drawLines(c: Canvas, list: List<MapStore.Poly>, color: Int, width: Float, effect: DashPathEffect?,
                          w: Float, h: Float, cLat: Double, cLon: Double, kx: Double, ky: Double) {
        if (list.isEmpty()) return
        paint.color = color
        paint.strokeWidth = width
        paint.pathEffect = effect
        for (p in list) {
            path.reset()
            for (i in p.lat.indices) {
                val x = (w / 2 + (p.lon[i] - cLon) * kx).toFloat()
                val y = (h / 2 - (p.lat[i] - cLat) * ky).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            c.drawPath(path, paint)
        }
        paint.pathEffect = null
    }
}
