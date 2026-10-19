package fr.cvvm.picpiste.core.airfield

import fr.cvvm.picpiste.core.geo.GeoPoint
import fr.cvvm.picpiste.core.geo.LocalFrame
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.geo.haversineM
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

    /** Closed outline (polygon), open line, or a single point. */
    val kind: Kind
        get() = when (this) {
            Boundary, is RunwayOutline, is Apron, is Hangar -> Kind.POLYGON
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
}

/** "Piste herbe", "Seuil 30 herbe", "Hangar 2"… */
fun AirfieldConfig.label(shape: Shape): String = when (shape) {
    Shape.Boundary -> "Contour du terrain"
    is Shape.RunwayOutline -> "Piste ${runways[shape.index].name}"
    is Shape.Threshold -> runways[shape.index].let { "Seuil ${it.ends[shape.end.ordinal].ref} ${it.name}" }
    is Shape.Apron -> "Parking ${shape.index + 1}"
    is Shape.Hangar -> "Hangar ${shape.index + 1}"
    is Shape.Taxiway -> "Taxiway ${shape.index + 1}"
    is Shape.Windsock -> "Manche à air ${shape.index + 1}"
    Shape.Starter -> "Starter"
}

fun AirfieldConfig.withPoints(shape: Shape, pts: List<LatLon>): AirfieldConfig = when (shape) {
    Shape.Boundary -> copy(boundary = pts)
    is Shape.RunwayOutline -> copy(runways = runways.replace(shape.index) { it.copy(outline = pts) })
    is Shape.Threshold -> copy(
        runways = runways.replace(shape.index) { rw ->
            rw.copy(ends = rw.ends.replace(shape.end.ordinal) { it.copy(threshold = pts.single()) })
        },
    )
    is Shape.Apron -> copy(aprons = aprons.replace(shape.index) { pts })
    is Shape.Hangar -> copy(hangars = hangars.replace(shape.index) { pts })
    is Shape.Taxiway -> copy(taxiways = taxiways.replace(shape.index) { it.copy(path = pts) })
    is Shape.Windsock -> copy(windsocks = windsocks.replace(shape.index) { pts.single() })
    Shape.Starter -> copy(starter = pts.single())
}

fun AirfieldConfig.movePoint(shape: Shape, index: Int, to: GeoPoint): AirfieldConfig =
    withPoints(shape, points(shape).replace(index) { latLon(to) })

/** Moves the whole shape by the same offset as point [index] going to [to]. */
fun AirfieldConfig.moveShape(shape: Shape, index: Int, to: GeoPoint): AirfieldConfig {
    val from = points(shape)[index]
    val dLat = to.lat - from[0]
    val dLon = to.lon - from[1]
    return withPoints(shape, points(shape).map { listOf(round7(it[0] + dLat), round7(it[1] + dLon)) })
}

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
    is Shape.RunwayOutline -> if (runways.size > 1) copy(runways = runways.without(shape.index)) else this
    is Shape.Threshold -> if (runways.size > 1) copy(runways = runways.without(shape.index)) else this
    is Shape.Apron -> copy(aprons = aprons.without(shape.index))
    is Shape.Hangar -> copy(hangars = hangars.without(shape.index))
    is Shape.Taxiway -> copy(taxiways = taxiways.without(shape.index))
    is Shape.Windsock -> copy(windsocks = windsocks.without(shape.index))
    Shape.Starter -> copy(starter = null)
}

fun AirfieldConfig.canDelete(shape: Shape): Boolean = when (shape) {
    is Shape.RunwayOutline, is Shape.Threshold -> runways.size > 1
    else -> true
}

/** What the "add" menu can create, at a point the user chose (the map centre or the GPS). */
enum class NewShape(val label: String) {
    RUNWAY("Piste"), HANGAR("Hangar"), APRON("Parking"), TAXIWAY("Taxiway"),
    WINDSOCK("Manche à air"), STARTER("Starter"), BOUNDARY("Contour du terrain"),
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
            val name = uniqueName("nouvelle")
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
        NewShape.HANGAR -> copy(hangars = hangars + listOf(rect(15.0, 10.0))) to Shape.Hangar(hangars.size)
        NewShape.APRON -> copy(aprons = aprons + listOf(rect(40.0, 25.0))) to Shape.Apron(aprons.size)
        NewShape.TAXIWAY -> copy(
            taxiways = taxiways + TaxiwayConfig(listOf(latLon(frame.toGeo(Vec2(-50.0, 0.0))), latLon(frame.toGeo(Vec2(50.0, 0.0))))),
        ) to Shape.Taxiway(taxiways.size)
        NewShape.WINDSOCK -> copy(windsocks = windsocks + listOf(latLon(at))) to Shape.Windsock(windsocks.size)
        NewShape.STARTER -> copy(starter = latLon(at)) to Shape.Starter
        NewShape.BOUNDARY -> copy(boundary = rect(500.0, 150.0)) to Shape.Boundary
    }
}

/** Renames a runway (its id follows, kept unique). */
fun AirfieldConfig.renameRunway(index: Int, name: String): AirfieldConfig {
    val others = copy(runways = runways.without(index))
    return copy(runways = runways.replace(index) { it.copy(name = name, id = others.uniqueRunwayId(name)) })
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
 * Ready to save: the radar axis follows the longest runway, the frame origin sits in the middle
 * of the runways, and every runway lists its lower designator first.
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
    return copy(
        formatVersion = AirfieldConfig.FORMAT_VERSION,
        icao = icao.trim().uppercase(),
        name = name.trim(),
        runways = rws,
        reference = latLon(centre),
        axisBearingDeg = round(axis * 10) / 10,
    )
}

/** Sort key of a designator: its number, then L, C, R. */
private fun refOrder(ref: String): Int =
    (ref.takeWhile { it.isDigit() }.toIntOrNull() ?: 99) * 10 + "LCR".indexOf(ref.dropWhile { it.isDigit() }.firstOrNull() ?: ' ') + 1

/**
 * What is wrong with this config, in French for the screen. Empty: it can be used.
 * Checks only what would break the app, not whether the drawing is accurate.
 */
fun AirfieldConfig.problems(): List<String> = buildList {
    fun bad(p: List<Double>) = p.size != 2 || p[0] !in -90.0..90.0 || p[1] !in -180.0..180.0 || p.any { it.isNaN() }
    if (icao.isBlank()) add("Le code du terrain est vide.")
    if (bad(reference)) add("Le point de référence est invalide.")
    if (elevationM.isNaN() || elevationM !in -500.0..5_000.0) add("L'altitude du terrain est invalide.")
    if (runways.isEmpty()) add("Il faut au moins une piste.")
    val ref = reference.takeUnless(::bad)?.geo()
    for (rw in runways) {
        if (rw.ends.size != 2 || rw.ends.any { bad(it.threshold) || it.ref.isBlank() }) {
            add("Piste ${rw.name} : il faut deux seuils avec leur numéro.")
            continue
        }
        if (rw.outline.size < 3 || rw.outline.any(::bad)) add("Piste ${rw.name} : le contour doit avoir au moins 3 points.")
        val len = haversineM(rw.ends[0].threshold.geo(), rw.ends[1].threshold.geo())
        if (len < 50) add("Piste ${rw.name} : les deux seuils sont trop proches (${len.toInt()} m).")
        if (ref != null && rw.ends.any { haversineM(ref, it.threshold.geo()) > 20_000 }) {
            add("Piste ${rw.name} : à plus de 20 km du terrain.")
        }
    }
    if (runways.map { it.id }.toSet().size != runways.size) add("Deux pistes ont le même nom.")
    if (boundary.isNotEmpty() && (boundary.size < 3 || boundary.any(::bad))) add("Le contour du terrain doit avoir au moins 3 points.")
    if ((aprons + hangars).any { it.size < 3 || it.any(::bad) }) add("Un parking ou un hangar a moins de 3 points.")
    if (taxiways.any { it.path.size < 2 || it.path.any(::bad) }) add("Un taxiway a moins de 2 points.")
    if (windsocks.any(::bad) || starter?.let(::bad) == true) add("Une manche à air ou le starter est mal placé.")
}

internal fun AirfieldConfig.uniqueRunwayId(name: String): String {
    val base = slug(name).ifEmpty { "piste" }
    val used = runways.map { it.id }.toSet()
    return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it !in used }
}

private fun AirfieldConfig.uniqueName(base: String): String {
    val used = runways.map { it.name }.toSet()
    return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in used }
}

/** "dur gauche" → "dur-gauche", "Hérbe" → "herbe". */
fun slug(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')

private inline fun <T> List<T>.replace(index: Int, f: (T) -> T): List<T> = mapIndexed { i, v -> if (i == index) f(v) else v }

private fun <T> List<T>.without(index: Int): List<T> = filterIndexed { i, _ -> i != index }
