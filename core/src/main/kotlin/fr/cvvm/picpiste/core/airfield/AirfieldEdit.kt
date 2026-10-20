package fr.cvvm.picpiste.core.airfield

import fr.cvvm.picpiste.core.geo.GeoPoint
import fr.cvvm.picpiste.core.geo.LocalFrame
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.geo.haversineM
import fr.cvvm.picpiste.core.geo.normalizeDeg
import fr.cvvm.picpiste.core.geo.angleDiffDeg
import fr.cvvm.picpiste.core.i18n.Texts
import java.text.Normalizer
import kotlin.math.round

/** One thing on the airfield that the editor can select and change. */
sealed interface Shape {
    data object Boundary : Shape
    data class RunwayOutline(val index: Int) : Shape
    data class Threshold(val index: Int, val end: RunwayEnd) : Shape
    data class Apron(val index: Int) : Shape
    data class Hangar(val index: Int) : Shape
    data class Taxiway(val index: Int) : Shape
    data class Windsock(val index: Int) : Shape
    data object Starter : Shape
    data class AlertZone(val index: Int) : Shape

    /** Closed outline (polygon), open line, or a single point. */
    val kind: Kind
        get() = when (this) {
            Boundary, is RunwayOutline, is Apron, is Hangar, is AlertZone -> Kind.POLYGON
            is Taxiway -> Kind.LINE
            is Threshold, is Windsock, Starter -> Kind.POINT
        }

    enum class Kind(val minPoints: Int) { POLYGON(3), LINE(2), POINT(1) }
}

/** Points are `[lat, lon]`, as in the JSON. */
typealias LatLon = List<Double>

fun latLon(p: GeoPoint): LatLon = listOf(round7(p.lat), round7(p.lon))

private fun round7(v: Double) = round(v * 1e7) / 1e7

/** Every shape of the airfield, in drawing order (big areas first, points last). */
fun AirfieldConfig.shapes(): List<Shape> = buildList {
    if (boundary.isNotEmpty()) add(Shape.Boundary)
    alertZones.indices.forEach { add(Shape.AlertZone(it)) }
    aprons.indices.forEach { add(Shape.Apron(it)) }
    taxiways.indices.forEach { add(Shape.Taxiway(it)) }
    runways.indices.forEach { add(Shape.RunwayOutline(it)) }
    hangars.indices.forEach { add(Shape.Hangar(it)) }
    runways.indices.forEach { i -> RunwayEnd.entries.forEach { add(Shape.Threshold(i, it)) } }
    windsocks.indices.forEach { add(Shape.Windsock(it)) }
    if (starter != null) add(Shape.Starter)
}

fun AirfieldConfig.points(shape: Shape): List<LatLon> = when (shape) {
    Shape.Boundary -> boundary
    is Shape.RunwayOutline -> runways[shape.index].outline
    is Shape.Threshold -> listOf(runways[shape.index].ends[shape.end.ordinal].threshold)
    is Shape.Apron -> aprons[shape.index]
    is Shape.Hangar -> hangars[shape.index]
    is Shape.Taxiway -> taxiways[shape.index].path
    is Shape.Windsock -> listOf(windsocks[shape.index])
    Shape.Starter -> listOfNotNull(starter)
    is Shape.AlertZone -> alertZones[shape.index].outline
}

fun AirfieldConfig.exists(shape: Shape): Boolean = when (shape) {
    Shape.Boundary -> boundary.isNotEmpty()
    is Shape.RunwayOutline -> shape.index in runways.indices
    is Shape.Threshold -> shape.index in runways.indices
    is Shape.Apron -> shape.index in aprons.indices
    is Shape.Hangar -> shape.index in hangars.indices
    is Shape.Taxiway -> shape.index in taxiways.indices
    is Shape.Windsock -> shape.index in windsocks.indices
    Shape.Starter -> starter != null
    is Shape.AlertZone -> shape.index in alertZones.indices
}

/** "Runway grass", "Threshold 22 grass", "Hangar 2"… in the phone language. */
fun AirfieldConfig.label(shape: Shape): String = when (shape) {
    Shape.Boundary -> Texts.current.boundary
    is Shape.RunwayOutline -> Texts.current.runway(runways[shape.index].name)
    is Shape.Threshold -> runways[shape.index].let { Texts.current.threshold(it.ends[shape.end.ordinal].ref, it.name) }
    is Shape.Apron -> Texts.current.apron(shape.index + 1)
    is Shape.Hangar -> Texts.current.hangar(shape.index + 1)
    is Shape.Taxiway -> Texts.current.taxiway(shape.index + 1)
    is Shape.Windsock -> Texts.current.windsock(shape.index + 1)
    Shape.Starter -> Texts.current.starter
    is Shape.AlertZone -> Texts.current.alertZone(shape.index + 1)
}

fun AirfieldConfig.withPoints(shape: Shape, pts: List<LatLon>): AirfieldConfig = when (shape) {
    Shape.Boundary -> copy(boundary = pts)
    // Runways stay rectangles: their outline follows the thresholds.
    is Shape.RunwayOutline -> this
    is Shape.Threshold -> copy(
        runways = runways.replace(shape.index) { rw ->
            val width = rw.widthM()
            rw.copy(ends = rw.ends.replace(shape.end.ordinal) { it.copy(threshold = pts.single()) }).squared(width)
        },
    )
    is Shape.Apron -> copy(aprons = aprons.replace(shape.index) { pts })
    is Shape.Hangar -> copy(hangars = hangars.replace(shape.index) { pts })
    is Shape.Taxiway -> copy(taxiways = taxiways.replace(shape.index) { it.copy(path = pts) })
    is Shape.Windsock -> copy(windsocks = windsocks.replace(shape.index) { pts.single() })
    Shape.Starter -> copy(starter = pts.single())
    is Shape.AlertZone -> copy(alertZones = alertZones.replace(shape.index) { it.copy(outline = pts) })
}

fun AirfieldConfig.movePoint(shape: Shape, index: Int, to: GeoPoint): AirfieldConfig =
    withPoints(shape, points(shape).replace(index) { latLon(to) })

/** Moves the whole shape by the same offset as point [index] going to [to]. */
fun AirfieldConfig.moveShape(shape: Shape, index: Int, to: GeoPoint): AirfieldConfig {
    val from = points(shape)[index]
    return translate(shape, to.lat - from[0], to.lon - from[1])
}

/** Moves the whole shape (a runway with its thresholds) by [dLat], [dLon] degrees. */
fun AirfieldConfig.translate(shape: Shape, dLat: Double, dLon: Double): AirfieldConfig {
    fun t(p: LatLon) = listOf(round7(p[0] + dLat), round7(p[1] + dLon))
    return when (shape) {
        is Shape.RunwayOutline, is Shape.Threshold -> {
            val i = (shape as? Shape.RunwayOutline)?.index ?: (shape as Shape.Threshold).index
            copy(
                runways = runways.replace(i) { rw ->
                    rw.copy(ends = rw.ends.map { it.copy(threshold = t(it.threshold)) }, outline = rw.outline.map(::t))
                },
            )
        }
        else -> withPoints(shape, points(shape).map(::t))
    }
}

/** Width of a runway (metres): its outline's area over the distance between its thresholds. */
fun RunwayConfig.widthM(): Double {
    val frame = LocalFrame(ends[0].threshold.geo(), 0.0)
    val length = frame.toLocal(ends[1].threshold.geo()).length
    val pts = outline.map { frame.toLocal(it.geo()) }
    if (pts.size < 3 || length < 1) return DEFAULT_RUNWAY_WIDTH_M
    val area = kotlin.math.abs(pts.indices.sumOf { i -> pts[i] cross pts[(i + 1) % pts.size] }) / 2
    return (area / length).coerceIn(MIN_RUNWAY_WIDTH_M, MAX_RUNWAY_WIDTH_M)
}

/** The runway as a rectangle [width] wide, from one threshold to the other. */
fun RunwayConfig.squared(width: Double = widthM()): RunwayConfig {
    val frame = LocalFrame(ends[0].threshold.geo(), 0.0)
    val a = Vec2(0.0, 0.0)
    val b = frame.toLocal(ends[1].threshold.geo())
    val dir = (b - a).normalized()
    val half = Vec2(-dir.y, dir.x) * (width.coerceIn(MIN_RUNWAY_WIDTH_M, MAX_RUNWAY_WIDTH_M) / 2)
    val corners = listOf(a - half, b - half, b + half, a + half)
    // Already that rectangle (to rounding): keep it, so loading and saving change nothing.
    if (outline.size == 4 && outline.zip(corners).all { (p, c) -> frame.toLocal(p.geo()).distanceTo(c) < 0.05 }) return this
    return copy(outline = corners.map { latLon(frame.toGeo(it)) })
}

/** Every runway as a rectangle (older files and OpenStreetMap give any outline). */
fun AirfieldConfig.squaredRunways(): AirfieldConfig = copy(runways = runways.map { it.squared() })

private fun AirfieldConfig.runwayCentre(index: Int): GeoPoint {
    val (a, b) = runways[index].ends.map { it.threshold }
    return GeoPoint((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
}

/** Turns a runway by [degrees] (clockwise, as a compass) around its middle. */
fun AirfieldConfig.rotateRunway(index: Int, degrees: Double): AirfieldConfig {
    val frame = LocalFrame(runwayCentre(index), 0.0)
    val before = runways[index]
    return copy(
        runways = runways.replace(index) { rw ->
            val width = rw.widthM()
            rw.copy(
                ends = rw.ends.map { e ->
                    val v = frame.toLocal(e.threshold.geo())
                    val turned = frame.directionOf(frame.bearingOf(v) + degrees) * v.length
                    e.copy(threshold = latLon(frame.toGeo(turned)))
                },
            ).squared(width)
        },
    ).refsFollow(index, before)
}

/**
 * Map directions worth snapping to when turning the map: north up, and every runway straight
 * up or straight across the screen.
 */
fun AirfieldConfig.mapUpSnaps(): List<Double> =
    listOf(0.0) + runways.flatMap { rw -> listOf(0.0, 90.0, 180.0, 270.0).map { normalizeDeg(rw.headingA() + it) } }

/** [upDeg] moved onto the closest of [snaps] within [toleranceDeg] (north first on a tie), else as is. */
fun snapMapUp(upDeg: Double, snaps: List<Double>, toleranceDeg: Double): Double =
    snaps.map { it to angleDiffDeg(it, upDeg) }
        .filter { it.second <= toleranceDeg }
        .minByOrNull { it.second }?.first ?: normalizeDeg(upDeg)

/** Heading (degrees) from the A threshold to the B threshold. */
private fun RunwayConfig.headingA(): Double {
    val frame = LocalFrame(ends[0].threshold.geo(), 0.0)
    return frame.bearingOf(frame.toLocal(ends[1].threshold.geo()))
}

/**
 * After a runway turned: designators that matched its old heading follow the new one
 * ("09" → "14", "09L" → "14L"). Ones the driver set otherwise are left alone.
 */
private fun AirfieldConfig.refsFollow(index: Int, before: RunwayConfig): AirfieldConfig {
    val after = runways[index]
    val oldHeadings = listOf(before.headingA(), normalizeDeg(before.headingA() + 180))
    val newHeadings = listOf(after.headingA(), normalizeDeg(after.headingA() + 180))
    val ends = after.ends.mapIndexed { i, e ->
        val number = e.ref.takeWhile { it.isDigit() }
        if (number == runwayRef(oldHeadings[i])) e.copy(ref = runwayRef(newHeadings[i]) + e.ref.drop(number.length)) else e
    }
    return copy(runways = runways.replace(index) { it.copy(ends = ends) })
}

fun AirfieldConfig.setRunwayWidth(index: Int, widthM: Double): AirfieldConfig =
    copy(runways = runways.replace(index) { it.squared(widthM) })

/** Moves one threshold; the runway keeps its width and follows, its numbers too (see [rotateRunway]). */
fun AirfieldConfig.moveRunwayEnd(index: Int, end: RunwayEnd, to: GeoPoint): AirfieldConfig =
    withPoints(Shape.Threshold(index, end), listOf(latLon(to))).refsFollow(index, runways[index])

const val DEFAULT_RUNWAY_WIDTH_M = 30.0
const val MIN_RUNWAY_WIDTH_M = 3.0
const val MAX_RUNWAY_WIDTH_M = 200.0

/**
 * Adds a point in the middle of the edge after [index] (for a line, before the last point
 * when [index] is the last one). Returns the new config and the new point's index.
 */
fun AirfieldConfig.insertPoint(shape: Shape, index: Int): Pair<AirfieldConfig, Int> {
    val pts = points(shape)
    if (shape.kind == Shape.Kind.POINT || pts.isEmpty()) return this to index
    val (a, b) = when {
        shape.kind == Shape.Kind.POLYGON -> index to (index + 1) % pts.size
        index < pts.lastIndex -> index to index + 1
        else -> index - 1 to index
    }
    val mid = listOf(round7((pts[a][0] + pts[b][0]) / 2), round7((pts[a][1] + pts[b][1]) / 2))
    val at = if (b == 0) pts.size else b
    return withPoints(shape, pts.toMutableList().apply { add(at, mid) }) to at
}

/** Removes one point; does nothing when the shape would have too few points left. */
fun AirfieldConfig.deletePoint(shape: Shape, index: Int): AirfieldConfig {
    val pts = points(shape)
    if (pts.size <= shape.kind.minPoints) return this
    return withPoints(shape, pts.filterIndexed { i, _ -> i != index })
}

fun AirfieldConfig.canDeletePoint(shape: Shape): Boolean = points(shape).size > shape.kind.minPoints

/** Removes a shape. A threshold removes its whole runway; the last runway cannot be removed. */
fun AirfieldConfig.delete(shape: Shape): AirfieldConfig = when (shape) {
    Shape.Boundary -> copy(boundary = emptyList())
    is Shape.RunwayOutline -> deleteRunway(shape.index)
    is Shape.Threshold -> deleteRunway(shape.index)
    is Shape.Apron -> copy(aprons = aprons.without(shape.index))
    is Shape.Hangar -> copy(hangars = hangars.without(shape.index))
    is Shape.Taxiway -> copy(taxiways = taxiways.without(shape.index))
    is Shape.Windsock -> copy(windsocks = windsocks.without(shape.index))
    Shape.Starter -> copy(starter = null)
    is Shape.AlertZone -> copy(alertZones = alertZones.without(shape.index))
}

/** Removes a runway (never the last one) and its links from the warning zones. */
private fun AirfieldConfig.deleteRunway(index: Int): AirfieldConfig {
    if (runways.size <= 1) return this
    val id = runways[index].id
    return copy(
        runways = runways.without(index),
        alertZones = alertZones.map { z -> z.copy(runways = z.runways - id) },
    )
}

fun AirfieldConfig.canDelete(shape: Shape): Boolean = when (shape) {
    is Shape.RunwayOutline, is Shape.Threshold -> runways.size > 1
    else -> true
}

/** What the "add" menu can create, at a point the user chose (the map centre or the GPS). */
enum class NewShape {
    RUNWAY, BUILDING, APRON, TAXIWAY, WINDSOCK, BOUNDARY, ALERT_ZONE;

    val label: String get() = Texts.current.newShape(this)
}

/** Adds a new shape centred on [at], aligned with the main runway. Returns the config and the new shape. */
fun AirfieldConfig.add(kind: NewShape, at: GeoPoint): Pair<AirfieldConfig, Shape> {
    val frame = LocalFrame(at, axisBearingDeg)
    fun rect(halfX: Double, halfY: Double) = listOf(
        Vec2(-halfX, -halfY), Vec2(halfX, -halfY), Vec2(halfX, halfY), Vec2(-halfX, halfY),
    ).map { latLon(frame.toGeo(it)) }
    return when (kind) {
        NewShape.RUNWAY -> {
            val refA = runwayRef(axisBearingDeg)
            val refB = runwayRef(axisBearingDeg + 180)
            val (a, b) = if (refA <= refB) (refA to Vec2(-300.0, 0.0)) to (refB to Vec2(300.0, 0.0))
            else (refB to Vec2(300.0, 0.0)) to (refA to Vec2(-300.0, 0.0))
            val name = uniqueName(Texts.current.newRunwayName)
            val rw = RunwayConfig(
                id = uniqueRunwayId(name),
                name = name,
                surface = "grass",
                ends = listOf(
                    RunwayEndConfig(a.first, latLon(frame.toGeo(a.second))),
                    RunwayEndConfig(b.first, latLon(frame.toGeo(b.second))),
                ),
                outline = rect(300.0, 20.0),
            )
            copy(runways = runways + rw) to Shape.RunwayOutline(runways.size)
        }
        // Any building (hangar, club house…): stored with the hangars, as in older files.
        NewShape.BUILDING -> copy(hangars = hangars + listOf(rect(15.0, 10.0))) to Shape.Hangar(hangars.size)
        NewShape.APRON -> copy(aprons = aprons + listOf(rect(40.0, 25.0))) to Shape.Apron(aprons.size)
        NewShape.TAXIWAY -> copy(
            taxiways = taxiways + TaxiwayConfig(listOf(latLon(frame.toGeo(Vec2(-50.0, 0.0))), latLon(frame.toGeo(Vec2(50.0, 0.0))))),
        ) to Shape.Taxiway(taxiways.size)
        NewShape.WINDSOCK -> copy(windsocks = windsocks + listOf(latLon(at))) to Shape.Windsock(windsocks.size)
        NewShape.BOUNDARY -> copy(boundary = rect(500.0, 150.0)) to Shape.Boundary
        // Around the nearest runway and under its finals, linked to it.
        NewShape.ALERT_ZONE -> {
            val rw = runways.minByOrNull { r -> r.ends.minOf { haversineM(at, it.threshold.geo()) } }
            val zone = if (rw == null) {
                AlertZoneConfig(rect(150.0, 60.0))
            } else {
                AlertZoneConfig(rw.zoneOutline(Airfield.APPROACH_ZONE_M), listOf(rw.id))
            }
            copy(alertZones = alertZones + zone) to Shape.AlertZone(alertZones.size)
        }
    }
}

/**
 * An airfield drawn by hand (no ICAO code, or not in OpenStreetMap): one 600 m grass runway
 * centred on [at], east–west, for the driver to move onto the real one in the editor.
 */
fun newAirfield(code: String, name: String, at: GeoPoint, elevationM: Double): AirfieldConfig =
    AirfieldConfig(
        icao = code, name = name.trim(), elevationM = elevationM,
        reference = latLon(at), axisBearingDeg = 90.0, runways = emptyList(), source = "manual",
    ).add(NewShape.RUNWAY, at).first.normalized()

/** A code for an airfield without one, from its name ("Les Mées" → "LESMEES"), not in [taken]. */
fun codeFromName(name: String, taken: Set<String>): String {
    val base = slug(name).replace("-", "").uppercase().take(8).ifEmpty { "TERRAIN" }
    return generateSequence(1) { it + 1 }.map { if (it == 1) base else base.take(7) + it }.first { it !in taken }
}

/** Renames a runway (its id follows, kept unique). */
fun AirfieldConfig.renameRunway(index: Int, name: String): AirfieldConfig {
    val others = copy(runways = runways.without(index))
    val oldId = runways[index].id
    val newId = others.uniqueRunwayId(name)
    return copy(
        runways = runways.replace(index) { it.copy(name = name, id = newId) },
        // The warning zones follow the runway's new id.
        alertZones = alertZones.map { z -> z.copy(runways = z.runways.map { if (it == oldId) newId else it }) },
    )
}

/** Which runways a warning zone protects, and whether gliders in the circuit warn there. */
fun AirfieldConfig.setZone(index: Int, runwayIds: List<String>, circuit: Boolean) =
    copy(alertZones = alertZones.replace(index) { it.copy(runways = runwayIds, circuit = circuit) })

/** The runway rectangle stretched [m] beyond each threshold and [sideM] on each side. */
fun RunwayConfig.zoneOutline(m: Double, sideM: Double = 10.0): List<LatLon> {
    val frame = LocalFrame(ends[0].threshold.geo(), 0.0)
    val b = frame.toLocal(ends[1].threshold.geo())
    val dir = b.normalized()
    val half = Vec2(-dir.y, dir.x) * (widthM() / 2 + sideM)
    val a0 = dir * -m
    val b1 = b + dir * m
    return listOf(a0 - half, b1 - half, b1 + half, a0 + half).map { latLon(frame.toGeo(it)) }
}

fun AirfieldConfig.setRunwaySurface(index: Int, grass: Boolean) =
    copy(runways = runways.replace(index) { it.copy(surface = if (grass) "grass" else "paved") })

fun AirfieldConfig.setRunwayRefs(index: Int, refA: String, refB: String) =
    copy(
        runways = runways.replace(index) { rw ->
            rw.copy(ends = listOf(rw.ends[0].copy(ref = refA.trim().uppercase()), rw.ends[1].copy(ref = refB.trim().uppercase())))
        },
    )

/**
 * Map orientation with a runway along [axisDeg] across the screen: of the two, the one closest
 * to [nearDeg] (north for a new airfield, the current orientation when following an edit).
 */
fun runwayAcrossUpDeg(axisDeg: Double, nearDeg: Double = 0.0): Double =
    listOf(axisDeg - 90, axisDeg + 90).map { round(normalizeDeg(it) * 10) / 10 }.minBy { angleDiffDeg(it, nearDeg) }

/** The map shows the main runway across the screen (set at creation, or chosen in the editor). */
private fun AirfieldConfig.mapFollowsRunway(): Boolean =
    runways.isNotEmpty() && (angleDiffDeg(mapUpDeg, axisBearingDeg - 90) < 0.5 || angleDiffDeg(mapUpDeg, axisBearingDeg + 90) < 0.5)

/**
 * Ready to save: the radar axis follows the longest runway, the frame origin sits in the middle
 * of the runways, and every runway lists its lower designator first. A map that showed the main
 * runway across the screen keeps doing so when the runway is turned.
 */
fun AirfieldConfig.normalized(): AirfieldConfig {
    val rws = runways.map { rw ->
        val a = rw.ends[0]
        val b = rw.ends[1]
        if (refOrder(b.ref) < refOrder(a.ref)) rw.copy(ends = listOf(b, a)) else rw
    }
    val main = rws.maxBy { haversineM(it.ends[0].threshold.geo(), it.ends[1].threshold.geo()) }
    val ends = rws.flatMap { r -> r.ends.map { it.threshold } }
    val centre = GeoPoint(ends.map { it[0] }.average(), ends.map { it[1] }.average())
    val axisFrame = LocalFrame(main.ends[0].threshold.geo(), 0.0)
    val axis = axisFrame.bearingOf(axisFrame.toLocal(main.ends[1].threshold.geo()))
    val ids = rws.map { it.id }.toSet()
    return copy(
        formatVersion = AirfieldConfig.FORMAT_VERSION,
        alertZones = alertZones.map { z -> z.copy(runways = z.runways.filter { it in ids }) },
        icao = icao.trim().uppercase(),
        name = name.trim(),
        runways = rws,
        reference = latLon(centre),
        axisBearingDeg = round(axis * 10) / 10,
        mapUpDeg = if (mapFollowsRunway()) runwayAcrossUpDeg(axis, mapUpDeg) else mapUpDeg,
    )
}

/** Sort key of a designator: its number, then L, C, R. */
private fun refOrder(ref: String): Int =
    (ref.takeWhile { it.isDigit() }.toIntOrNull() ?: 99) * 10 + "LCR".indexOf(ref.dropWhile { it.isDigit() }.firstOrNull() ?: ' ') + 1

/**
 * What is wrong with this config, for the screen (phone language). Empty: it can be used.
 * Checks only what would break the app, not whether the drawing is accurate.
 */
fun AirfieldConfig.problems(): List<String> = buildList {
    fun bad(p: List<Double>) = p.size != 2 || p[0] !in -90.0..90.0 || p[1] !in -180.0..180.0 || p.any { it.isNaN() }
    val t = Texts.current
    if (icao.isBlank()) add(t.emptyCode)
    if (bad(reference)) add(t.badReference)
    if (elevationM.isNaN() || elevationM !in -500.0..5_000.0) add(t.badElevation)
    if (runways.isEmpty()) add(t.noRunway)
    val ref = reference.takeUnless(::bad)?.geo()
    for (rw in runways) {
        if (rw.ends.size != 2 || rw.ends.any { bad(it.threshold) || it.ref.isBlank() }) {
            add(t.runwayNeedsThresholds(rw.name))
            continue
        }
        if (rw.outline.size < 3 || rw.outline.any(::bad)) add(t.runwayOutlineTooSmall(rw.name))
        val len = haversineM(rw.ends[0].threshold.geo(), rw.ends[1].threshold.geo())
        if (len < 50) add(t.thresholdsTooClose(rw.name, len.toInt()))
        if (ref != null && rw.ends.any { haversineM(ref, it.threshold.geo()) > 20_000 }) {
            add(t.runwayTooFar(rw.name))
        }
    }
    if (runways.map { it.id }.toSet().size != runways.size) add(t.duplicateRunwayNames)
    if (boundary.isNotEmpty() && (boundary.size < 3 || boundary.any(::bad))) add(t.boundaryTooSmall)
    if ((aprons + hangars + alertZones.map { it.outline }).any { it.size < 3 || it.any(::bad) }) add(t.polygonTooSmall)
    if (taxiways.any { it.path.size < 2 || it.path.any(::bad) }) add(t.taxiwayTooShort)
    if (windsocks.any(::bad) || starter?.let(::bad) == true) add(t.misplacedPoint)
}

internal fun AirfieldConfig.uniqueRunwayId(name: String): String {
    val base = slug(name).ifEmpty { "runway" }
    val used = runways.map { it.id }.toSet()
    return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it !in used }
}

private fun AirfieldConfig.uniqueName(base: String): String {
    val used = runways.map { it.name }.toSet()
    return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in used }
}

/** "dur nord" → "dur-nord", "Hérbe" → "herbe". */
fun slug(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')

private inline fun <T> List<T>.replace(index: Int, f: (T) -> T): List<T> = mapIndexed { i, v -> if (i == index) f(v) else v }

private fun <T> List<T>.without(index: Int): List<T> = filterIndexed { i, _ -> i != index }
