package fr.cvvm.picpiste.core.airfield

import fr.cvvm.picpiste.core.geo.GeoPoint
import fr.cvvm.picpiste.core.geo.LocalFrame
import fr.cvvm.picpiste.core.geo.Polygon
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.geo.angleDiffDeg
import fr.cvvm.picpiste.core.geo.normalizeDeg
import fr.cvvm.picpiste.core.i18n.Texts
import kotlin.math.abs
import kotlin.math.round
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** An airfield drawn from OpenStreetMap, with what the user should check before using it. */
/** [elevationMissing]: neither OSM nor the caller gave the field elevation. */
data class ImportResult(val config: AirfieldConfig, val warnings: List<String>, val elevationMissing: Boolean = false)

/** Something went wrong; [message] is for the screen, in the phone language. */
class AirfieldImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Builds a first airfield outline from OpenStreetMap data (an Overpass API answer).
 * OSM gives runways, taxiways, aprons, hangars and windsocks, of uneven quality:
 * runways may be drawn as outlines, centre lines or both, and their designators may be
 * missing. The result is meant to be checked and corrected in the editor.
 */
object OsmImport {
    /** OSM surface values drawn as grass. */
    private val SOFT = setOf("grass", "dirt", "ground", "earth", "unpaved", "turf", "sand", "gravel", "fine_gravel", "compacted")
    private const val MIN_RUNWAY_M = 150.0
    private const val DEFAULT_PAVED_WIDTH_M = 25.0
    private const val DEFAULT_GRASS_WIDTH_M = 50.0
    /** Hangars, aprons… further than this outside the airfield outline belong to someone else. */
    private const val NEAR_FIELD_M = 200.0
    private const val NEAR_RUNWAYS_M = 1_500.0

    /** Codes we accept: ICAO (LFPT), or a national code with digits (LF3452). */
    val CODE = Regex("^[A-Z0-9]{3,7}$")

    fun normalizeCode(code: String): String = code.trim().uppercase()

    /** Overpass QL: the aerodrome tagged with [code], and the aeroway features in or near it. */
    fun query(code: String): String {
        val c = normalizeCode(code)
        require(CODE.matches(c)) { "Code invalide" }
        val features = "\"aeroway\"~\"^(runway|taxiway|apron|hangar)$\""
        return """
            [out:json][timeout:25];
            nwr["aeroway"="aerodrome"][~"^(icao|ref)$"~"^$c$",i]->.ad;
            .ad map_to_area->.ar;
            node.ad->.adn;
            (
              way[$features](area.ar);
              way["building"="hangar"](area.ar);
              node["aeroway"="windsock"](area.ar);
              way[$features](around.ad:150);
              node["aeroway"="windsock"](around.ad:150);
              way[$features](around.adn:2500);
              node["aeroway"="windsock"](around.adn:2500);
            );
            out geom;
            .ad out geom;
        """.trimIndent()
    }

    private class Element(
        val type: String,
        val id: Long,
        val tags: Map<String, String>,
        /** Way geometry, or the node position. */
        val points: List<GeoPoint>,
        /** Relation members' geometries, with their roles. */
        val members: List<Pair<String, List<GeoPoint>>>,
    ) {
        val closed get() = points.size >= 4 && points.first() == points.last()
        fun tag(k: String) = tags[k]
    }

    /**
     * @param elevationM used when OSM has no `ele` tag on the aerodrome (from an elevation service).
     * @throws AirfieldImportException when there is no such aerodrome or no runway.
     */
    fun build(code: String, overpassJson: String, elevationM: Double? = null): ImportResult {
        val elements = parse(overpassJson)
        val c = normalizeCode(code)
        val aerodromes = elements.filter { it.tag("aeroway") == "aerodrome" }
        // A downloaded area may hold a neighbouring airfield too: prefer the one with this code.
        val aerodrome = aerodromes.firstOrNull { a -> listOf("icao", "ref", "faa", "iata").any { a.tag(it)?.uppercase() == c } }
            ?: aerodromes.firstOrNull()
            ?: throw AirfieldImportException(Texts.current.noAirfield(c))
        val warnings = mutableListOf<String>()

        val aerodromePoints = aerodrome.points + aerodrome.members.flatMap { it.second }
        val origin = aerodromePoints.takeIf { it.isNotEmpty() }?.let { pts ->
            GeoPoint(pts.map { it.lat }.average(), pts.map { it.lon }.average())
        } ?: elements.first { it.points.isNotEmpty() }.points.first()
        val frame = LocalFrame(origin, 0.0)

        val runways = buildRunways(elements, frame)
        if (runways.isEmpty()) throw AirfieldImportException(Texts.current.noRunways(c))
        if (runways.any { it.guessedRefs }) warnings += Texts.current.refsGuessed
        if (runways.any { it.guessedWidth }) warnings += Texts.current.widthUnknown

        val ele = aerodrome.tag("ele")?.let(::parseElevation)
        val elevation = ele ?: elevationM
        when {
            ele != null -> Unit
            elevationM != null -> warnings += Texts.current.elevationEstimated(elevationM.toInt())
            else -> warnings += Texts.current.elevationUnknown
        }

        val boundary = when {
            aerodrome.type == "way" && aerodrome.closed -> aerodrome.points.dropLast(1)
            aerodrome.type == "relation" -> outerRing(aerodrome.members.filter { it.first == "outer" || it.first == "" }.map { it.second })
            else -> emptyList()
        }

        // Only what belongs to this airfield: near its outline, or near its runways when it has none.
        val outline = boundary.takeIf { it.size >= 3 }?.let { b -> Polygon(b.map(frame::toLocal)) }
        val runwayPoints = runways.flatMap { r -> r.config.ends.map { frame.toLocal(it.threshold.geo()) } }
        fun onField(points: List<GeoPoint>) = points.any { g ->
            val p = frame.toLocal(g)
            outline?.containsWithin(p, NEAR_FIELD_M) ?: runwayPoints.any { it.distanceTo(p) < NEAR_RUNWAYS_M }
        }
        val ways = elements.filter { it.type == "way" && onField(it.points) }
        val aprons = ways.filter { it.closed && (it.tag("aeroway") == "apron" || it.tag("aeroway") == "taxiway") }
            .map { w -> w.points.dropLast(1).map(::latLon) }
        val taxiways = ways.filter { it.tag("aeroway") == "taxiway" && !it.closed && it.points.size >= 2 }
            .map { w -> TaxiwayConfig(w.points.map(::latLon), parseMetres(w.tag("width")) ?: 8.0) }
        val hangars = ways.filter { it.closed && (it.tag("aeroway") == "hangar" || it.tag("building") == "hangar") }
            .distinctBy { it.id }
            .map { w -> w.points.dropLast(1).map(::latLon) }
        val windsocks = elements.filter { it.type == "node" && it.tag("aeroway") == "windsock" && onField(it.points) }
            .map { latLon(it.points.single()) }

        val name = aerodrome.tag("name")?.let(::shortName) ?: c
        val config = AirfieldConfig(
            icao = c,
            name = name,
            elevationM = elevation?.let { round(it) } ?: 0.0,
            reference = latLon(origin),
            axisBearingDeg = 0.0,
            runways = runways.map { it.config },
            boundary = boundary.takeIf { it.size >= 3 }?.map(::latLon) ?: emptyList(),
            aprons = aprons,
            taxiways = taxiways,
            hangars = hangars,
            windsocks = windsocks,
            source = "OpenStreetMap contributors (ODbL)",
        ).normalized().squaredRunways()
        return ImportResult(config, warnings, elevationMissing = elevation == null)
    }

    private class Built(val config: RunwayConfig, val guessedRefs: Boolean, val guessedWidth: Boolean)

    private class Line(val el: Element, val a: Vec2, val b: Vec2) {
        val length get() = a.distanceTo(b)
        val mid get() = (a + b) * 0.5
    }

    private fun buildRunways(elements: List<Element>, frame: LocalFrame): List<Built> {
        val all = elements.filter { it.type == "way" && it.tag("aeroway") == "runway" && it.tag("runway") != "displaced_threshold" }
            .distinctBy { it.id }
        val areas = all.filter { it.closed || it.tag("area") == "yes" && it.points.size >= 4 }
            .map { it to Polygon(it.points.dropLast(1).map(frame::toLocal)) }
        val lines = all.filter { !it.closed && it.points.size >= 2 }
            .map { Line(it, frame.toLocal(it.points.first()), frame.toLocal(it.points.last())) }
            .filter { it.length >= MIN_RUNWAY_M }
            .sortedByDescending { it.length }

        val used = mutableSetOf<Long>()
        val raw = mutableListOf<Triple<Element, Pair<Vec2, Vec2>, Pair<Element?, List<Vec2>?>>>()
        // Centre lines first, each matched with the outline it runs in.
        for (line in lines) {
            val area = areas.filter { it.first.id !in used && it.second.contains(line.mid) }
                .minByOrNull { polygonArea(it.second.points) }
            if (area != null) used += area.first.id
            raw += Triple(line.el, line.a to line.b, (area?.first) to area?.second?.points)
        }
        // Outlines without a centre line: the long axis of the outline.
        for ((el, poly) in areas) {
            if (el.id in used) continue
            val axis = longAxis(poly.points) ?: continue
            if (axis.first.distanceTo(axis.second) < MIN_RUNWAY_M) continue
            raw += Triple(el, axis, el to poly.points)
        }

        val built = raw.map { (el, ends, area) ->
            val (a, b) = ends
            val tags = el.tags + (area.first?.tags ?: emptyMap()).filterKeys { it !in el.tags }
            val grass = tags["surface"] in SOFT
            val width = parseMetres(tags["width"])
            val outline = area.second ?: rectangle(a, b, width ?: if (grass) DEFAULT_GRASS_WIDTH_M else DEFAULT_PAVED_WIDTH_M)
            val headingAB = frame.bearingOf(b - a)
            val refs = parseRefs(tags["ref"])
            val (endA, endB, guessed) = assignEnds(a, b, headingAB, refs)
            Built(
                RunwayConfig(
                    id = "",
                    name = if (grass) Texts.current.grassName else Texts.current.pavedName,
                    surface = if (grass) "grass" else "paved",
                    ends = listOf(
                        RunwayEndConfig(endA.first, latLon(frame.toGeo(endA.second))),
                        RunwayEndConfig(endB.first, latLon(frame.toGeo(endB.second))),
                    ),
                    outline = outline.map { latLon(frame.toGeo(it)) },
                ),
                guessedRefs = guessed,
                guessedWidth = area.second == null && width == null,
            )
        }

        // Same name twice ("dur", "dur"): number them. Ids follow the names.
        val counts = built.groupingBy { it.config.name }.eachCount()
        val seen = mutableMapOf<String, Int>()
        val ids = mutableSetOf<String>()
        return built.map { b ->
            val n = b.config.name
            val name = if (counts.getValue(n) > 1) "$n ${seen.merge(n, 1, Int::plus)}" else n
            val id = generateSequence(1) { it + 1 }.map { if (it == 1) slug(name) else "${slug(name)}-$it" }.first { it !in ids }
            ids += id
            Built(b.config.copy(id = id, name = name), b.guessedRefs, b.guessedWidth)
        }
    }

    /**
     * Pairs the two ends with their designators. With refs like "04/22" (OSM writes "04/22" or
     * "04-22"), the end you start from when heading about 40° is the 04 threshold. Without refs, they come from the true heading.
     * A is always the lower designator.
     */
    private fun assignEnds(a: Vec2, b: Vec2, headingAB: Double, refs: Pair<String, String>?): Triple<Pair<String, Vec2>, Pair<String, Vec2>, Boolean> {
        var guessed = false
        val (first, second) = refs?.takeIf { (r1, r2) ->
            val h1 = refHeading(r1)
            val h2 = refHeading(r2)
            h1 != null && h2 != null && angleDiffDeg(h1, h2) > 150
        } ?: run {
            guessed = true
            runwayRef(headingAB) to runwayRef(headingAB + 180)
        }
        // first's threshold is where you start when flying its heading.
        val h1 = refHeading(first)!!
        val firstAtA = angleDiffDeg(h1, headingAB) <= angleDiffDeg(h1, normalizeDeg(headingAB + 180))
        val p1 = if (firstAtA) a else b
        val p2 = if (firstAtA) b else a
        val e1 = first to p1
        val e2 = second to p2
        return if (refHeading(second)!! < h1) Triple(e2, e1, guessed) else Triple(e1, e2, guessed)
    }

    /** "04/22", "04-22", "14L/32R", "09 / 27" → pair; anything else → null. */
    fun parseRefs(ref: String?): Pair<String, String>? {
        val parts = ref?.uppercase()?.split('/', '-', ';', ' ')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
        if (parts.size != 2) return null
        if (parts.any { !Regex("^\\d{1,2}[LCR]?$").matches(it) }) return null
        // "9/27" → "09", "27"
        fun pad(r: String) = if (r.takeWhile { it.isDigit() }.length == 1) "0$r" else r
        return pad(parts[0]) to pad(parts[1])
    }

    /** "182", "182 m", "597 ft", "182;183" → metres. */
    fun parseElevation(s: String): Double? {
        val first = s.split(';').first().trim().lowercase()
        val num = Regex("^-?\\d+(\\.\\d+)?").find(first)?.value?.toDoubleOrNull() ?: return null
        return if (first.contains("ft") || first.contains("'")) num * 0.3048 else num
    }

    private fun parseMetres(s: String?): Double? = s?.let(::parseElevation)?.takeIf { it in 3.0..300.0 }

    /** "Aérodrome de Saint-Martin-de-Londres" → "Saint-Martin-de-Londres". */
    private fun shortName(s: String): String =
        s.replace(Regex("^(Aérodrome|Aerodrome|Aéroport|Aeroport|Airfield|Airport)\\s+(de la |de l'|de |d'|du |des )?", RegexOption.IGNORE_CASE), "")
            .trim().ifEmpty { s }

    private fun rectangle(a: Vec2, b: Vec2, width: Double): List<Vec2> {
        val d = (b - a).normalized()
        val n = Vec2(-d.y, d.x) * (width / 2)
        return listOf(a + n, b + n, b - n, a - n)
    }

    /**
     * The middle line of the polygon's tightest rectangle (tried along each edge direction):
     * from the middle of one short side to the middle of the other.
     */
    private fun longAxis(pts: List<Vec2>): Pair<Vec2, Vec2>? {
        if (pts.size < 3) return null
        var best: Pair<Double, Pair<Vec2, Vec2>>? = null
        for (i in pts.indices) {
            val d = (pts[(i + 1) % pts.size] - pts[i]).normalized()
            if (d.length == 0.0) continue
            val n = Vec2(-d.y, d.x)
            val us = pts.map { it dot d }
            val vs = pts.map { it dot n }
            val (u0, u1, v0, v1) = listOf(us.min(), us.max(), vs.min(), vs.max())
            val area = (u1 - u0) * (v1 - v0)
            if (best != null && area >= best.first) continue
            val vm = (v0 + v1) / 2
            val um = (u0 + u1) / 2
            val axis = if (u1 - u0 >= v1 - v0) {
                (d * u0 + n * vm) to (d * u1 + n * vm)
            } else {
                (d * um + n * v0) to (d * um + n * v1)
            }
            best = area to axis
        }
        return best?.second
    }

    private fun polygonArea(pts: List<Vec2>): Double =
        abs(pts.indices.sumOf { i -> pts[i] cross pts[(i + 1) % pts.size] }) / 2

    /** Joins relation member ways end to end into the longest closed ring it can make. */
    private fun outerRing(parts: List<List<GeoPoint>>): List<GeoPoint> {
        val left = parts.filter { it.size >= 2 }.toMutableList()
        if (left.isEmpty()) return emptyList()
        // One member already closed: take the biggest such ring.
        left.filter { it.size >= 4 && it.first() == it.last() }.maxByOrNull { it.size }?.let { return it.dropLast(1) }
        val ring = left.removeAt(left.indices.maxBy { left[it].size }).toMutableList()
        while (left.isNotEmpty() && ring.first() != ring.last()) {
            val end = ring.last()
            val i = left.indexOfFirst { it.first() == end || it.last() == end }
            if (i < 0) break
            val next = left.removeAt(i)
            ring += (if (next.first() == end) next else next.reversed()).drop(1)
        }
        return if (ring.size >= 4 && ring.first() == ring.last()) ring.dropLast(1) else emptyList()
    }

    private fun parse(text: String): List<Element> {
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw AirfieldImportException(Texts.current.osmUnreadable, e)
        }
        val objects = (root["elements"] as? JsonArray ?: throw AirfieldImportException(Texts.current.osmUnreadable))
            .mapNotNull { it as? JsonObject }
        fun type(o: JsonObject) = o["type"]?.jsonPrimitive?.content ?: ""
        fun id(o: JsonObject) = o["id"]?.jsonPrimitive?.longOrNull ?: 0
        fun geometry(o: JsonObject) = (o["geometry"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::point) }

        // The OSM API lists node ids instead of coordinates (Overpass "out geom" gives coordinates).
        val nodes = objects.filter { type(it) == "node" }.associate { id(it) to point(it) }
        val wayPoints = objects.filter { type(it) == "way" }.associate { o ->
            id(o) to (geometry(o) ?: (o["nodes"] as? JsonArray)?.mapNotNull { n -> n.jsonPrimitive.longOrNull?.let { nodes[it] } } ?: emptyList())
        }
        return objects.map { o ->
            val type = type(o)
            val tags = (o["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            val points = when (type) {
                "node" -> listOfNotNull(point(o))
                "way" -> wayPoints[id(o)] ?: emptyList()
                else -> emptyList()
            }
            val members = (o["members"] as? JsonArray)?.mapNotNull { m ->
                val mo = m as? JsonObject ?: return@mapNotNull null
                val geom = (mo["geometry"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::point) }
                    ?: mo["ref"]?.jsonPrimitive?.longOrNull?.takeIf { mo["type"]?.jsonPrimitive?.content == "way" }?.let { wayPoints[it] }
                    ?: return@mapNotNull null
                (mo["role"]?.jsonPrimitive?.content ?: "") to geom
            } ?: emptyList()
            Element(type, id(o), tags, points, members)
        }
    }

    private fun point(o: JsonObject): GeoPoint? {
        val lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: return null
        val lon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: return null
        return GeoPoint(lat, lon)
    }
}
