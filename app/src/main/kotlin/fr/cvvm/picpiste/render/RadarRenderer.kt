package fr.cvvm.picpiste.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import fr.cvvm.picpiste.R
import fr.cvvm.picpiste.core.Snapshot
import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.Runway
import fr.cvvm.picpiste.core.airfield.RunwayEnd
import fr.cvvm.picpiste.core.aprs.AircraftType
import fr.cvvm.picpiste.core.cart.formatEta
import fr.cvvm.picpiste.core.geo.Polygon
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.geo.angleDiffDeg
import fr.cvvm.picpiste.core.traffic.AircraftState
import fr.cvvm.picpiste.core.traffic.FlightStatus
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.atan2
import kotlin.math.min

/**
 * Draws the fixed, runway-aligned radar on any Android canvas: the phone screen
 * (through Compose) and the Android Auto surface use the same code.
 */
/** [context] gives the texts drawn on the map, in the phone language. */
class RadarRenderer(private val context: Context) {
    /** Colours to draw with: the phone's theme, or the car's day / night. */
    var colors: Colors = Colors.DARK

    private companion object {
        const val STARTER_LENGTH_M = 30.0
        const val STARTER_WIDTH_M = 14.0
    }

    data class Hit(val address: String, val x: Float, val y: Float)

    /** Screen positions of the aircraft drawn last, for tap handling on the phone. */
    var hits: List<Hit> = emptyList()
        private set

    /** The airfield of the frame being drawn (it changes when the user picks another one). */
    private var airfield: Airfield? = null
    private var fieldCentre = Vec2(0.0, 0.0)
    private var runwayLabels: List<Pair<Vec2, String>> = emptyList()
    private var labelsForScale = -1f

    // Map orientation: the local frame turned by the airfield's setting (north up by default).
    private var rotCos = 1.0
    private var rotSin = 0.0

    private fun use(a: Airfield) {
        if (a === airfield) return
        airfield = a
        fieldCentre = a.centre
        labelsForScale = -1f
        val r = Math.toRadians(a.mapRotationDeg)
        rotCos = cos(r)
        rotSin = sin(r)
    }

    /** A local vector as drawn (y up), turned to the map orientation. */
    private fun turned(v: Vec2) = Vec2(v.x * rotCos - v.y * rotSin, v.x * rotSin + v.y * rotCos)

    private val condensedBold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private val sansBold = Typeface.create("sans-serif", Typeface.BOLD)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    // Screen mapping for the frame being drawn.
    private var cx = 0f
    private var cy = 0f
    private var scale = 1f
    private val view = RectF()

    private fun sx(v: Vec2) = cx + (turned(v - fieldCentre).x * scale).toFloat()
    private fun sy(v: Vec2) = cy - (turned(v - fieldCentre).y * scale).toFloat()

    /**
     * @param radiusM metres from the centre to the nearest edge.
     * @param dp pixels per design unit (screen density on the phone, about 1 on a car screen).
     */
    fun draw(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        snap: Snapshot,
        radiusM: Double,
        dp: Float,
        selected: String? = null,
        showNorth: Boolean = true,
        showScale: Boolean = true,
    ) {
        canvas.save()
        canvas.clipRect(left, top, left + width, top + height)
        fill.color = colors.MAP_BG
        canvas.drawRect(left, top, left + width, top + height, fill)

        use(snap.airfield)
        cx = left + width / 2
        cy = top + height / 2
        scale = (min(width, height) / 2 / radiusM).toFloat()
        view.set(left, top, left + width, top + height)

        drawRings(canvas, radiusM, dp)
        drawAirfield(canvas, snap, dp)
        drawTraffic(canvas, snap, dp, selected)
        drawCart(canvas, snap, dp)
        if (showNorth) drawNorth(canvas, left + 16 * dp, top + 16 * dp, dp)
        if (showScale) drawScale(canvas, left + 16 * dp, top + height - 16 * dp, width, dp)
        canvas.restore()
    }

    private fun drawRings(canvas: Canvas, radiusM: Double, dp: Float) {
        stroke.color = colors.RING
        stroke.strokeWidth = 1 * dp
        stroke.pathEffect = null
        text.typeface = sans
        text.textSize = 11 * dp
        text.color = colors.FAINT
        text.textAlign = Paint.Align.LEFT
        for (r in listOf(radiusM / 2, radiusM)) {
            val rp = (r * scale).toFloat()
            canvas.drawCircle(cx, cy, rp, stroke)
            canvas.drawText(if (r >= 1000) "${trim(r / 1000)} km" else "${r.toInt()} m", cx + 4 * dp, cy - rp + 13 * dp, text)
        }
    }

    private fun polyPath(poly: Polygon): Path {
        path.reset()
        poly.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(sx(p), sy(p)) else path.lineTo(sx(p), sy(p)) }
        path.close()
        return path
    }

    private fun drawAirfield(canvas: Canvas, snap: Snapshot, dp: Float) {
        val airfield = snap.airfield
        // Boundary
        fill.color = colors.BOUNDARY_FILL
        canvas.drawPath(polyPath(airfield.boundary), fill)
        stroke.color = colors.BOUNDARY_STROKE
        stroke.strokeWidth = 1 * dp
        stroke.pathEffect = DashPathEffect(floatArrayOf(5 * dp, 4 * dp), 0f)
        canvas.drawPath(path, stroke)
        stroke.pathEffect = null

        // Aprons, then taxiways on top
        fill.color = colors.APRON
        airfield.aprons.forEach { canvas.drawPath(polyPath(it), fill) }
        stroke.color = colors.TAXIWAY
        for (t in airfield.taxiways) {
            stroke.strokeWidth = maxOf((t.widthM * scale).toFloat(), 2 * dp)
            path.reset()
            t.path.forEachIndexed { i, p -> if (i == 0) path.moveTo(sx(p), sy(p)) else path.lineTo(sx(p), sy(p)) }
            canvas.drawPath(path, stroke)
        }

        // Runways
        for (rw in airfield.runways) {
            fill.color = if (rw.isGrass) colors.GRASS else colors.PAVED
            canvas.drawPath(polyPath(rw.outline), fill)
            if (rw.isGrass) {
                stroke.color = colors.GRASS_EDGE
                stroke.strokeWidth = 1 * dp
                canvas.drawPath(path, stroke)
            }
        }

        // Hangars and windsock
        for (h in airfield.hangars) {
            fill.color = colors.HANGAR
            canvas.drawPath(polyPath(h), fill)
            stroke.color = colors.HANGAR_EDGE
            stroke.strokeWidth = 1 * dp
            canvas.drawPath(path, stroke)
        }
        fill.color = colors.WINDSOCK
        airfield.windsocks.forEach { canvas.drawCircle(sx(it), sy(it), 3.5f * dp, fill) }

        // Runway numbers, when the runways are big enough to label
        val rwPx = airfield.runways.maxOf { it.length } * scale
        if (rwPx > 90 * dp) {
            text.typeface = condensedBold
            text.textSize = 13 * dp
            text.color = colors.MUTED
            text.textAlign = Paint.Align.CENTER
            if (labelsForScale != scale) {
                runwayLabels = runwayLabels(airfield, (14 * dp / scale).toDouble())
                labelsForScale = scale
            }
            for ((p, label) in runwayLabels) canvas.drawText(label, sx(p), sy(p) + 4.5f * dp, text)
        }

        airfield.starter?.let { drawStarter(canvas, it) }
    }

    /**
     * Parallel runways share their numbers: one "14" and one "32" beside the whole group,
     * direction only (no L/R). Crossing runways each get their own pair.
     */
    private fun runwayLabels(airfield: Airfield, offM: Double): List<Pair<Vec2, String>> {
        val groups = mutableListOf<MutableList<Runway>>()
        for (rw in airfield.runways) {
            groups.firstOrNull { angleDiffDeg(it[0].headingA, rw.headingA) < 15 }?.add(rw)
                ?: groups.add(mutableListOf(rw))
        }
        return groups.flatMap { g ->
            val dir = g[0].dirA
            val normal = Vec2(-dir.y, dir.x)
            val mid = g.map { (it.thresholdA + it.thresholdB) * 0.5 }.reduce { a, b -> a + b } * (1.0 / g.size)
            val across = mid dot normal
            fun onAxis(p: Vec2) = dir * (p dot dir) + normal * across
            val a = onAxis(g.minBy { it.thresholdA dot dir }.thresholdA) - dir * offM
            val b = onAxis(g.maxBy { it.thresholdB dot dir }.thresholdB) + dir * offM
            listOf(
                a to g[0].direction(RunwayEnd.A),
                b to g[0].direction(RunwayEnd.B),
            )
        }
    }

    /** The starter: a plain box like the hangars, aligned with the main runway. */
    private fun drawStarter(canvas: Canvas, p: Vec2) {
        val halfLong = maxOf((STARTER_LENGTH_M / 2 * scale).toFloat(), 3f)
        val halfWide = maxOf((STARTER_WIDTH_M / 2 * scale).toFloat(), 2f)
        rect.set(sx(p) - halfLong, sy(p) - halfWide, sx(p) + halfLong, sy(p) + halfWide)
        canvas.save()
        // The local x axis (main runway) is turned by the map orientation; screen y points down.
        canvas.rotate(-Math.toDegrees(atan2(rotSin, rotCos)).toFloat(), sx(p), sy(p))
        fill.color = colors.HANGAR
        canvas.drawRect(rect, fill)
        stroke.color = colors.HANGAR_EDGE
        stroke.strokeWidth = 1f
        canvas.drawRect(rect, stroke)
        canvas.restore()
    }

    private fun drawTraffic(canvas: Canvas, snap: Snapshot, dp: Float, selected: String?) {
        val order = snap.aircraft.sortedBy {
            when {
                it.signalLost -> 0
                !it.status.isAirborne && it.status != FlightStatus.TAKEOFF -> 1
                it.status == FlightStatus.FINAL -> 3
                else -> 2
            }
        }
        val newHits = ArrayList<Hit>(order.size)
        for (a in order) {
            val x = sx(a.fix.pos)
            val y = sy(a.fix.pos)
            val color = colors.of(a)

            // Trail of the last 2 minutes
            if (a.status.isAirborne && !a.signalLost) {
                fill.color = color
                val n = a.trail.size
                a.trail.forEachIndexed { i, p ->
                    if (i % 2 == 0 && i < n - 1) {
                        fill.alpha = (40 + 140 * i / n.coerceAtLeast(1))
                        canvas.drawCircle(sx(p), sy(p), 1.6f * dp, fill)
                    }
                }
                fill.alpha = 255
            }

            if (!view.contains(x, y)) {
                newHits += drawOffScreen(canvas, a, x, y, color, dp, selected == a.address)
                continue
            }
            if (selected == a.address) {
                stroke.color = colors.TEXT
                stroke.strokeWidth = 2.5f * dp
                canvas.drawCircle(x, y, 18 * dp, stroke)
            }
            drawIcon(canvas, a, x, y, color, dp)
            drawTag(canvas, a, x + 14 * dp, y, color, dp, selected == a.address)
            newHits += Hit(a.address, x, y)
        }
        hits = newHits
    }

    /**
     * Zoomed in, an aircraft outside the view: an arrow on the edge pointing to it, with its tag
     * next to it. Returns where it can be tapped.
     */
    private fun drawOffScreen(canvas: Canvas, a: AircraftState, x: Float, y: Float, color: Int, dp: Float, selected: Boolean): Hit {
        val size = (if (selected) 14 else 11) * dp
        val inset = size + 6 * dp
        val dx = x - cx
        val dy = y - cy
        val len = kotlin.math.hypot(dx, dy)
        val ux = dx / len
        val uy = dy / len
        // Where the line from the centre to the aircraft meets the (inset) edge.
        val t = min(
            if (dx != 0f) (view.width() / 2 - inset) / kotlin.math.abs(dx) else Float.MAX_VALUE,
            if (dy != 0f) (view.height() / 2 - inset) / kotlin.math.abs(dy) else Float.MAX_VALUE,
        )
        val ex = cx + dx * t
        val ey = cy + dy * t

        path.reset()
        path.moveTo(ex + ux * size, ey + uy * size)
        path.lineTo(ex - ux * size * 0.6f - uy * size * 0.8f, ey - uy * size * 0.6f + ux * size * 0.8f)
        path.lineTo(ex - ux * size * 0.6f + uy * size * 0.8f, ey - uy * size * 0.6f - ux * size * 0.8f)
        path.close()
        fill.color = color
        if (a.signalLost) fill.alpha = 200
        canvas.drawPath(path, fill)
        fill.alpha = 255
        stroke.color = colors.BG
        stroke.strokeWidth = 1.5f * dp
        canvas.drawPath(path, stroke)

        // Tag just inside the arrow, kept within the view.
        val w = tagWidth(a, dp)
        val h = 19 * dp
        val px = ex - ux * (size + 6 * dp + w / 2)
        val py = ey - uy * (size + 6 * dp + h / 2)
        val tx = (px - w / 2).coerceIn(view.left + 4 * dp, maxOf(view.left + 4 * dp, view.right - 4 * dp - w))
        val ty = py.coerceIn(view.top + 4 * dp + h / 2, maxOf(view.top + 4 * dp + h / 2, view.bottom - 4 * dp - h / 2))
        drawTag(canvas, a, tx, ty, color, dp, selected)
        return Hit(a.address, ex, ey)
    }

    private fun tagSub(a: AircraftState): String = when {
        a.signalLost -> context.getString(R.string.signal_lost_ago, (a.ageMs / 1000).toInt())
        !a.status.isAirborne && a.status != FlightStatus.TAKEOFF -> ""
        a.status == FlightStatus.FINAL && a.etaS != null -> "${a.fix.aglM.coerceAtLeast(0.0).toInt()} m · ${formatEta(a.etaS!!)}"
        else -> "${a.fix.aglM.coerceAtLeast(0.0).toInt()} m"
    }

    private fun tagWidth(a: AircraftState, dp: Float): Float {
        text.textSize = 14 * dp
        text.typeface = condensedBold
        val wMain = text.measureText(a.identity.label)
        text.typeface = condensedRegular
        val sub = tagSub(a)
        val wSub = if (sub.isEmpty()) 0f else text.measureText(" $sub")
        return wMain + wSub + 10 * dp
    }

    /** Low-wing single-engine plane seen from above, nose up, in a 40 × 40 box (propeller drawn apart). */
    private val planePath = Path().apply {
        val right = floatArrayOf(
            20f, 4.2f, 21.3f, 4.6f, 22.2f, 6.2f, 22.6f, 11.6f, // cowling
            34.6f, 12.6f, 35.8f, 13.6f, 36f, 16.6f, 35.2f, 17.6f, 22.6f, 18.6f, // wing
            21.5f, 28.4f, 27.4f, 29.4f, 28f, 30.6f, 27.4f, 32.2f, 21.2f, 32.6f, 20f, 32.6f, // tail
        )
        moveTo(right[0], right[1])
        for (i in 2 until right.size step 2) lineTo(right[i], right[i + 1])
        for (i in right.size - 4 downTo 2 step 2) lineTo(40f - right[i], right[i + 1]) // mirrored left half
        close()
    }

    private fun drawIcon(canvas: Canvas, a: AircraftState, x: Float, y: Float, color: Int, dp: Float) {
        val size = (if (a.status.isAirborne || a.status == FlightStatus.TAKEOFF) 26 else 22) * dp
        val angle = a.fix.trackDeg?.let { track ->
            val d = turned(airfield!!.frame.directionOf(track))
            Math.toDegrees(atan2(d.x, d.y)).toFloat() // screen y is flipped: up = +y local
        } ?: 0f
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(angle)
        val k = size / 40f
        canvas.scale(k, k)
        canvas.translate(-20f, -20f)
        fill.color = color
        if (a.signalLost) fill.alpha = 200
        when (a.identity.type) {
            AircraftType.GLIDER, AircraftType.HANG_GLIDER, AircraftType.UNKNOWN -> {
                rect.set(2f, 13f, 38f, 16.5f); canvas.drawRoundRect(rect, 1.5f, 1.5f, fill)
                rect.set(18.5f, 5f, 21.5f, 35f); canvas.drawRoundRect(rect, 1.5f, 1.5f, fill)
                rect.set(13f, 31f, 27f, 33.5f); canvas.drawRoundRect(rect, 1f, 1f, fill)
            }
            AircraftType.TOW_PLANE, AircraftType.POWERED, AircraftType.DROP_PLANE, AircraftType.JET -> {
                canvas.drawPath(planePath, fill)
                rect.set(13.5f, 2.4f, 26.5f, 3.9f); canvas.drawRoundRect(rect, 0.75f, 0.75f, fill)
            }
            else -> {
                path.reset()
                path.moveTo(20f, 6f); path.lineTo(36f, 30f); path.lineTo(20f, 25f); path.lineTo(4f, 30f); path.close()
                canvas.drawPath(path, fill)
            }
        }
        fill.alpha = 255
        canvas.restore()
    }

    private fun drawTag(canvas: Canvas, a: AircraftState, x: Float, y: Float, color: Int, dp: Float, selected: Boolean) {
        val main = a.identity.label
        val sub = tagSub(a)
        text.textSize = 14 * dp
        text.typeface = condensedBold
        text.textAlign = Paint.Align.LEFT
        val wMain = text.measureText(main)
        text.typeface = condensedRegular
        val wSub = if (sub.isEmpty()) 0f else text.measureText(" $sub")
        val padX = 5 * dp
        val h = 19 * dp
        rect.set(x, y - h / 2, x + wMain + wSub + 2 * padX, y + h / 2)
        fill.color = colors.BG
        fill.alpha = 225
        canvas.drawRoundRect(rect, 4 * dp, 4 * dp, fill)
        fill.alpha = 255
        stroke.color = color
        stroke.strokeWidth = (if (selected) 2.5f else 1.2f) * dp
        stroke.pathEffect = if (a.signalLost) DashPathEffect(floatArrayOf(3 * dp, 2 * dp), 0f) else null
        canvas.drawRoundRect(rect, 4 * dp, 4 * dp, stroke)
        stroke.pathEffect = null
        val base = y + 5 * dp
        text.typeface = condensedBold
        text.color = if (a.status == FlightStatus.FINAL && !a.signalLost) colors.RED_TEXT else color
        canvas.drawText(main, x + padX, base, text)
        if (sub.isNotEmpty()) {
            text.typeface = condensedRegular
            text.color = colors.TEXT_2
            canvas.drawText(" $sub", x + padX + wMain, base, text)
        }
    }

    private val condensedRegular = Typeface.create("sans-serif-condensed", Typeface.NORMAL)

    private fun drawCart(canvas: Canvas, snap: Snapshot, dp: Float) {
        val cart = snap.cart ?: return
        val x = sx(cart.pos)
        val y = sy(cart.pos)
        fill.color = colors.CART_FILL
        canvas.drawCircle(x, y, 9 * dp, fill)
        stroke.color = colors.CART
        stroke.strokeWidth = 4 * dp
        canvas.drawCircle(x, y, 9 * dp, stroke)
    }

    private fun drawNorth(canvas: Canvas, x: Float, y: Float, dp: Float) {
        rect.set(x, y, x + 52 * dp, y + 32 * dp)
        fill.color = colors.SURFACE
        fill.alpha = 230
        canvas.drawRoundRect(rect, 8 * dp, 8 * dp, fill)
        fill.alpha = 255
        val d = turned(airfield!!.frame.directionOf(0.0))
        val angle = Math.toDegrees(atan2(d.x, d.y)).toFloat()
        canvas.save()
        canvas.translate(x + 16 * dp, y + 16 * dp)
        canvas.rotate(angle)
        path.reset()
        path.moveTo(0f, -10 * dp); path.lineTo(6 * dp, 8 * dp); path.lineTo(0f, 4 * dp); path.lineTo(-6 * dp, 8 * dp); path.close()
        fill.color = colors.TEXT
        canvas.drawPath(path, fill)
        canvas.restore()
        text.typeface = sansBold
        text.textSize = 13 * dp
        text.color = colors.TEXT
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("N", x + 32 * dp, y + 21 * dp, text)
    }

    private fun drawScale(canvas: Canvas, x: Float, y: Float, width: Float, dp: Float) {
        val target = width / 5 / scale
        val m = listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0).lastOrNull { it <= target } ?: 50.0
        val w = (m * scale).toFloat()
        stroke.color = colors.FAINT
        stroke.strokeWidth = 2 * dp
        stroke.strokeCap = Paint.Cap.BUTT
        path.reset()
        path.moveTo(x, y - 18 * dp); path.lineTo(x, y - 14 * dp); path.lineTo(x + w, y - 14 * dp); path.lineTo(x + w, y - 18 * dp)
        canvas.drawPath(path, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
        text.typeface = sans
        text.textSize = 11 * dp
        text.color = colors.MUTED
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(if (m >= 1000) "${trim(m / 1000)} km" else "${m.toInt()} m", x, y, text)
    }

    private fun trim(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)
}
