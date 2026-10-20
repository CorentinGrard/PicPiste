package fr.cvvm.picpiste.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import fr.cvvm.picpiste.R
import fr.cvvm.picpiste.core.Snapshot
import fr.cvvm.picpiste.core.cart.AlertLevel
import fr.cvvm.picpiste.core.cart.formatEta
import fr.cvvm.picpiste.core.traffic.AircraftState
import fr.cvvm.picpiste.core.traffic.FlightStatus

/** The parts of the car screen around the radar: side panel, alert banner, red border. */
class CarOverlay(private val context: Context) {
    /** Colours to draw with: the phone's theme, or the car's day / night. */
    var colors: Colors = Colors.DARK

    private val condensedBold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private val sansBold = Typeface.create("sans-serif", Typeface.BOLD)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** Left panel: app name, connection, arrivals, last landings. */
    fun drawPanel(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, snap: Snapshot, dp: Float) {
        fill.color = colors.BG
        canvas.drawRect(left, top, left + width, top + height, fill)
        fill.color = colors.RING
        canvas.drawRect(left + width - 1 * dp, top, left + width, top + height, fill)

        val pad = 16 * dp
        val x0 = left + pad
        val x1 = left + width - pad
        var y = top + pad

        // Header
        text.typeface = sansBold
        text.textSize = 22 * dp
        text.color = colors.TEXT
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("PicPiste", x0, y + 20 * dp, text)
        drawConnection(canvas, x1, y + 2 * dp, snap, dp)
        y += 44 * dp

        y = drawSection(canvas, context.getString(R.string.car_arrivals), x0, y, dp)
        val maxRows = ((top + height - y - 110 * dp) / (68 * dp)).toInt().coerceIn(1, 5)
        if (snap.arrivals.isEmpty()) {
            // "Circuit clear" only while OGN data is flowing; otherwise an empty list proves nothing.
            val live = snap.connection.isLive(snap.timeMs)
            rect.set(x0, y, x1, y + 58 * dp)
            fill.color = colors.SURFACE
            canvas.drawRoundRect(rect, 10 * dp, 10 * dp, fill)
            text.typeface = sansBold
            text.textSize = 18 * dp
            text.color = if (live) colors.OK else colors.CIRCUIT
            canvas.drawText(context.getString(if (live) R.string.circuit_clear else R.string.car_no_data), x0 + 14 * dp, y + 25 * dp, text)
            text.typeface = sans
            text.textSize = 13 * dp
            text.color = colors.MUTED
            canvas.drawText(context.getString(if (live) R.string.nobody_in_circuit else R.string.car_look_sky), x0 + 14 * dp, y + 45 * dp, text)
            y += 68 * dp
        } else {
            for (a in snap.arrivals.take(maxRows)) {
                drawArrival(canvas, a, x0, y, x1 - x0, dp, highlight = a.status == FlightStatus.FINAL, danger = snap.alert.threat?.address == a.address && snap.alert.level == AlertLevel.DANGER)
                y += 68 * dp
            }
        }

        val landed = snap.landed.take(2)
        if (landed.isNotEmpty() && y + 40 * dp < top + height) {
            y += 4 * dp
            y = drawSection(canvas, context.getString(R.string.car_landed), x0, y, dp)
            for (a in landed) {
                if (y + 44 * dp > top + height) break
                rect.set(x0, y, x1, y + 40 * dp)
                fill.color = colors.SURFACE
                canvas.drawRoundRect(rect, 10 * dp, 10 * dp, fill)
                fill.color = colors.LANDED
                canvas.drawCircle(x0 + 16 * dp, y + 20 * dp, 6 * dp, fill)
                text.typeface = condensedBold
                text.textSize = 19 * dp
                text.color = colors.TEXT
                canvas.drawText(a.identity.label, x0 + 30 * dp, y + 27 * dp, text)
                val labelW = text.measureText(a.identity.label)
                text.typeface = sans
                text.textSize = 15 * dp
                val ago = a.landedAtMs?.let { minutesAgo(snap.timeMs - it) } ?: ""
                text.color = colors.MUTED
                text.textAlign = Paint.Align.RIGHT
                canvas.drawText(ago, x1 - 12 * dp, y + 26 * dp, text)
                text.textAlign = Paint.Align.LEFT
                text.color = colors.TEXT_2
                val room = x1 - 12 * dp - text.measureText(ago) - 10 * dp - (x0 + 38 * dp + labelW)
                val where = TextUtils.ellipsize(" · ${a.landedText ?: context.getString(R.string.landed_fallback)}", text, room, TextUtils.TruncateAt.END)
                canvas.drawText(where, 0, where.length, x0 + 32 * dp + labelW, y + 26 * dp, text)
                y += 48 * dp
            }
        }
    }

    private fun drawSection(canvas: Canvas, title: String, x: Float, y: Float, dp: Float): Float {
        text.typeface = sansBold
        text.textSize = 12 * dp
        text.letterSpacing = 0.12f
        text.color = colors.MUTED
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(title, x, y + 12 * dp, text)
        text.letterSpacing = 0f
        return y + 22 * dp
    }

    private fun drawConnection(canvas: Canvas, right: Float, y: Float, snap: Snapshot, dp: Float) {
        val c = snap.connection
        val ok = c.isLive(snap.timeMs)
        val silentMs = c.silentForMs(snap.timeMs)
        val label = when {
            ok -> context.getString(R.string.ogn)
            silentMs != null -> context.getString(R.string.ogn_no_data_for, silence(silentMs))
            else -> context.getString(R.string.offline)
        }
        text.typeface = sans
        text.textSize = 13 * dp
        val w = text.measureText(label) + 32 * dp
        rect.set(right - w, y, right, y + 26 * dp)
        fill.color = colors.SURFACE
        canvas.drawRoundRect(rect, 13 * dp, 13 * dp, fill)
        fill.color = if (ok) colors.OK else colors.RED
        canvas.drawCircle(rect.left + 13 * dp, y + 13 * dp, 4.5f * dp, fill)
        text.color = colors.MUTED
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(label, rect.left + 23 * dp, y + 18 * dp, text)
    }

    private fun drawArrival(canvas: Canvas, a: AircraftState, x: Float, y: Float, w: Float, dp: Float, highlight: Boolean, danger: Boolean) {
        rect.set(x, y, x + w, y + 60 * dp)
        fill.color = if (danger) colors.DANGER_FILL else colors.SURFACE
        canvas.drawRoundRect(rect, 10 * dp, 10 * dp, fill)
        if (highlight) {
            stroke.color = colors.RED
            stroke.strokeWidth = (if (danger) 2f else 1.5f) * dp
            canvas.drawRoundRect(rect, 10 * dp, 10 * dp, stroke)
        }
        fill.color = colors.of(a)
        canvas.drawCircle(x + 16 * dp, y + 30 * dp, 6 * dp, fill)

        val eta = a.etaS?.let(::formatEta) ?: ""
        text.typeface = condensedBold
        text.textSize = (if (a.status == FlightStatus.FINAL) 28 else 24) * dp
        text.color = colors.TEXT
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(eta, x + w - 12 * dp, y + 40 * dp, text)
        val etaW = text.measureText(eta)

        text.textAlign = Paint.Align.LEFT
        text.textSize = 24 * dp
        canvas.drawText(a.identity.label, x + 30 * dp, y + 27 * dp, text)
        val labelW = text.measureText(a.identity.label)
        val room = w - 30 * dp - etaW - 24 * dp
        text.typeface = sans
        text.textSize = 13 * dp
        text.color = colors.MUTED
        val reg = listOfNotNull(a.identity.registration, a.identity.type.takeIf { it.name == "TOW_PLANE" }?.label?.lowercase()).joinToString(" · ")
        val regText = TextUtils.ellipsize(reg, text, room - labelW - 8 * dp, TextUtils.TruncateAt.END)
        canvas.drawText(regText, 0, regText.length, x + 36 * dp + labelW, y + 26 * dp, text)
        text.textSize = 15 * dp
        text.color = if (a.status == FlightStatus.FINAL) colors.RED_TEXT else colors.of(a)
        val st = TextUtils.ellipsize(a.statusText, text, room, TextUtils.TruncateAt.END)
        canvas.drawText(st, 0, st.length, x + 30 * dp, y + 48 * dp, text)
    }

    /** Red banner across the top of the map while the cart is on a runway with traffic. */
    fun drawBanner(canvas: Canvas, left: Float, top: Float, width: Float, snap: Snapshot, dp: Float) {
        val alert = snap.alert
        if (alert.level != AlertLevel.DANGER && alert.level != AlertLevel.CAUTION) return
        // Red when close (with voice), amber while the glider is still far in the circuit.
        val danger = alert.level == AlertLevel.DANGER
        val h = 92 * dp
        fill.color = if (danger) colors.BANNER else colors.CAUTION_BANNER
        canvas.drawRect(left, top, left + width, top + h, fill)
        val pad = 22 * dp
        text.color = if (danger) colors.ON_BANNER else colors.ON_CAUTION
        text.typeface = condensedBold
        text.textSize = 56 * dp
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(alert.eta, left + width - pad, top + 64 * dp, text)
        val etaW = text.measureText(alert.eta)
        text.textAlign = Paint.Align.LEFT
        text.typeface = sansBold
        text.textSize = 27 * dp
        val room = width - 2 * pad - etaW - 20 * dp
        val title = TextUtils.ellipsize(alert.title, text, room, TextUtils.TruncateAt.END)
        canvas.drawText(title, 0, title.length, left + pad, top + 42 * dp, text)
        text.typeface = sans
        text.textSize = 18 * dp
        val detail = TextUtils.ellipsize(alert.detail, text, room, TextUtils.TruncateAt.END)
        canvas.drawText(detail, 0, detail.length, left + pad, top + 70 * dp, text)
    }

    /** Frame around the whole screen while something is coming: amber (circuit), red (final, takeoff). */
    fun drawRunwayBorder(canvas: Canvas, width: Float, height: Float, snap: Snapshot, dp: Float) {
        stroke.color = when (snap.alert.level) {
            AlertLevel.DANGER -> colors.RED
            AlertLevel.CAUTION -> colors.CAUTION_BANNER
            else -> return
        }
        val w = 10 * dp
        stroke.strokeWidth = w
        canvas.drawRect(w / 2, w / 2, width - w / 2, height - w / 2, stroke)
    }

    /** "45 s", "3 min": how long the OGN feed has been silent. */
    private fun silence(ms: Long): String = (ms / 1000).toInt().let { s ->
        if (s < 120) context.getString(R.string.seconds_short, s) else context.getString(R.string.minutes_short, s / 60)
    }

    /** No airfield chosen yet: the car cannot do anything useful, say where to choose it. */
    fun drawSetupMessage(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, dp: Float) {
        text.typeface = sansBold
        text.textSize = 28 * dp
        text.color = colors.TEXT
        text.textAlign = Paint.Align.CENTER
        val msg = TextUtils.ellipsize(context.getString(R.string.car_setup), text, width - 48 * dp, TextUtils.TruncateAt.END)
        canvas.drawText(msg, 0, msg.length, left + width / 2, top + height / 2, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun minutesAgo(ms: Long): String = (ms / 60_000).toInt().let {
        if (it < 1) context.getString(R.string.just_now) else context.getString(R.string.minutes_short, it)
    }
}
