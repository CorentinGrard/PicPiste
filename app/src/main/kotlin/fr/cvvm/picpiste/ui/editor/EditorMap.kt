package fr.cvvm.picpiste.ui.editor

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import fr.cvvm.picpiste.core.airfield.AirfieldConfig
import fr.cvvm.picpiste.core.airfield.Shape
import fr.cvvm.picpiste.core.airfield.snapMapUp
import fr.cvvm.picpiste.core.airfield.mapUpSnaps
import fr.cvvm.picpiste.core.airfield.widthM
import fr.cvvm.picpiste.core.airfield.translate
import fr.cvvm.picpiste.core.airfield.setRunwayWidth
import fr.cvvm.picpiste.core.airfield.rotateRunway
import fr.cvvm.picpiste.core.airfield.moveRunwayEnd
import fr.cvvm.picpiste.core.airfield.insertPoint
import fr.cvvm.picpiste.core.airfield.RunwayEnd
import fr.cvvm.picpiste.core.airfield.exists
import fr.cvvm.picpiste.core.airfield.movePoint
import fr.cvvm.picpiste.core.airfield.points
import fr.cvvm.picpiste.core.airfield.shapes
import fr.cvvm.picpiste.core.geo.GeoPoint
import fr.cvvm.picpiste.core.geo.LocalFrame
import fr.cvvm.picpiste.core.geo.Polygon
import fr.cvvm.picpiste.core.geo.Vec2
import kotlin.math.round
import fr.cvvm.picpiste.core.geo.normalizeDeg
import fr.cvvm.picpiste.core.geo.WebMercator
import fr.cvvm.picpiste.core.geo.distanceToSegment
import fr.cvvm.picpiste.data.MapBackground
import fr.cvvm.picpiste.data.PhoneFix
import fr.cvvm.picpiste.render.Palette
import kotlin.math.abs
import kotlin.math.floor
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
    /** Current point of [selected] (for a runway: 0 = A threshold, 1 = B), the one "Ici" places. */
    var vertex by mutableIntStateOf(0)

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
    /** Metres shown across the screen when there is nothing to fit (picking a place). */
    var emptySpanM = 3_000.0

    private var dragBase: AirfieldConfig? = null

    fun edit(new: AirfieldConfig) {
        if (new == draft) return
        pushUndo()
        draft = new
        keepSelectionValid()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        val turned = prev.mapUpDeg != draft.mapUpDeg
        draft = prev
        if (turned) turnView(upDeg - prev.mapUpDeg, centre)
        keepSelectionValid()
    }

    /** Compass direction at the top of the map now (it follows the fingers before being saved). */
    val upDeg: Double get() = normalizeDeg(frame.axisBearingDeg - 90)

    /**
     * Turns the view by [byDeg] (clockwise on screen) around [pivot] (local metres), which stays
     * where it is on screen.
     */
    fun turnView(byDeg: Double, pivot: Vec2) {
        val pivotGeo = frame.toGeo(pivot)
        val offset = pivot - centre
        frame = LocalFrame(frame.origin, frame.axisBearingDeg - byDeg)
        // The pivot keeps its place on screen: same offset from the centre in the new frame.
        centre = frame.toLocal(pivotGeo) - offset
    }

    /** Where the fingers have turned the map to, before snapping (null outside a turn). */
    private var fingersUp: Double? = null

    /**
     * Two fingers turned by [byDeg] (clockwise) around [pivot]. Close to north, or to a runway
     * straight up or across, the map snaps to it while the fingers keep turning.
     */
    fun turnBy(byDeg: Double, pivot: Vec2) {
        val raw = normalizeDeg((fingersUp ?: upDeg) - byDeg)
        fingersUp = raw
        val target = snapMapUp(raw, draft.mapUpSnaps(), SNAP_DEG)
        turnView(signedDeg(upDeg - target), pivot)
    }

    /** End of a turn: the direction is saved with the airfield (the radar uses it too). */
    fun commitView() {
        fingersUp = null
        val up = round(upDeg * 10) / 10
        val value = if (up >= 360.0) 0.0 else up
        if (value != draft.mapUpDeg) edit(draft.copy(mapUpDeg = value))
    }

    /** Compass: north up again. */
    fun northUp() {
        turnView(signedDeg(upDeg), centre)
        commitView()
    }

    private fun signedDeg(d: Double) = ((d % 360) + 540) % 360 - 180

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

    private var dragHandle: Handle? = null
    private var dragStart: GeoPoint? = null

    /** Starts dragging [handle] of the selected shape, grabbed at [at]. */
    fun beginDrag(handle: Handle, at: GeoPoint) {
        val shape = selected ?: return
        pushUndo()
        var h = handle
        if (h is Handle.Insert) {
            // A new point in the middle of the edge, then dragged like any point.
            val (config, index) = draft.insertPoint(shape, h.after)
            draft = config
            h = Handle.Point(index)
        }
        if (h is Handle.Point) vertex = h.index
        dragBase = draft
        dragHandle = h
        dragStart = at
    }

    fun dragTo(to: GeoPoint) {
        val base = dragBase ?: return
        val shape = selected ?: return
        val start = dragStart ?: return
        val runway = (shape as? Shape.RunwayOutline)?.index
        draft = when (val h = dragHandle) {
            is Handle.Point ->
                if (runway != null) base.moveRunwayEnd(runway, RunwayEnd.entries[h.index], to)
                else base.movePoint(shape, h.index, to)
            Handle.Move -> base.translate(shape, to.lat - start.lat, to.lon - start.lon)
            Handle.Rotate -> {
                if (runway == null) return
                val c = runwayMiddle(base, runway)
                val angle = frame.bearingOf(frame.toLocal(to) - c) - frame.bearingOf(frame.toLocal(start) - c)
                base.rotateRunway(runway, angle)
            }
            Handle.Width -> {
                if (runway == null) return
                val (a, b) = base.runways[runway].ends.map { toLocal(it.threshold) }
                val lateral = abs((b - a).normalized() cross (frame.toLocal(to) - a))
                base.setRunwayWidth(runway, 2 * lateral)
            }
            is Handle.Insert, null -> return
        }
    }

    fun endDrag() {
        dragBase = null
        dragHandle = null
        dragStart = null
    }

    fun runwayMiddle(c: AirfieldConfig, index: Int): Vec2 =
        c.runways[index].ends.map { toLocal(it.threshold) }.let { (a, b) -> (a + b) * 0.5 }

    private fun pushUndo() {
        undoStack.add(draft)
        if (undoStack.size > 100) undoStack.removeAt(0)
    }

    private fun keepSelectionValid() {
        val s = selected ?: return
        if (!draft.exists(s)) selected = null
        else vertex = vertex.coerceIn(0, if (s is Shape.RunwayOutline) 1 else draft.points(s).lastIndex)
    }

    fun toLocal(p: List<Double>): Vec2 = frame.toLocal(GeoPoint(p[0], p[1]))

    companion object {
        /** North up (x east), or the main runway along x, as the airfield asks. */
        /** The map frame: x across the screen, [AirfieldConfig.mapUpDeg] at the top. */
        fun frameOf(c: AirfieldConfig) = LocalFrame(GeoPoint(c.reference[0], c.reference[1]), c.mapUpDeg + 90)

        /** A turn this close to north, or to a runway straight up or across, snaps to it. */
        const val SNAP_DEG = 5.0

        /** Fingers must turn this much before the map turns, so pinching to zoom does not turn it. */
        const val ROTATE_SLOP_DEG = 10f
    }
}

/** What can be grabbed on the selected shape. */
sealed interface Handle {
    /** A corner, a line point, a threshold, a windsock… For a runway: 0 = A threshold, 1 = B. */
    data class Point(val index: Int) : Handle
    /** Middle of an edge: dragging it adds a point there. */
    data class Insert(val after: Int) : Handle
    /** Middle of the shape: dragging moves all of it. */
    data object Move : Handle
    /** Runways: turns the runway around its middle. */
    data object Rotate : Handle
    /** Runways: the side, dragged to set the width. */
    data object Width : Handle
}

/**
 * Map of the airfield being edited, over aerial photos or a street map ([background]):
 * drag the empty map to pan, pinch to zoom, tap to select, drag a point to move it.
 * With [pickOnly], only the map and a crosshair: the place is [EditorState.centreGeo].
 */
@Composable
fun EditorMap(state: EditorState, fix: PhoneFix?, background: MapBackground, modifier: Modifier = Modifier, pickOnly: Boolean = false) {
    val density = LocalDensity.current.density
    val context = LocalContext.current
    val tiles = remember { MapTiles.get(context) }
    val tilesLoaded by tiles.loaded.collectAsState()
    val bitmapPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
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
        if (size.x <= 0) return
        val pts = state.draft.shapes().flatMap { s -> state.draft.points(s).map(state::toLocal) }
        if (pts.isEmpty()) {
            state.pxPerM = (size.x / state.emptySpanM).toFloat()
            state.needsFit = false
            return
        }
        val x0 = pts.minOf { it.x }; val x1 = pts.maxOf { it.x }
        val y0 = pts.minOf { it.y }; val y1 = pts.maxOf { it.y }
        state.centre = Vec2((x0 + x1) / 2, (y0 + y1) / 2)
        state.pxPerM = min(size.x / max(x1 - x0, 50.0), size.y / max(y1 - y0, 50.0)).toFloat() * 0.85f
        state.needsFit = false
    }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { size = Offset(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val grab = if (pickOnly) null else handleAt(state, down.position, 28 * density, ::toScreen, density)
                    if (grab != null) {
                        // Drag a handle of the selected element: a point, its middle, rotate, width.
                        state.beginDrag(grab, state.frame.toGeo(toWorld(down.position)))
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            state.dragTo(state.frame.toGeo(toWorld(ch.position)))
                            ch.consume()
                        }
                        state.endDrag()
                        return@awaitEachGesture
                    }
                    // Pan, zoom and turn the map; a short touch without moving is a tap.
                    var moved = false
                    var travel = Offset.Zero
                    var twist = 0f
                    var turning = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.none { it.pressed }) break
                        val zoom = ev.calculateZoom()
                        val pan = ev.calculatePan()
                        val rotation = ev.calculateRotation()
                        travel += pan
                        if (ev.changes.size > 1 || travel.getDistance() > viewConfiguration.touchSlop) moved = true
                        if (!moved) continue
                        val c = ev.calculateCentroid()
                        if (zoom != 1f && c != Offset.Unspecified) {
                            val w = toWorld(c)
                            state.pxPerM = (state.pxPerM * zoom).coerceIn(if (pickOnly) 0.0005f else 0.02f, 40f)
                            state.centre = Vec2(w.x - (c.x - size.x / 2) / state.pxPerM, w.y + (c.y - size.y / 2) / state.pxPerM)
                        }
                        if (ev.changes.size > 1 && c != Offset.Unspecified) {
                            twist += rotation
                            if (!turning && abs(twist) > EditorState.ROTATE_SLOP_DEG) {
                                turning = true
                                state.turnBy(twist.toDouble(), toWorld(c))
                            } else if (turning && rotation != 0f) {
                                state.turnBy(rotation.toDouble(), toWorld(c))
                            }
                        }
                        state.centre = Vec2(state.centre.x - pan.x / state.pxPerM, state.centre.y + pan.y / state.pxPerM)
                        ev.changes.forEach { it.consume() }
                    }
                    if (turning) state.commitView()
                    if (!moved && !pickOnly) {
                        val hit = shapeAt(state, down.position, 20 * density, ::toScreen)
                        state.select(hit, if (hit == null) 0 else nearestVertex(state, hit, down.position, ::toScreen))
                    }
                }
            },
    ) {
        if (state.needsFit || state.pxPerM == 0f) fit()
        if (state.pxPerM == 0f) return@Canvas
        drawRect(Color(Palette.MAP_BG))
        val sources = TileSource.forBackground(background, state.frame.origin)
        tilesLoaded // Redraw when a tile arrives.
        val shown = drawTiles(tiles, sources, state, size, ::toScreen, ::toWorld, bitmapPaint)
        val overMap = shown.isNotEmpty()
        if (overMap && background == MapBackground.PHOTO) drawRect(Color(Palette.MAP_BG).copy(alpha = 0.25f))
        if (!pickOnly) {
            drawAirfield(state, ::toScreen, density, text, overMap)
            drawSelection(state, ::toScreen, density)
        }
        fix?.let { drawFix(it, state, ::toScreen, density) }
        if (pickOnly) drawCrosshair(size, density)
        if (overMap) drawAttribution(shown, size, density, text)
    }
}

/**
 * Map tiles under the airfield. A tile not loaded yet is replaced by a coarser one already in
 * memory, so zooming in shows a blurred map rather than holes. Returns the sources on screen.
 */
private fun DrawScope.drawTiles(
    tiles: MapTiles, sources: List<TileSource>, state: EditorState, size: Offset,
    toScreen: (Vec2) -> Offset, toWorld: (Offset) -> Vec2, paint: Paint,
): Set<TileSource> {
    if (sources.isEmpty()) {
        tiles.setWanted(emptySet())
        return emptySet()
    }
    val z = WebMercator.zoomFor(state.pxPerM.toDouble(), state.frame.origin.lat, sources.minOf { it.maxZoom })
    val n = 1 shl z
    // The map is turned to the runway axis: take the tiles under all four screen corners.
    val corners = listOf(Offset.Zero, Offset(size.x, 0f), Offset(0f, size.y), size)
        .map { WebMercator.tileXY(state.frame.toGeo(toWorld(it)), z) }
    val x0 = floor(corners.minOf { it.first }).toInt().coerceAtLeast(0)
    val x1 = floor(corners.maxOf { it.first }).toInt().coerceAtMost(n - 1)
    val y0 = floor(corners.minOf { it.second }).toInt().coerceAtLeast(0)
    val y1 = floor(corners.maxOf { it.second }).toInt().coerceAtMost(n - 1)
    if (x1 < x0 || y1 < y0 || (x1 - x0 + 1) * (y1 - y0 + 1) > 300) return emptySet()

    val onScreen = (y0..y1).flatMap { y -> (x0..x1).map { x -> x to y } }
    tiles.setWanted(onScreen.mapTo(HashSet()) { (x, y) -> tiles.key(sources, z, x, y) })

    val ready = mutableListOf<Triple<Int, Int, MapTiles.Tile>>()
    val standIns = LinkedHashMap<Triple<Int, Int, Int>, MapTiles.Tile>()
    for ((x, y) in onScreen) {
        val tile = tiles.request(sources, z, x, y)
        if (tile != null) {
            ready += Triple(x, y, tile)
            continue
        }
        for (up in 1..6) {
            if (z - up < 1) break
            val at = Triple(z - up, x shr up, y shr up)
            val t = standIns[at] ?: tiles.peek(sources, at.first, at.second, at.third) ?: continue
            standIns[at] = t
            break
        }
    }

    val m = Matrix()
    fun draw(canvas: android.graphics.Canvas, tile: MapTiles.Tile, tz: Int, x: Int, y: Int) {
        fun scr(cx: Int, cy: Int) = toScreen(state.frame.toLocal(WebMercator.corner(tz, cx, cy)))
        val nw = scr(x, y)
        val ne = scr(x + 1, y)
        val sw = scr(x, y + 1)
        val w = tile.bitmap.width.toFloat()
        val h = tile.bitmap.height.toFloat()
        m.setPolyToPoly(floatArrayOf(0f, 0f, w, 0f, 0f, h), 0, floatArrayOf(nw.x, nw.y, ne.x, ne.y, sw.x, sw.y), 0, 3)
        canvas.drawBitmap(tile.bitmap, m, paint)
    }
    drawIntoCanvas { c ->
        // Coarsest first, so finer tiles land on top.
        standIns.entries.sortedBy { it.key.first }.forEach { (k, t) -> draw(c.nativeCanvas, t, k.first, k.second, k.third) }
        ready.forEach { (x, y, t) -> draw(c.nativeCanvas, t, z, x, y) }
    }
    return (ready.map { it.third.source } + standIns.values.map { it.source }).toSet()
}

/** Where the new airfield goes: the middle of the map. */
private fun DrawScope.drawCrosshair(size: Offset, dp: Float) {
    val c = size / 2f
    val arm = 18 * dp
    val gap = 5 * dp
    for (color in listOf(Color(Palette.BG) to 5f, Color(Palette.RED) to 2.5f)) {
        val w = color.second * dp
        drawLine(color.first, Offset(c.x - arm, c.y), Offset(c.x - gap, c.y), w, StrokeCap.Round)
        drawLine(color.first, Offset(c.x + gap, c.y), Offset(c.x + arm, c.y), w, StrokeCap.Round)
        drawLine(color.first, Offset(c.x, c.y - arm), Offset(c.x, c.y - gap), w, StrokeCap.Round)
        drawLine(color.first, Offset(c.x, c.y + gap), Offset(c.x, c.y + arm), w, StrokeCap.Round)
    }
}

/** Credit for the map, required by the tile servers. */
private fun DrawScope.drawAttribution(sources: Set<TileSource>, size: Offset, dp: Float, text: Paint) {
    val label = TileSource.entries.filter { it in sources }.joinToString(" · ") { it.attribution }
    text.textSize = 10 * dp
    text.textAlign = Paint.Align.RIGHT
    drawIntoCanvas { haloText(it.nativeCanvas, label, size.x - 6 * dp, size.y - 6 * dp, text, dp) }
    text.textAlign = Paint.Align.CENTER
}

/** Text with a thin outline in the background colour, readable over a photo. */
private fun haloText(canvas: android.graphics.Canvas, s: String, x: Float, y: Float, text: Paint, dp: Float) {
    val fill = text.color
    text.style = Paint.Style.STROKE
    text.strokeWidth = 3 * dp
    text.strokeJoin = Paint.Join.ROUND
    text.color = Palette.BG
    canvas.drawText(s, x, y, text)
    text.style = Paint.Style.FILL
    text.color = fill
    canvas.drawText(s, x, y, text)
}

private fun path(points: List<Offset>, close: Boolean) = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    if (close) close()
}

/** The airfield; over a map ([overMap]) areas are see-through and outlined, so the map shows. */
private fun DrawScope.drawAirfield(state: EditorState, toScreen: (Vec2) -> Offset, dp: Float, text: Paint, overMap: Boolean) {
    val c = state.draft
    fun scr(p: List<Double>) = toScreen(state.toLocal(p))
    val k = state.pxPerM
    fun fill(argb: Int) = Color(argb).let { if (overMap) it.copy(alpha = it.alpha * 0.35f) else it }

    if (c.boundary.size >= 3) {
        val p = path(c.boundary.map(::scr), true)
        drawPath(p, fill(Palette.BOUNDARY_FILL))
        drawPath(p, Color(Palette.BOUNDARY_STROKE), style = Stroke(1.5f * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6 * dp, 4 * dp))))
    }
    c.aprons.forEach {
        val p = path(it.map(::scr), true)
        drawPath(p, fill(Palette.APRON))
        if (overMap) drawPath(p, Color(Palette.TAXIWAY), style = Stroke(1.5f * dp))
    }
    c.taxiways.forEach { t ->
        drawPath(
            path(t.path.map(::scr), false), fill(Palette.TAXIWAY),
            style = Stroke(max((t.widthM * k).toFloat(), 2 * dp), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
    c.runways.forEach { rw ->
        val grass = rw.surface == "grass"
        val p = path(rw.outline.map(::scr), true)
        drawPath(p, fill(if (grass) Palette.GRASS else Palette.PAVED))
        if (overMap) drawPath(p, Color(if (grass) Palette.GRASS_EDGE else Palette.TEXT), style = Stroke(2 * dp))
        else if (grass) drawPath(p, Color(Palette.GRASS_EDGE), style = Stroke(1 * dp))
        // Centre line and designators at each threshold.
        val a = scr(rw.ends[0].threshold)
        val b = scr(rw.ends[1].threshold)
        drawLine(Color(Palette.TEXT).copy(alpha = 0.35f), a, b, 1 * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8 * dp, 6 * dp)))
    }
    c.hangars.forEach {
        val p = path(it.map(::scr), true)
        drawPath(p, fill(Palette.HANGAR))
        drawPath(p, Color(Palette.HANGAR_EDGE), style = Stroke((if (overMap) 2f else 1f) * dp))
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
                haloText(canvas.nativeCanvas, end.ref, o.x, o.y + 4.5f * dp, text, dp)
                drawCircle(Color(Palette.TEXT), 3.5f * dp, toScreen(if (i == 0) a else b))
            }
        }
    }
}

private fun DrawScope.drawSelection(state: EditorState, toScreen: (Vec2) -> Offset, dp: Float) {
    val s = state.selected ?: return
    val pts = state.draft.points(s).map { toScreen(state.toLocal(it)) }
    val accent = Color(Palette.CIRCUIT)
    val ink = Color(Palette.TEXT)
    val paper = Color(Palette.BG)
    when (s.kind) {
        Shape.Kind.POLYGON -> drawPath(path(pts, true), accent, style = Stroke(2.5f * dp, join = StrokeJoin.Round))
        Shape.Kind.LINE -> drawPath(path(pts, false), accent, style = Stroke(2.5f * dp, join = StrokeJoin.Round))
        Shape.Kind.POINT -> Unit
    }
    val handles = handles(state, toScreen, dp)
    // The rotate handle hangs off the middle of the runway.
    val middle = handles.firstOrNull { it.first == Handle.Move }?.second
    handles.firstOrNull { it.first == Handle.Rotate }?.let { (_, o) ->
        if (middle != null) drawLine(accent, middle, o, 1.5f * dp)
    }
    for ((h, o) in handles) {
        when (h) {
            is Handle.Point -> {
                val current = h.index == state.vertex
                val r = (if (current) 11 else 8) * dp
                drawCircle(paper, r, o)
                drawCircle(if (current) accent else ink, r, o, style = Stroke((if (current) 3.5f else 2f) * dp))
            }
            is Handle.Insert -> {
                drawCircle(paper.copy(alpha = 0.85f), 7 * dp, o)
                drawCircle(accent, 7 * dp, o, style = Stroke(1.5f * dp))
                drawLine(accent, o - Offset(3.5f * dp, 0f), o + Offset(3.5f * dp, 0f), 1.5f * dp)
                drawLine(accent, o - Offset(0f, 3.5f * dp), o + Offset(0f, 3.5f * dp), 1.5f * dp)
            }
            Handle.Move -> {
                drawCircle(paper, 13 * dp, o)
                drawCircle(ink, 13 * dp, o, style = Stroke(2f * dp))
                // Four-way arrow.
                val a = 7 * dp
                val k = 2.5f * dp
                drawLine(ink, o - Offset(a, 0f), o + Offset(a, 0f), 1.8f * dp)
                drawLine(ink, o - Offset(0f, a), o + Offset(0f, a), 1.8f * dp)
                for ((d, n) in listOf(Offset(1f, 0f) to Offset(0f, 1f), Offset(-1f, 0f) to Offset(0f, 1f), Offset(0f, 1f) to Offset(1f, 0f), Offset(0f, -1f) to Offset(1f, 0f))) {
                    val tip = o + d * a
                    drawLine(ink, tip, tip - d * k + n * k, 1.8f * dp, StrokeCap.Round)
                    drawLine(ink, tip, tip - d * k - n * k, 1.8f * dp, StrokeCap.Round)
                }
            }
            Handle.Rotate -> {
                drawCircle(paper, 13 * dp, o)
                drawCircle(accent, 13 * dp, o, style = Stroke(2.5f * dp))
                // Circular arrow.
                val r = 6 * dp
                drawArc(accent, -60f, 280f, false, o - Offset(r, r), androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = Stroke(2f * dp, cap = StrokeCap.Round))
                val end = o + Offset(r * 0.5f, -r * 0.87f)
                drawLine(accent, end, end + Offset(3.5f * dp, 0.5f * dp), 2f * dp, StrokeCap.Round)
                drawLine(accent, end, end + Offset(0.5f * dp, 3.5f * dp), 2f * dp, StrokeCap.Round)
            }
            Handle.Width -> {
                drawCircle(paper, 11 * dp, o)
                drawCircle(ink, 11 * dp, o, style = Stroke(2f * dp))
                // Double arrow across the runway.
                val n = runwayNormal(state, s, toScreen) ?: Offset(0f, 1f)
                val a = 6 * dp
                val t = Offset(-n.y, n.x)
                drawLine(ink, o - n * a, o + n * a, 1.8f * dp)
                for (sgn in listOf(1f, -1f)) {
                    val tip = o + n * (a * sgn)
                    drawLine(ink, tip, tip - n * (2.5f * dp * sgn) + t * (2.5f * dp), 1.8f * dp, StrokeCap.Round)
                    drawLine(ink, tip, tip - n * (2.5f * dp * sgn) - t * (2.5f * dp), 1.8f * dp, StrokeCap.Round)
                }
            }
        }
    }
}

/** Unit vector across the selected runway, on screen. */
private fun runwayNormal(state: EditorState, s: Shape, toScreen: (Vec2) -> Offset): Offset? {
    val i = (s as? Shape.RunwayOutline)?.index ?: return null
    val (a, b) = state.draft.runways[i].ends.map { toScreen(state.toLocal(it.threshold)) }
    val d = b - a
    val len = d.getDistance().takeIf { it > 0f } ?: return null
    return Offset(-d.y / len, d.x / len)
}

/** The handles of the selected shape, with their place on screen. */
private fun handles(state: EditorState, toScreen: (Vec2) -> Offset, dp: Float): List<Pair<Handle, Offset>> {
    val s = state.selected ?: return emptyList()
    val c = state.draft
    if (s is Shape.RunwayOutline) {
        val rw = c.runways[s.index]
        val (a, b) = rw.ends.map { toScreen(state.toLocal(it.threshold)) }
        val mid = (a + b) / 2f
        val n = runwayNormal(state, s, toScreen) ?: Offset(0f, 1f)
        val half = max((rw.widthM() * state.pxPerM / 2).toFloat(), 26 * dp)
        return listOf(
            Handle.Point(0) to a,
            Handle.Point(1) to b,
            Handle.Move to mid,
            Handle.Width to mid + n * half,
            Handle.Rotate to mid - n * (half + 30 * dp),
        )
    }
    val pts = c.points(s).map { toScreen(state.toLocal(it)) }
    return buildList {
        pts.forEachIndexed { i, p -> add(Handle.Point(i) to p) }
        if (s.kind == Shape.Kind.POINT) return@buildList
        val edges = if (s.kind == Shape.Kind.POLYGON) pts.size else pts.size - 1
        for (i in 0 until edges) add(Handle.Insert(i) to (pts[i] + pts[(i + 1) % pts.size]) / 2f)
        val centre = Offset(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat())
        add(Handle.Move to centre)
    }
}

private fun DrawScope.drawFix(fix: PhoneFix, state: EditorState, toScreen: (Vec2) -> Offset, dp: Float) {
    val o = toScreen(state.frame.toLocal(fix.geo))
    fix.accuracyM?.let { drawCircle(Color(Palette.CART).copy(alpha = 0.15f), (it * state.pxPerM).toFloat().coerceAtLeast(9 * dp), o) }
    drawCircle(Color(Palette.CART_FILL), 7 * dp, o)
    drawCircle(Color(Palette.CART), 7 * dp, o, style = Stroke(3 * dp))
}

/** The handle of the selected shape under [at], if any; points win over the others when close. */
private fun handleAt(state: EditorState, at: Offset, radius: Float, toScreen: (Vec2) -> Offset, dp: Float): Handle? =
    handles(state, toScreen, dp)
        .map { (h, o) ->
            val bias = when (h) {
                is Handle.Point -> 0f
                Handle.Width, Handle.Rotate -> 4 * dp
                Handle.Move -> 6 * dp
                is Handle.Insert -> 8 * dp
            }
            h to (o - at).getDistance() + bias
        }
        .filter { it.second <= radius + 8 * dp }
        .minByOrNull { it.second }?.first

private fun nearestVertex(state: EditorState, s: Shape, at: Offset, toScreen: (Vec2) -> Offset): Int {
    // A runway's points are its two thresholds.
    val pts = (if (s is Shape.RunwayOutline) state.draft.runways[s.index].ends.map { it.threshold } else state.draft.points(s))
        .map { toScreen(state.toLocal(it)) }
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
