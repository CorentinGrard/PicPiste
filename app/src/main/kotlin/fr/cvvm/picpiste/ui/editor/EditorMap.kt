package fr.cvvm.picpiste.ui.editor

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import fr.cvvm.picpiste.core.airfield.AirfieldConfig
import fr.cvvm.picpiste.core.airfield.Shape
import fr.cvvm.picpiste.core.airfield.exists
import fr.cvvm.picpiste.core.airfield.movePoint
import fr.cvvm.picpiste.core.airfield.moveShape
import fr.cvvm.picpiste.core.airfield.points
import fr.cvvm.picpiste.core.airfield.shapes
import fr.cvvm.picpiste.core.geo.GeoPoint
import fr.cvvm.picpiste.core.geo.LocalFrame
import fr.cvvm.picpiste.core.geo.Polygon
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.geo.distanceToSegment
import fr.cvvm.picpiste.data.PhoneFix
import fr.cvvm.picpiste.render.Palette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Everything the airfield editor is working on. [saved] is the airfield in use on this phone;
 * [draft] is what is on screen, saved only when the user taps "Enregistrer".
 */
class EditorState(saved: AirfieldConfig) {
    var saved by mutableStateOf(saved)
        private set
    var draft by mutableStateOf(saved)
        private set
    /** Things to check after an import ("altitude estimée"…). */
    var warnings by mutableStateOf(emptyList<String>())
    var selected by mutableStateOf<Shape?>(null)
        private set
    var vertex by mutableIntStateOf(0)
    /** Dragging a point moves the whole shape. */
    var moveWhole by mutableStateOf(false)

    private val undoStack = mutableStateListOf<AirfieldConfig>()
    val canUndo get() = undoStack.isNotEmpty()
    val changed get() = draft != saved

    /** Map frame: fixed while editing, so the view does not jump when points move. */
    var frame by mutableStateOf(frameOf(saved))
        private set

    // View: centre of the screen in local metres, and zoom.
    var centre by mutableStateOf(Vec2(0.0, 0.0))
    var pxPerM by mutableFloatStateOf(0f)
    /** Set to fit the whole airfield on screen at the next layout. */
    var needsFit by mutableStateOf(true)

    private var dragBase: AirfieldConfig? = null

    fun edit(new: AirfieldConfig) {
        if (new == draft) return
        pushUndo()
        draft = new
        keepSelectionValid()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        draft = prev
        keepSelectionValid()
    }

    fun select(shape: Shape?, vertex: Int = 0) {
        selected = shape
        this.vertex = vertex
    }

    /** A new airfield (from OSM or a file): not saved yet. */
    fun load(config: AirfieldConfig, warnings: List<String>) {
        pushUndo()
        draft = config
        this.warnings = warnings
        selected = null
        frame = frameOf(config)
        needsFit = true
    }

    fun markSaved(config: AirfieldConfig) {
        saved = config
        draft = config
        warnings = emptyList()
    }

    fun beginDrag() {
        pushUndo()
        dragBase = draft
    }

    fun dragTo(index: Int, to: GeoPoint) {
        val base = dragBase ?: return
        val shape = selected ?: return
        draft = if (moveWhole) base.moveShape(shape, index, to) else base.movePoint(shape, index, to)
    }

    fun endDrag() {
        dragBase = null
    }

    private fun pushUndo() {
        undoStack.add(draft)
        if (undoStack.size > 100) undoStack.removeAt(0)
    }

    private fun keepSelectionValid() {
        val s = selected ?: return
        if (!draft.exists(s)) selected = null
        else vertex = vertex.coerceIn(0, draft.points(s).lastIndex)
    }

    fun toLocal(p: List<Double>): Vec2 = frame.toLocal(GeoPoint(p[0], p[1]))

    companion object {
        fun frameOf(c: AirfieldConfig) = LocalFrame(GeoPoint(c.reference[0], c.reference[1]), c.axisBearingDeg)
    }
}

/** Map of the airfield being edited: drag the empty map to pan, pinch to zoom, tap to select, drag a point to move it. */
@Composable
fun EditorMap(state: EditorState, fix: PhoneFix?, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(Offset.Zero) }
    val text = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    }

    fun toScreen(v: Vec2) = Offset(
        size.x / 2 + ((v.x - state.centre.x) * state.pxPerM).toFloat(),
        size.y / 2 - ((v.y - state.centre.y) * state.pxPerM).toFloat(),
    )

    fun toWorld(o: Offset) = Vec2(
        state.centre.x + (o.x - size.x / 2) / state.pxPerM,
        state.centre.y - (o.y - size.y / 2) / state.pxPerM,
    )

    fun fit() {
        val pts = state.draft.shapes().flatMap { s -> state.draft.points(s).map(state::toLocal) }
        if (pts.isEmpty() || size.x <= 0) return
        val x0 = pts.minOf { it.x }; val x1 = pts.maxOf { it.x }
        val y0 = pts.minOf { it.y }; val y1 = pts.maxOf { it.y }
        state.centre = Vec2((x0 + x1) / 2, (y0 + y1) / 2)
        state.pxPerM = min(size.x / max(x1 - x0, 50.0), size.y / max(y1 - y0, 50.0)).toFloat() * 0.85f
        state.needsFit = false
    }

    Canvas(
        modifier
            .onSizeChanged { size = Offset(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val grab = handleAt(state, down.position, 28 * density, ::toScreen)
                    if (grab != null) {
                        // Drag a point (or the whole shape) of the selected element.
                        state.vertex = grab
                        state.beginDrag()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            state.dragTo(grab, state.frame.toGeo(toWorld(ch.position)))
                            ch.consume()
                        }
                        state.endDrag()
                        return@awaitEachGesture
                    }
                    // Pan and zoom the map; a short touch without moving is a tap.
                    var moved = false
                    var travel = Offset.Zero
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.none { it.pressed }) break
                        val zoom = ev.calculateZoom()
                        val pan = ev.calculatePan()
                        travel += pan
                        if (ev.changes.size > 1 || travel.getDistance() > viewConfiguration.touchSlop) moved = true
                        if (!moved) continue
                        val c = ev.calculateCentroid()
                        if (zoom != 1f && c != Offset.Unspecified) {
                            val w = toWorld(c)
                            state.pxPerM = (state.pxPerM * zoom).coerceIn(0.02f, 40f)
                            state.centre = Vec2(w.x - (c.x - size.x / 2) / state.pxPerM, w.y + (c.y - size.y / 2) / state.pxPerM)
                        }
                        state.centre = Vec2(state.centre.x - pan.x / state.pxPerM, state.centre.y + pan.y / state.pxPerM)
                        ev.changes.forEach { it.consume() }
                    }
                    if (!moved) {
                        val hit = shapeAt(state, down.position, 20 * density, ::toScreen)
                        state.select(hit, if (hit == null) 0 else nearestVertex(state, hit, down.position, ::toScreen))
                    }
                }
            },
    ) {
        if (state.needsFit || state.pxPerM == 0f) fit()
        if (state.pxPerM == 0f) return@Canvas
        drawRect(Color(Palette.MAP_BG))
        drawAirfield(state, ::toScreen, density, text)
        drawSelection(state, ::toScreen, density)
        fix?.let { drawFix(it, state, ::toScreen, density) }
    }
}

private fun path(points: List<Offset>, close: Boolean) = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    if (close) close()
}

private fun DrawScope.drawAirfield(state: EditorState, toScreen: (Vec2) -> Offset, dp: Float, text: Paint) {
    val c = state.draft
    fun scr(p: List<Double>) = toScreen(state.toLocal(p))
    val k = state.pxPerM

    if (c.boundary.size >= 3) {
        val p = path(c.boundary.map(::scr), true)
        drawPath(p, Color(Palette.BOUNDARY_FILL))
        drawPath(p, Color(Palette.BOUNDARY_STROKE), style = Stroke(1.5f * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6 * dp, 4 * dp))))
    }
    c.aprons.forEach { drawPath(path(it.map(::scr), true), Color(Palette.APRON)) }
    c.taxiways.forEach { t ->
        drawPath(
            path(t.path.map(::scr), false), Color(Palette.TAXIWAY),
            style = Stroke(max((t.widthM * k).toFloat(), 2 * dp), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
    c.runways.forEach { rw ->
        val grass = rw.surface == "grass"
        val p = path(rw.outline.map(::scr), true)
        drawPath(p, Color(if (grass) Palette.GRASS else Palette.PAVED))
        if (grass) drawPath(p, Color(Palette.GRASS_EDGE), style = Stroke(1 * dp))
        // Centre line and designators at each threshold.
        val a = scr(rw.ends[0].threshold)
        val b = scr(rw.ends[1].threshold)
        drawLine(Color(Palette.TEXT).copy(alpha = 0.35f), a, b, 1 * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8 * dp, 6 * dp)))
    }
    c.hangars.forEach {
        val p = path(it.map(::scr), true)
        drawPath(p, Color(Palette.HANGAR))
        drawPath(p, Color(Palette.HANGAR_EDGE), style = Stroke(1 * dp))
    }
    c.windsocks.forEach { drawCircle(Color(Palette.WINDSOCK), 5 * dp, scr(it)) }
    c.starter?.let { s ->
        val o = scr(s)
        val hx = max(15 * k, 4 * dp)
        val hy = max(7 * k, 3 * dp)
        drawRect(Color(Palette.HANGAR), Offset(o.x - hx, o.y - hy), androidx.compose.ui.geometry.Size(2 * hx, 2 * hy))
        drawRect(Color(Palette.STARTER_EDGE), Offset(o.x - hx, o.y - hy), androidx.compose.ui.geometry.Size(2 * hx, 2 * hy), style = Stroke(1 * dp))
    }
    // Thresholds on top: a dot and the designator, just outside the runway.
    text.textSize = 13 * dp
    drawIntoCanvas { canvas ->
        c.runways.forEach { rw ->
            val a = state.toLocal(rw.ends[0].threshold)
            val b = state.toLocal(rw.ends[1].threshold)
            val d = (b - a).normalized()
            val off = 16 * dp / k
            for ((i, end) in rw.ends.withIndex()) {
                val p = if (i == 0) a - d * off.toDouble() else b + d * off.toDouble()
                val o = toScreen(p)
                text.color = Palette.TEXT
                canvas.nativeCanvas.drawText(end.ref, o.x, o.y + 4.5f * dp, text)
                drawCircle(Color(Palette.TEXT), 3.5f * dp, toScreen(if (i == 0) a else b))
            }
        }
    }
}

private fun DrawScope.drawSelection(state: EditorState, toScreen: (Vec2) -> Offset, dp: Float) {
    val s = state.selected ?: return
    val pts = state.draft.points(s).map { toScreen(state.toLocal(it)) }
    val accent = Color(Palette.CIRCUIT)
    when (s.kind) {
        Shape.Kind.POLYGON -> drawPath(path(pts, true), accent, style = Stroke(2.5f * dp, join = StrokeJoin.Round))
        Shape.Kind.LINE -> drawPath(path(pts, false), accent, style = Stroke(2.5f * dp, join = StrokeJoin.Round))
        Shape.Kind.POINT -> Unit
    }
    pts.forEachIndexed { i, p ->
        val current = i == state.vertex
        drawCircle(Color(Palette.BG), (if (current) 11 else 8) * dp, p)
        drawCircle(if (current) accent else Color(Palette.TEXT), (if (current) 11 else 8) * dp, p, style = Stroke((if (current) 3.5f else 2f) * dp))
    }
}

private fun DrawScope.drawFix(fix: PhoneFix, state: EditorState, toScreen: (Vec2) -> Offset, dp: Float) {
    val o = toScreen(state.frame.toLocal(fix.geo))
    fix.accuracyM?.let { drawCircle(Color(Palette.CART).copy(alpha = 0.15f), (it * state.pxPerM).toFloat().coerceAtLeast(9 * dp), o) }
    drawCircle(Color(Palette.CART_FILL), 7 * dp, o)
    drawCircle(Color(Palette.CART), 7 * dp, o, style = Stroke(3 * dp))
}

/** Index of the selected shape's point under [at], if any. */
private fun handleAt(state: EditorState, at: Offset, radius: Float, toScreen: (Vec2) -> Offset): Int? {
    val s = state.selected ?: return null
    val pts = state.draft.points(s).map { toScreen(state.toLocal(it)) }
    val best = pts.indices.minByOrNull { (pts[it] - at).getDistance() } ?: return null
    return best.takeIf { (pts[it] - at).getDistance() <= radius }
}

private fun nearestVertex(state: EditorState, s: Shape, at: Offset, toScreen: (Vec2) -> Offset): Int {
    val pts = state.draft.points(s).map { toScreen(state.toLocal(it)) }
    return pts.indices.minByOrNull { (pts[it] - at).getDistance() } ?: 0
}

/**
 * What a tap at [at] selects: a point-like thing first (threshold, windsock, starter),
 * then a line or an outline edge, then the smallest area containing the tap.
 */
private fun shapeAt(state: EditorState, at: Offset, radius: Float, toScreen: (Vec2) -> Offset): Shape? {
    val shapes = state.draft.shapes().reversed()
    val screen = shapes.associateWith { s -> state.draft.points(s).map { toScreen(state.toLocal(it)) } }
    fun v(o: Offset) = Vec2(o.x.toDouble(), o.y.toDouble())
    val p = v(at)

    shapes.filter { it.kind == Shape.Kind.POINT }
        .map { it to (screen.getValue(it).single() - at).getDistance() }
        .filter { it.second <= radius * 1.3f }
        .minByOrNull { it.second }?.let { return it.first }

    fun edgeDistance(s: Shape): Double {
        val pts = screen.getValue(s).map(::v)
        val n = if (s.kind == Shape.Kind.POLYGON) pts.size else pts.size - 1
        return (0 until n).minOfOrNull { i -> distanceToSegment(p, pts[i], pts[(i + 1) % pts.size]) } ?: Double.MAX_VALUE
    }
    shapes.filter { it.kind != Shape.Kind.POINT && it != Shape.Boundary }
        .map { it to edgeDistance(it) }
        .filter { it.second <= radius }
        .minByOrNull { it.second }?.let { return it.first }

    shapes.filter { it.kind == Shape.Kind.POLYGON }
        .map { it to screen.getValue(it).map(::v) }
        .filter { it.second.size >= 3 && Polygon(it.second).contains(p) }
        .minByOrNull { area(it.second) }?.let { return it.first }

    if (edgeDistance(Shape.Boundary.takeIf { it in shapes } ?: return null) <= radius) return Shape.Boundary
    return null
}

private fun area(pts: List<Vec2>) = abs(pts.indices.sumOf { i -> pts[i] cross pts[(i + 1) % pts.size] }) / 2

/** Map centre, in geo coordinates, where new shapes are added. */
fun EditorState.centreGeo(): GeoPoint = frame.toGeo(centre)
