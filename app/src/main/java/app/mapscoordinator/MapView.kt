package app.mapscoordinator

import android.content.Context
import android.graphics.Bitmap
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
    var showStops = false
    var showLanes = true
    fun redraw() { mapDirty = true; invalidate() }
    private val lanePathY = Path()
    private val lanePathW = Path()
    var hasMark = false
    var markLat = 0.0
    var markLon = 0.0
    fun setMark(lat: Double, lon: Double) { markLat = lat; markLon = lon; hasMark = true; invalidate() }
    fun clearMark() { hasMark = false; invalidate() }
    var hasFix = false
    var myLat = 0.0
    var myLon = 0.0
    var myBearing = -1f
    var myAccuracy = 0f
    var onUserPan: (() -> Unit)? = null
    var hasMaps = false
        set(v) { field = v; mapDirty = true; if (!v) bmp = null; invalidate() }
    private var interacting = false

    private val cache = object : LinkedHashMap<Long, List<MapStore.Poly>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<MapStore.Poly>>?): Boolean = size > 120
    }
    private val loading = HashSet<Long>()
    private val dcache = object : LinkedHashMap<Long, List<MapStore.Poly>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<MapStore.Poly>>?): Boolean = size > 48
    }
    private val dloading = HashSet<Long>()
    @Volatile private var overview: List<MapStore.Poly>? = null
    @Volatile private var ovLoading = false
    @Volatile private var rMpp = 4.0
    private var bmp: Bitmap? = null
    private var bmpLat = 0.0
    private var bmpLon = 0.0
    private var bmpMpp = 1.0
    @Volatile private var rendering = false
    @Volatile private var mapDirty = true
    private val renderExec = Executors.newSingleThreadExecutor()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val gpsPath = Path()
    private val gpsFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dirtyRun = Runnable { mapDirty = true; invalidate() }
    private fun postDirty() { removeCallbacks(dirtyRun); postDelayed(dirtyRun, 150) }
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
            mpp = (mpp / d.scaleFactor).coerceIn(0.3, 600.0)
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> interacting = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { interacting = false; invalidate() }
        }
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    fun zoomBy(f: Double) {
        mpp = (mpp / f).coerceIn(0.3, 600.0)
        invalidate()
    }

    fun clearCache() {
        synchronized(cache) { cache.clear() }
        loading.clear()
        synchronized(dcache) { dcache.clear() }
        dloading.clear()
        overview = null
        ovLoading = false
        bmp = null
        mapDirty = true
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
                    val polys = try { MapStore.loadCell(appCtx, cx, cy) } catch (t: Throwable) {
                        synchronized(cache) { cache.clear() }
                        emptyList<MapStore.Poly>()
                    }
                    synchronized(cache) { cache[key] = polys; loading.remove(key) }
                    postDirty()
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
                    val polys = try { MapStore.loadDetail(appCtx, cx, cy) } catch (t: Throwable) {
                        synchronized(dcache) { dcache.clear() }
                        emptyList<MapStore.Poly>()
                    }
                    synchronized(dcache) { dcache[key] = polys; dloading.remove(key) }
                    postDirty()
                }
            }
        }
        return null
    }

    private fun getOverview(): List<MapStore.Poly>? {
        val o = overview
        if (o != null) return o
        if (!ovLoading) {
            ovLoading = true
            val appCtx = context.applicationContext
            exec.execute {
                val list = try {
                    if (!java.io.File(MapStore.dir(appCtx), "overview.t").exists() && MapStore.hasAny(appCtx)) MapStore.buildOverview(appCtx)
                    MapStore.loadOverview(appCtx)
                } catch (t: Throwable) { emptyList<MapStore.Poly>() }
                overview = list
                postDirty()
            }
        }
        return null
    }

    private fun drawOverview(c: Canvas) {
        val ov = getOverview()
        if (ov == null) {
            c.drawText("Preparing the zoomed-out view (one time)…", fw / 2, fh / 2, textPaint)
            return
        }
        if (ov.isEmpty()) {
            c.drawText("No major roads in this map", fw / 2, fh / 2, textPaint)
            return
        }
        val byType = Array(4) { ArrayList<MapStore.Poly>() }
        val places = ArrayList<MapStore.Poly>()
        for (p in ov) {
            if (!visible(p)) continue
            if (p.type <= 3) {
                if (p.type == 3 && rMpp > 60) continue
                if (p.type == 2 && rMpp > 160) continue
                byType[p.type].add(p)
            } else if (p.type == MapStore.T_PLACE) places.add(p)
        }
        val wd = floatArrayOf(3.5f, 2.6f, 2f, 1.4f)
        for (t in intArrayOf(3, 2, 1, 0)) drawLines(c, byType[t], roadFill[t], wd[t] * dens, null)
        placed.clear()
        drawPlaces(c, places)
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
            if (placed.size > 400) break
            val name = p.name ?: continue
            if (p.type > MapStore.T_TRAIL || rMpp > maxMppForName(p.type)) continue
            val n = p.lat.size
            if (n < 2) continue
            val tw = labelPaint.measureText(name)
            val px = FloatArray(n) { sx(p.lon[it]) }
            val py = FloatArray(n) { sy(p.lat[it]) }
            val spot = Labels.place(px, py, n, fw, fh, tw, ts) ?: continue
            var dup = false
            for (u in usedNames) if (u.first == name && hypot(u.second - spot.midX, u.third - spot.midY) < 260f) { dup = true; break }
            if (dup) continue
            if (!free(RectF(spot.left, spot.top, spot.right, spot.bottom))) continue
            usedNames.add(Triple(name, spot.midX, spot.midY))
            path.reset()
            path.moveTo(spot.xs[0], spot.ys[0])
            for (k in 1 until spot.n) path.lineTo(spot.xs[k], spot.ys[k])
            c.drawTextOnPath(name, path, spot.hOffset, ts * 0.35f, haloPaint)
            c.drawTextOnPath(name, path, spot.hOffset, ts * 0.35f, labelPaint)
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
        val maxMpp = doubleArrayOf(0.0, 700.0, 300.0, 120.0, 40.0, 25.0)
        val sizes = floatArrayOf(0f, 20f, 17f, 15f, 13f, 12f)
        labelPaint.textAlign = Paint.Align.CENTER
        haloPaint.textAlign = Paint.Align.CENTER
        for (p in polys.sortedBy { it.lanes }) {
            val rank = p.lanes
            val name = p.name ?: continue
            if (rank < 1 || rank > 5 || rMpp > maxMpp[rank]) continue
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

    private fun renderMap(c: Canvas, w: Float, h: Float, cenLat: Double, cenLon: Double, m: Double) {
        rMpp = m
        c.drawColor(bg)
        val cosLat = cos(Math.toRadians(cenLat))
        val cLat = cenLat * 1e7
        val cLon = cenLon * 1e7
        val kx = 1e-7 * 111320.0 * cosLat / rMpp
        val ky = 1e-7 * 110574.0 / rMpp
        fw = w; fh = h; fcLat = cLat; fcLon = cLon; fkx = kx; fky = ky
        placed.clear(); usedNames.clear(); signPos.clear()

        val halfLon = (w / 2 * rMpp) / (111320.0 * cosLat) * 1e7
        val halfLat = (h / 2 * rMpp) / 110574.0 * 1e7
        vMinLat = (cLat - halfLat - 2000).toInt(); vMaxLat = (cLat + halfLat + 2000).toInt()
        vMinLon = (cLon - halfLon - 2000).toInt(); vMaxLon = (cLon + halfLon + 2000).toInt()
        val cx0 = Math.floorDiv((cLon - halfLon).toLong(), MapStore.CELL_E7.toLong()).toInt()
        val cx1 = Math.floorDiv((cLon + halfLon).toLong(), MapStore.CELL_E7.toLong()).toInt()
        val cy0 = Math.floorDiv((cLat - halfLat).toLong(), MapStore.CELL_E7.toLong()).toInt()
        val cy1 = Math.floorDiv((cLat + halfLat).toLong(), MapStore.CELL_E7.toLong()).toInt()
        val tooWide = (cx1 - cx0 + 1).toLong() * (cy1 - cy0 + 1) > 100

        if (hasMaps && !tooWide) {
            val byType = Array(15) { ArrayList<MapStore.Poly>() }
            for (cy in cy0..cy1) for (cx in cx0..cx1) {
                val polys = getCell(cx, cy) ?: continue
                for (p in polys) {
                    if (!visible(p)) continue
                    if (p.type == MapStore.T_TRAIL && rMpp > 10) continue
                    if (p.type == MapStore.T_SERVICE && rMpp > 12) continue
                    if (p.type == MapStore.T_LOCAL && rMpp > 25) continue
                    byType[p.type].add(p)
                }
            }
            drawLines(c, byType[MapStore.T_WATER], Color.parseColor("#3B6FA0"), maxOf(2f, (3f / rMpp).toFloat()), null, w, h, cLat, cLon, kx, ky)
            // detail layer: buildings, house numbers, businesses (only when zoomed in)
            val detail = ArrayList<MapStore.Poly>()
            if (rMpp < 5) {
                for (cy in cy0..cy1) for (cx in cx0..cx1) {
                    val d = getDetail(cx, cy) ?: continue
                    for (q in d) if (visible(q)) detail.add(q)
                }
            }
            if (rMpp < 3) {
                fillPaint.color = Color.parseColor("#1A2340")
                paint.color = Color.parseColor("#3A4670")
                paint.strokeWidth = 1.5f
                var nb = 0
                path.reset()
                for (p in detail) {
                    if (p.type != MapStore.T_BUILD || p.lat.size < 3) continue
                    for (i in p.lat.indices) {
                        val x = sx(p.lon[i]); val y = sy(p.lat[i])
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    if (++nb >= 250) {
                        c.drawPath(path, fillPaint)
                        c.drawPath(path, paint)
                        path.reset()
                        nb = 0
                    }
                }
                if (nb > 0) {
                    c.drawPath(path, fillPaint)
                    c.drawPath(path, paint)
                }
                path.reset()
            }
            val trailDash = DashPathEffect(floatArrayOf(14f, 10f), 0f)
            drawLines(c, byType[MapStore.T_TRAIL], Color.parseColor("#B88A5A"), 3f, trailDash, w, h, cLat, cLon, kx, ky)
            // dirt roads: two-track look
            val dirtPx = maxOf(5f, (4f / rMpp).toFloat())
            drawLines(c, byType[MapStore.T_DIRT], Color.parseColor("#C8A96E"), dirtPx + 2f, null, w, h, cLat, cLon, kx, ky)
            drawLines(c, byType[MapStore.T_DIRT], Color.parseColor("#1B2A30"), dirtPx - 2f, null, w, h, cLat, cLon, kx, ky)
            // paved roads, small to big
            for (t in intArrayOf(5, 4, 3, 2, 1, 0)) {
                val px = maxOf(3f, (widthM[t] / rMpp).toFloat())
                drawLines(c, byType[t], casing, px + 3f, null, w, h, cLat, cLon, kx, ky)
                drawLines(c, byType[t], roadFill[t], px, null, w, h, cLat, cLon, kx, ky)
            }
            // lane lines: yellow double centre line on even lane counts, white dashes between same-way lanes
            if (showLanes && rMpp < 3) {
                for (t in 0..4) drawLaneLines(c, byType[t], (widthM[t] / rMpp).toFloat(), w, h, cLat, cLon, kx, ky)
            }
            // stop signs
            if (showStops && rMpp < 5) {
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
            run {
                drawStreetNames(c, roads)
                if (rMpp < 3) drawSpeedSigns(c, roads)
                if (rMpp < 2.5) drawHouseNumbers(c, detail)
                if (rMpp < 5) drawBusinesses(c, detail)
            }
            drawPlaces(c, byType[MapStore.T_PLACE])
        } else if (hasMaps && tooWide) {
            drawOverview(c)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        bmp = null
        mapDirty = true
    }

    private fun requestRender() {
        if (rendering || width == 0 || height == 0) return
        rendering = true
        mapDirty = false
        val bw = (width * 1.6f).toInt()
        val bh = (height * 1.6f).toInt()
        val lat = centerLat
        val lon = centerLon
        val m = mpp
        renderExec.execute {
            var out: Bitmap? = null
            try {
                out = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565)
                renderMap(Canvas(out), bw.toFloat(), bh.toFloat(), lat, lon, m)
            } catch (t: Throwable) {
                out = null
            }
            val result = out
            val done = Runnable {
                val old = bmp
                if (result != null) {
                    bmp = result; bmpLat = lat; bmpLon = lon; bmpMpp = m
                    old?.recycle()
                }
                rendering = false
                invalidate()
            }
            if (result != null) post(done) else postDelayed(done, 1500)
        }
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

        if (hasMaps) {
            val b = bmp
            var needs = b == null || mapDirty
            if (b != null) {
                val s = (bmpMpp / mpp).toFloat()
                val cx = w / 2 + ((bmpLon - centerLon) * 111320.0 * cosLat / mpp).toFloat()
                val cy = h / 2 + ((centerLat - bmpLat) * 110574.0 / mpp).toFloat()
                val hw = b.width / 2f * s
                val hh = b.height / 2f * s
                c.save()
                c.translate(cx, cy)
                c.scale(s, s)
                c.drawBitmap(b, -b.width / 2f, -b.height / 2f, bmpPaint)
                c.restore()
                if (!scaler.isInProgress) {
                    val mx = w * 0.05f
                    val my = h * 0.05f
                    if (cx - hw > -mx || cx + hw < w + mx || cy - hh > -my || cy + hh < h + my) needs = true
                    if (abs(s - 1f) > 0.002f) needs = true
                }
            }
            if (needs) requestRender()
        }

        if (hasMark) {
            val mx = (w / 2 + (markLon * 1e7 - cLon) * kx).toFloat()
            val my = (h / 2 - (markLat * 1e7 - cLat) * ky).toFloat()
            gpsFill.color = Color.parseColor("#F2B632")
            c.drawCircle(mx, my, 11f, gpsFill)
            gpsFill.color = Color.parseColor("#0E1424")
            c.drawCircle(mx, my, 4.5f, gpsFill)
        }

        if (hasFix) {
            val x = (w / 2 + (myLon * 1e7 - cLon) * kx).toFloat()
            val y = (h / 2 - (myLat * 1e7 - cLat) * ky).toFloat()
            val accPx = (myAccuracy / mpp).toFloat()
            if (accPx > 12f) {
                gpsFill.color = Color.argb(40, 77, 163, 255)
                c.drawCircle(x, y, accPx, gpsFill)
            }
            c.save()
            c.translate(x, y)
            if (myBearing >= 0f) c.rotate(myBearing)
            gpsPath.reset()
            gpsPath.moveTo(0f, -22f)
            gpsPath.lineTo(14f, 16f)
            gpsPath.lineTo(0f, 9f)
            gpsPath.lineTo(-14f, 16f)
            gpsPath.close()
            gpsFill.color = Color.parseColor("#EAF0FF")
            c.drawPath(gpsPath, gpsFill)
            gpsFill.color = Color.parseColor("#4DA3FF")
            c.drawCircle(0f, 0f, 6f, gpsFill)
            c.restore()
        }
    }

    private fun drawLaneLines(c: Canvas, list: List<MapStore.Poly>, roadPx: Float, w: Float, h: Float,
                              cLat: Double, cLon: Double, kx: Double, ky: Double) {
        if (list.isEmpty() || roadPx < 9f) return
        val dash = DashPathEffect(floatArrayOf(16f, 18f), 0f)
        lanePathY.reset(); lanePathW.reset()
        var any = false
        for (p in list) {
            val n = p.lat.size
            val lanes = p.lanes
            if (lanes < 2 || n < 2) continue
            val laneW = roadPx / lanes
            if (laneW < 4.5f) continue
            val xs = FloatArray(n); val ys = FloatArray(n)
            for (i in 0 until n) {
                xs[i] = (w / 2 + (p.lon[i] - cLon) * kx).toFloat()
                ys[i] = (h / 2 - (p.lat[i] - cLat) * ky).toFloat()
            }
            // unit normals per vertex (average of the two neighbouring segments)
            val nx = FloatArray(n); val ny = FloatArray(n)
            for (i in 0 until n) {
                var ax = 0f; var ay = 0f
                if (i > 0) { val dx = xs[i] - xs[i - 1]; val dy = ys[i] - ys[i - 1]; val l = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat(); if (l > 0f) { ax += -dy / l; ay += dx / l } }
                if (i < n - 1) { val dx = xs[i + 1] - xs[i]; val dy = ys[i + 1] - ys[i]; val l = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat(); if (l > 0f) { ax += -dy / l; ay += dx / l } }
                val l = Math.hypot(ax.toDouble(), ay.toDouble()).toFloat()
                if (l < 0.3f) { nx[i] = 0f; ny[i] = 0f } else { nx[i] = ax / l; ny[i] = ay / l }
            }
            val even = lanes % 2 == 0
            for (k in 1 until lanes) {
                val off = (k - lanes / 2.0).toFloat() * laneW
                val yellow = even && k == lanes / 2
                val offs = if (yellow) floatArrayOf(-1.6f, 1.6f) else floatArrayOf(off)
                for (o in offs) {
                    val pa = if (yellow) lanePathY else lanePathW
                    for (i in 0 until n) {
                        val x = xs[i] + nx[i] * o; val y = ys[i] + ny[i] * o
                        if (i == 0) pa.moveTo(x, y) else pa.lineTo(x, y)
                    }
                    any = true
                }
            }
        }
        if (!any) return
        paint.strokeWidth = 1.4f
        paint.pathEffect = null
        paint.color = Color.parseColor("#F2B632")
        c.drawPath(lanePathY, paint)
        paint.pathEffect = dash
        paint.color = Color.parseColor("#C9D4F0")
        c.drawPath(lanePathW, paint)
        paint.pathEffect = null
        lanePathY.reset(); lanePathW.reset()
    }

    private fun drawLines(c: Canvas, list: List<MapStore.Poly>, color: Int, width: Float, effect: DashPathEffect?,
                          w: Float = fw, h: Float = fh, cLat: Double = fcLat, cLon: Double = fcLon,
                          kx: Double = fkx, ky: Double = fky) {
        if (list.isEmpty()) return
        paint.color = color
        paint.strokeWidth = width
        paint.pathEffect = effect
        var inPath = 0
        path.reset()
        for (p in list) {
            for (i in p.lat.indices) {
                val x = (w / 2 + (p.lon[i] - cLon) * kx).toFloat()
                val y = (h / 2 - (p.lat[i] - cLat) * ky).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (++inPath >= 300) {
                c.drawPath(path, paint)
                path.reset()
                inPath = 0
            }
        }
        if (inPath > 0) c.drawPath(path, paint)
        paint.pathEffect = null
    }
}
