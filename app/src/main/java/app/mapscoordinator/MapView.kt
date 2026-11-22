package app.mapscoordinator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.cos

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
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<MapStore.Poly>>?): Boolean = size > 300
    }
    private val loading = HashSet<Long>()
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

    override fun onDraw(c: Canvas) {
        c.drawColor(bg)
        val w = width.toFloat()
        val h = height.toFloat()
        val cosLat = cos(Math.toRadians(centerLat))
        val cLat = centerLat * 1e7
        val cLon = centerLon * 1e7
        val kx = 1e-7 * 111320.0 * cosLat / mpp
        val ky = 1e-7 * 110574.0 / mpp

        val halfLon = (w / 2 * mpp) / (111320.0 * cosLat) * 1e7
        val halfLat = (h / 2 * mpp) / 110574.0 * 1e7
        val cx0 = Math.floorDiv((cLon - halfLon).toLong(), MapStore.CELL_E7.toLong()).toInt()
        val cx1 = Math.floorDiv((cLon + halfLon).toLong(), MapStore.CELL_E7.toLong()).toInt()
        val cy0 = Math.floorDiv((cLat - halfLat).toLong(), MapStore.CELL_E7.toLong()).toInt()
        val cy1 = Math.floorDiv((cLat + halfLat).toLong(), MapStore.CELL_E7.toLong()).toInt()
        val tooWide = (cx1 - cx0 + 1) * (cy1 - cy0 + 1) > 160

        if (hasMaps && !tooWide) {
            val byType = Array(11) { ArrayList<MapStore.Poly>() }
            for (cy in cy0..cy1) for (cx in cx0..cx1) {
                val polys = getCell(cx, cy) ?: continue
                for (p in polys) {
                    if (p.type == MapStore.T_TRAIL && mpp > 10) continue
                    if (p.type == MapStore.T_SERVICE && mpp > 12) continue
                    if (p.type == MapStore.T_LOCAL && mpp > 25) continue
                    byType[p.type].add(p)
                }
            }
            drawLines(c, byType[MapStore.T_WATER], Color.parseColor("#3B6FA0"), maxOf(2f, (3f / mpp).toFloat()), null, w, h, cLat, cLon, kx, ky)
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
