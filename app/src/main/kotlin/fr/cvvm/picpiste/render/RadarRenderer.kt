package fr.cvvm.picpiste.render

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import fr.cvvm.picpiste.core.Snapshot
import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.aprs.AircraftType
import fr.cvvm.picpiste.core.cart.formatEta
import fr.cvvm.picpiste.core.geo.Polygon
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.traffic.AircraftState
import fr.cvvm.picpiste.core.traffic.FlightStatus
import kotlin.math.atan2
import kotlin.math.min

/**
 * Draws the fixed, runway-aligned radar on any Android canvas: the phone screen
 * (through Compose) and the Android Auto surface use the same code.
 */
class RadarRenderer(private val airfield: Airfield) {
    private companion object {
        const val STARTER_LENGTH_M = 30.0
        const val STARTER_WIDTH_M = 14.0
    }

    data class Hit(val address: String, val x: Float, val y: Float)

    /** Screen positions of the aircraft drawn last, for tap handling on the phone. */
    var hits: List<Hit> = emptyList()
        private set

    /** Middle of the three runways: the radar is centred on it. */
    private val fieldCentre: Vec2 = airfield.runways
        .map { (it.threshold12 + it.threshold30) * 0.5 }
        .reduce { a, b -> a + b } * (1.0 / airfield.runways.size)

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

    private fun sx(v: Vec2) = cx + ((v.x - fieldCentre.x) * scale).toFloat()
    private fun sy(v: Vec2) = cy - ((v.y - fieldCentre.y) * scale).toFloat()

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
        fill.color = Palette.MAP_BG
        canvas.drawRect(left, top, left + width, top + height, fill)

        cx = left + width / 2
        cy = top + height / 2
        scale = (min(width, height) / 2 / radiusM).toFloat()

        drawRings(canvas, radiusM, dp)
        drawAirfield(canvas, snap, dp)
        drawTraffic(canvas, snap, dp, selected)
        drawCart(canvas, snap, dp)
        if (showNorth) drawNorth(canvas, left + 16 * dp, top + 16 * dp, dp)
        if (showScale) drawScale(canvas, left + 16 * dp, top + height - 16 * dp, width, dp)
        canvas.restore()
    }

    private fun drawRings(canvas: Canvas, radiusM: Double, dp: Float) {
        stroke.color = Palette.RING
        stroke.strokeWidth = 1 * dp
        stroke.pathEffect = null
        text.typeface = sans
        text.textSize = 11 * dp
        text.color = Palette.FAINT
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
        // Boundary
        fill.color = Palette.BOUNDARY_FILL
        canvas.drawPath(polyPath(airfield.boundary), fill)
        stroke.color = Palette.BOUNDARY_STROKE
        stroke.strokeWidth = 1 * dp
        stroke.pathEffect = DashPathEffect(floatArrayOf(5 * dp, 4 * dp), 0f)
        canvas.drawPath(path, stroke)
        stroke.pathEffect = null

        // Aprons, then taxiways on top
        fill.color = Palette.APRON
        airfield.aprons.forEach { canvas.drawPath(polyPath(it), fill) }
        stroke.color = Palette.TAXIWAY
        for (t in airfield.taxiways) {
            stroke.strokeWidth = maxOf((t.widthM * scale).toFloat(), 2 * dp)
            path.reset()
            t.path.forEachIndexed { i, p -> if (i == 0) path.moveTo(sx(p), sy(p)) else path.lineTo(sx(p), sy(p)) }
            canvas.drawPath(path, stroke)
        }

        // Runways
        for (rw in airfield.runways) {
            fill.color = if (rw.isGrass) Palette.GRASS else Palette.PAVED
            canvas.drawPath(polyPath(rw.outline), fill)
            if (rw.isGrass) {
                stroke.color = Palette.GRASS_EDGE
                stroke.strokeWidth = 1 * dp
                canvas.drawPath(path, stroke)
            }
        }

        // Hangars and windsock
        for (h in airfield.hangars) {
            fill.color = Palette.HANGAR
            canvas.drawPath(polyPath(h), fill)
            stroke.color = Palette.HANGAR_EDGE
            stroke.strokeWidth = 1 * dp
            canvas.drawPath(path, stroke)
        }
        fill.color = Palette.WINDSOCK
        airfield.windsocks.forEach { canvas.drawCircle(sx(it), sy(it), 3.5f * dp, fill) }

        // Runway numbers, when the runways are big enough to label
        val rwPx = airfield.runways.maxOf { it.length } * scale
        if (rwPx > 90 * dp) {
            text.typeface = condensedBold
            text.textSize = 13 * dp
            text.color = Palette.MUTED
            text.textAlign = Paint.Align.CENTER
            // Parallel runways share their numbers: one "12" and one "30", beside the whole group.
            val off = (14 * dp / scale).toDouble()
            val dir = airfield.runways.first().dir12
            val midY = airfield.runways.map { (it.threshold12.y + it.threshold30.y) / 2 }.average()
            val a = airfield.runways.minBy { it.threshold12 dot dir }.threshold12
            val b = airfield.runways.maxBy { it.threshold30 dot dir }.threshold30
            val pa = Vec2(a.x, midY) - dir * off
            val pb = Vec2(b.x, midY) + dir * off
            canvas.drawText("12", sx(pa), sy(pa) + 4.5f * dp, text)
            canvas.drawText("30", sx(pb), sy(pb) + 4.5f * dp, text)
        }

        airfield.starter?.let { drawStarter(canvas, it) }
    }

    /** The starter: a plain box like the hangars, aligned with the runways (local axes = screen axes). */
    private fun drawStarter(canvas: Canvas, p: Vec2) {
        val halfLong = maxOf((STARTER_LENGTH_M / 2 * scale).toFloat(), 3f)
        val halfWide = maxOf((STARTER_WIDTH_M / 2 * scale).toFloat(), 2f)
        rect.set(sx(p) - halfLong, sy(p) - halfWide, sx(p) + halfLong, sy(p) + halfWide)
        fill.color = Palette.HANGAR
        canvas.drawRect(rect, fill)
        stroke.color = Palette.HANGAR_EDGE
        stroke.strokeWidth = 1f
        canvas.drawRect(rect, stroke)
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
            val color = a.color

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

            drawIcon(canvas, a, x, y, color, dp)
            drawTag(canvas, a, x + 14 * dp, y, color, dp, selected == a.address)
            newHits += Hit(a.address, x, y)
        }
        hits = newHits
    }

    private fun drawIcon(canvas: Canvas, a: AircraftState, x: Float, y: Float, color: Int, dp: Float) {
        val size = (if (a.status.isAirborne || a.status == FlightStatus.TAKEOFF) 26 else 22) * dp
        val angle = a.fix.trackDeg?.let { track ->
            val d = airfield.frame.directionOf(track)
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
                rect.set(7f, 13f, 33f, 18f); canvas.drawRoundRect(rect, 2f, 2f, fill)
                rect.set(17.5f, 5f, 22.5f, 33f); canvas.drawRoundRect(rect, 2f, 2f, fill)
                rect.set(12f, 29f, 28f, 32f); canvas.drawRoundRect(rect, 1f, 1f, fill)
                rect.set(14f, 3f, 26f, 5f); canvas.drawRoundRect(rect, 1f, 1f, fill)
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
        val sub = when {
            a.signalLost -> "signal perdu · ${a.ageMs / 1000} s"
            !a.status.isAirborne && a.status != FlightStatus.TAKEOFF -> ""
            a.status == FlightStatus.FINAL && a.etaS != null -> "${a.fix.aglM.coerceAtLeast(0.0).toInt()} m · ${formatEta(a.etaS!!)}"
            else -> "${a.fix.aglM.coerceAtLeast(0.0).toInt()} m"
        }
        text.textSize = 14 * dp
        text.typeface = condensedBold
        text.textAlign = Paint.Align.LEFT
        val wMain = text.measureText(main)
        text.typeface = condensedRegular
        val wSub = if (sub.isEmpty()) 0f else text.measureText(" $sub")
        val padX = 5 * dp
        val h = 19 * dp
        rect.set(x, y - h / 2, x + wMain + wSub + 2 * padX, y + h / 2)
        fill.color = Palette.BG
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
        text.color = if (a.status == FlightStatus.FINAL && !a.signalLost) Palette.RED_TEXT else color
        canvas.drawText(main, x + padX, base, text)
        if (sub.isNotEmpty()) {
            text.typeface = condensedRegular
            text.color = Palette.TEXT_2
            canvas.drawText(" $sub", x + padX + wMain, base, text)
        }
    }

    private val condensedRegular = Typeface.create("sans-serif-condensed", Typeface.NORMAL)

    private fun drawCart(canvas: Canvas, snap: Snapshot, dp: Float) {
        val cart = snap.cart ?: return
        val x = sx(cart.pos)
        val y = sy(cart.pos)
        fill.color = Palette.CART_FILL
        canvas.drawCircle(x, y, 9 * dp, fill)
        stroke.color = Palette.CART
        stroke.strokeWidth = 4 * dp
        canvas.drawCircle(x, y, 9 * dp, stroke)
    }

    private fun drawNorth(canvas: Canvas, x: Float, y: Float, dp: Float) {
        rect.set(x, y, x + 52 * dp, y + 32 * dp)
        fill.color = Palette.SURFACE
        fill.alpha = 230
        canvas.drawRoundRect(rect, 8 * dp, 8 * dp, fill)
        fill.alpha = 255
        val d = airfield.frame.directionOf(0.0)
        val angle = Math.toDegrees(atan2(d.x, d.y)).toFloat()
        canvas.save()
        canvas.translate(x + 16 * dp, y + 16 * dp)
        canvas.rotate(angle)
        path.reset()
        path.moveTo(0f, -10 * dp); path.lineTo(6 * dp, 8 * dp); path.lineTo(0f, 4 * dp); path.lineTo(-6 * dp, 8 * dp); path.close()
        fill.color = Palette.TEXT
        canvas.drawPath(path, fill)
        canvas.restore()
        text.typeface = sansBold
        text.textSize = 13 * dp
        text.color = Palette.TEXT
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("N", x + 32 * dp, y + 21 * dp, text)
    }

    private fun drawScale(canvas: Canvas, x: Float, y: Float, width: Float, dp: Float) {
        val target = width / 5 / scale
        val m = listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0).lastOrNull { it <= target } ?: 50.0
        val w = (m * scale).toFloat()
        stroke.color = Palette.FAINT
        stroke.strokeWidth = 2 * dp
        stroke.strokeCap = Paint.Cap.BUTT
        path.reset()
        path.moveTo(x, y - 18 * dp); path.lineTo(x, y - 14 * dp); path.lineTo(x + w, y - 14 * dp); path.lineTo(x + w, y - 18 * dp)
        canvas.drawPath(path, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
        text.typeface = sans
        text.textSize = 11 * dp
        text.color = Palette.MUTED
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(if (m >= 1000) "${trim(m / 1000)} km" else "${m.toInt()} m", x, y, text)
    }

    private fun trim(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)
}
