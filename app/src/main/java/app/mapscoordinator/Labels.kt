package app.mapscoordinator

/** Pure geometry for putting a street name along a bent line. No Android types, so it can be tested anywhere. */
object Labels {
    class Spot(
        val xs: FloatArray, val ys: FloatArray, val n: Int,
        val hOffset: Float,
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val midX: Float, val midY: Float
    )

    private fun seg(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    /**
     * px, py: the line in screen pixels. w, h: screen size. tw: text width, th: text height.
     * Returns where to draw the text, or null if the street is too short or too bent here.
     */
    fun place(px: FloatArray, py: FloatArray, count: Int, w: Float, h: Float, tw: Float, th: Float): Spot? {
        if (count < 2) return null
        // longest run of vertices that are on screen
        var bestStart = -1
        var bestEnd = -1
        var bestLen = 0f
        var i = 0
        while (i < count) {
            val inside = px[i] >= -10f && px[i] <= w + 10f && py[i] >= -10f && py[i] <= h + 10f
            if (!inside) { i++; continue }
            var j = i
            var len = 0f
            while (j + 1 < count) {
                val nx = px[j + 1]; val ny = py[j + 1]
                if (!(nx >= -10f && nx <= w + 10f && ny >= -10f && ny <= h + 10f)) break
                len += seg(px[j], py[j], nx, ny)
                j++
            }
            if (len > bestLen) { bestLen = len; bestStart = i; bestEnd = j }
            i = j + 1
        }
        if (bestStart < 0 || bestLen < tw + 8f) return null
        val n = bestEnd - bestStart + 1
        var xs = FloatArray(n) { px[bestStart + it] }
        var ys = FloatArray(n) { py[bestStart + it] }
        if (xs[n - 1] < xs[0]) { xs = xs.reversedArray(); ys = ys.reversedArray() }
        val cum = FloatArray(n)
        for (k in 1 until n) cum[k] = cum[k - 1] + seg(xs[k - 1], ys[k - 1], xs[k], ys[k])
        val total = cum[n - 1]
        // try windows from the middle outward, take the first that is not too bent
        val centre = (total - tw) / 2f
        val step = Math.max(tw / 4f, 6f)
        var tries = 0
        var off = 0f
        var chosen = -1f
        while (tries < 40) {
            val cand = if (tries == 0) centre else if (tries % 2 == 1) centre + step * ((tries + 1) / 2) else centre - step * (tries / 2)
            tries++
            if (cand < 0f || cand + tw > total) { if (step * (tries / 2 + 1) > total) break else continue }
            if (maxTurn(xs, ys, cum, n, cand, cand + tw) <= 55.0) { chosen = cand; break }
            off = cand
        }
        if (chosen < 0f) return null
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (k in 0 until n) {
            if (cum[k] >= chosen - 1f && cum[k] <= chosen + tw + 1f) {
                if (xs[k] < l) l = xs[k]; if (xs[k] > r) r = xs[k]
                if (ys[k] < t) t = ys[k]; if (ys[k] > b) b = ys[k]
            }
        }
        if (l == Float.MAX_VALUE) { l = xs[0]; r = xs[0]; t = ys[0]; b = ys[0] }
        val half = th * 0.7f
        return Spot(xs, ys, n, chosen, l - half, t - half, r + half, b + half, (l + r) / 2f, (t + b) / 2f)
    }

    private fun maxTurn(xs: FloatArray, ys: FloatArray, cum: FloatArray, n: Int, from: Float, to: Float): Double {
        var worst = 0.0
        for (k in 1 until n - 1) {
            if (cum[k] < from || cum[k] > to) continue
            val a1 = Math.atan2((ys[k] - ys[k - 1]).toDouble(), (xs[k] - xs[k - 1]).toDouble())
            val a2 = Math.atan2((ys[k + 1] - ys[k]).toDouble(), (xs[k + 1] - xs[k]).toDouble())
            var d = Math.abs(Math.toDegrees(a2 - a1))
            if (d > 180.0) d = 360.0 - d
            if (d > worst) worst = d
        }
        return worst
    }
}
