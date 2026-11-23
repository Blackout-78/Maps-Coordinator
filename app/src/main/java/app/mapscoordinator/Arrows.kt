package app.mapscoordinator

/** Where to draw lane turn arrows. Pure geometry, in screen pixels. */
object Arrows {
    const val THROUGH = 0
    const val LEFT = 1
    const val RIGHT = 2
    const val SLIGHT_LEFT = 3
    const val SLIGHT_RIGHT = 4
    const val REVERSE = 5

    /** One arrow: centre x,y, heading angle in degrees (0 = up the screen, clockwise), kind, size in px. */
    class Mark(val x: Float, val y: Float, val angle: Float, val kind: Int, val size: Float)

    fun kinds(token: String): List<Int> {
        val out = ArrayList<Int>()
        for (t in token.split(';')) when (t.trim()) {
            "through" -> out.add(THROUGH)
            "left", "sharp_left" -> out.add(LEFT)
            "right", "sharp_right" -> out.add(RIGHT)
            "slight_left", "merge_to_left" -> out.add(SLIGHT_LEFT)
            "slight_right", "merge_to_right" -> out.add(SLIGHT_RIGHT)
            "reverse" -> out.add(REVERSE)
        }
        return out
    }

    /**
     * a -> b is the direction of travel, b is the stop line. turn is the OSM string like "left|through|through;right".
     * totalLanes is the road's lane tag. When it equals the number of listed lanes, the road is one-way and the arrows
     * are centred; otherwise they sit on the right-hand side of the centre line.
     */
    fun layout(ax: Float, ay: Float, bx: Float, by: Float, turn: String, totalLanes: Int, laneW: Float): List<Mark> {
        val lanes = turn.split('|')
        val n = lanes.size
        var dx = bx - ax; var dy = by - ay
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (len < 1f || n == 0) return emptyList()
        dx /= len; dy /= len
        val rx = -dy; val ry = dx          // right of travel on a y-down screen
        val centred = totalLanes == 0 || totalLanes == n
        val angle = Math.toDegrees(Math.atan2(dx.toDouble(), -dy.toDouble())).toFloat()
        val size = Math.min(laneW * 0.9f, 60f)
        val back = size * 1.2f             // sit just behind the stop line
        val cx = bx - dx * back; val cy = by - dy * back
        val out = ArrayList<Mark>()
        for (i in 0 until n) {
            val off = if (centred) (i + 0.5f - n / 2f) * laneW else (i + 0.5f) * laneW
            for (k in kinds(lanes[i])) out.add(Mark(cx + rx * off, cy + ry * off, angle, k, size))
        }
        return out
    }
}
