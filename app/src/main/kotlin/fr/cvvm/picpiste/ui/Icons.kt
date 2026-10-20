package fr.cvvm.picpiste.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Small drawn icons. Text glyphs ("←", "⋯") sit off-centre in round buttons because of
 * font metrics; these are centred exactly.
 */
enum class IconKind { BACK, UNDO, MORE, CLOSE }

@Composable
fun LineIcon(kind: IconKind, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * w, y * h)
        when (kind) {
            IconKind.BACK -> {
                drawLine(color, p(0.85f, 0.5f), p(0.15f, 0.5f), stroke.width, StrokeCap.Round)
                drawPath(Path().apply { moveTo(0.45f * w, 0.2f * h); lineTo(0.15f * w, 0.5f * h); lineTo(0.45f * w, 0.8f * h) }, color, style = stroke)
            }
            IconKind.UNDO -> {
                drawPath(
                    Path().apply {
                        moveTo(0.15f * w, 0.38f * h)
                        lineTo(0.6f * w, 0.38f * h)
                        cubicTo(0.92f * w, 0.38f * h, 0.92f * w, 0.84f * h, 0.6f * w, 0.84f * h)
                        lineTo(0.35f * w, 0.84f * h)
                    },
                    color, style = stroke,
                )
                drawPath(Path().apply { moveTo(0.35f * w, 0.16f * h); lineTo(0.15f * w, 0.38f * h); lineTo(0.35f * w, 0.6f * h) }, color, style = stroke)
            }
            IconKind.CLOSE -> {
                drawLine(color, p(0.22f, 0.22f), p(0.78f, 0.78f), stroke.width, StrokeCap.Round)
                drawLine(color, p(0.78f, 0.22f), p(0.22f, 0.78f), stroke.width, StrokeCap.Round)
            }
            IconKind.MORE -> listOf(0.18f, 0.5f, 0.82f).forEach { drawCircle(color, 2.4.dp.toPx(), p(it, 0.5f)) }
        }
    }
}
