package fr.cvvm.picpiste.core.airfield

import fr.cvvm.picpiste.core.geo.GeoPoint
import fr.cvvm.picpiste.core.geo.LocalFrame
import fr.cvvm.picpiste.core.geo.Polygon
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.geo.angleDiffDeg
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Airfield config as stored in JSON: bundled in the app, built from OpenStreetMap,
 * edited on the phone, shared between drivers as a file. Points are `[lat, lon]`.
 */
@Serializable
data class AirfieldConfig(
    val formatVersion: Int = FORMAT_VERSION,
    val icao: String,
    val name: String,
    val elevationM: Double,
    /** Origin of the local frame, near the middle of the runways. */
    val reference: List<Double>,
    /** True bearing of the main runway, from its A end to its B end: the radar's x axis. */
    val axisBearingDeg: Double,
    val runways: List<RunwayConfig>,
    /** Airfield outline. Empty: a box around the runways is used. */
    val boundary: List<List<Double>> = emptyList(),
    val aprons: List<List<List<Double>>> = emptyList(),
    val taxiways: List<TaxiwayConfig> = emptyList(),
    val hangars: List<List<List<Double>>> = emptyList(),
    val windsocks: List<List<Double>> = emptyList(),
    val starter: List<Double>? = null,
    val source: String? = null,
) {
    companion object {
        const val FORMAT_VERSION = 2
    }
}

@Serializable
data class RunwayConfig(
    val id: String,
    /** How the club calls it: "herbe", "dur droite", "dur gauche". */
    val name: String,
    /** "grass" or "paved". */
    val surface: String,
    /** The two ends, A first: A is the lower designator ("12" before "30"). */
    val ends: List<RunwayEndConfig>,
    val outline: List<List<Double>>,
)

/** [ref] is the designator painted on the runway ("12", "30", "14L"); [threshold] is where it starts. */
@Serializable
data class RunwayEndConfig(val ref: String, val threshold: List<Double>)

@Serializable
data class TaxiwayConfig(val path: List<List<Double>>, val widthM: Double = 8.0)

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    prettyPrintIndent = " "
}

internal fun List<Double>.geo() = GeoPoint(this[0], this[1])

/** The two ends of a runway. A has the lower designator (12 at LFNL), B the higher (30). */
enum class RunwayEnd { A, B }

/** Airfield geometry in the local frame, ready for the status engine and the radar. */
class Airfield(val config: AirfieldConfig) {
    val icao get() = config.icao
    val name get() = config.name
    val elevationM get() = config.elevationM
    val reference = config.reference.geo()
    val frame = LocalFrame(reference, config.axisBearingDeg)

    val runways: List<Runway> = config.runways.map { Runway(it, frame) }
    val aprons = config.aprons.map { a -> Polygon(a.map { frame.toLocal(it.geo()) }) }
    val hangars = config.hangars.map { h -> Polygon(h.map { frame.toLocal(it.geo()) }) }
    val taxiways = config.taxiways.map { t -> Taxiway(t.path.map { frame.toLocal(it.geo()) }, t.widthM) }
    val windsocks = config.windsocks.map { frame.toLocal(it.geo()) }
    val starter: Vec2? = config.starter?.let { frame.toLocal(it.geo()) }

    val boundary: Polygon = config.boundary.takeIf { it.size >= 3 }
        ?.let { b -> Polygon(b.map { frame.toLocal(it.geo()) }) }
        ?: boxAround(runways.flatMap { it.outline.points }, 60.0)

    /** Middle of all the runways: the radar is centred on it. */
    val centre: Vec2 = runways.map { (it.thresholdA + it.thresholdB) * 0.5 }
        .reduce { a, b -> a + b } * (1.0 / runways.size)

    /**
     * Where the cart counts as "on the runway": every runway, plus the strips of grass
     * between parallel runways next to each other. Crossing from the south edge of dur gauche
     * to the north edge of herbe at LFNL is one single runway crossing, not three.
     */
    val runwayZone: List<Polygon> = buildList {
        runways.forEach { add(it.outline) }
        // Strips: only between runways along the frame axis (the main runway's direction).
        val alongAxis = runways.filter { angleDiffDeg(it.headingA, config.axisBearingDeg) < 10 }
        val byY = alongAxis.map { rw ->
            val xs = rw.outline.points.map { it.x }
            val ys = rw.outline.points.map { it.y }
            doubleArrayOf(xs.min(), xs.max(), ys.min(), ys.max())
        }.sortedBy { it[2] }
        for ((low, high) in byY.zipWithNext()) {
            val x0 = maxOf(low[0], high[0])
            val x1 = minOf(low[1], high[1])
            val y0 = low[3]
            val y1 = high[2]
            if (x1 > x0 && y1 > y0 && y1 - y0 < MAX_STRIP_M) {
                add(Polygon(listOf(Vec2(x0, y0), Vec2(x1, y0), Vec2(x1, y1), Vec2(x0, y1))))
            }
        }
    }

    fun inRunwayZone(p: Vec2, marginM: Double = 0.0): Boolean = runwayZone.any { it.containsWithin(p, marginM) }

    fun runway(id: String): Runway? = runways.firstOrNull { it.id == id }

    /** The runway whose centreline is closest to [p], with that lateral distance. */
    fun nearestRunway(p: Vec2): Pair<Runway, Double> =
        runways.map { it to abs(it.lateral(p)) }.minBy { it.second }

    companion object {
        /** Grass between two parallel runways wider than this is not part of the runway zone. */
        const val MAX_STRIP_M = 150.0

        fun parse(text: String): Airfield = Airfield(decode(text))

        /** Reads a config file. Throws [IllegalArgumentException] with a French message when it is not usable. */
        fun decode(text: String): AirfieldConfig {
            val config = try {
                json.decodeFromString(AirfieldConfig.serializer(), text)
            } catch (e: Exception) {
                throw IllegalArgumentException("Ce fichier n'est pas un terrain PicPiste.", e)
            }
            require(config.formatVersion <= AirfieldConfig.FORMAT_VERSION) {
                "Ce terrain vient d'une version plus récente de PicPiste : mettez l'app à jour."
            }
            val problems = config.problems()
            require(problems.isEmpty()) { problems.joinToString("\n") }
            return config
        }

        fun encode(config: AirfieldConfig): String = json.encodeToString(AirfieldConfig.serializer(), config) + "\n"

        /** Loads a bundled airfield from the core resources, e.g. `"LFNL"`. */
        fun bundled(icao: String): Airfield = Airfield(bundledConfig(icao))

        fun bundledConfig(icao: String): AirfieldConfig {
            val stream = Airfield::class.java.getResourceAsStream("/airfields/$icao.json")
                ?: error("No bundled airfield $icao")
            return decode(stream.bufferedReader().use { it.readText() })
        }

        private fun boxAround(points: List<Vec2>, marginM: Double): Polygon {
            val x0 = points.minOf { it.x } - marginM
            val x1 = points.maxOf { it.x } + marginM
            val y0 = points.minOf { it.y } - marginM
            val y1 = points.maxOf { it.y } + marginM
            return Polygon(listOf(Vec2(x0, y0), Vec2(x1, y0), Vec2(x1, y1), Vec2(x0, y1)))
        }
    }
}

class Taxiway(val path: List<Vec2>, val widthM: Double)

class Runway(val config: RunwayConfig, frame: LocalFrame) {
    val id get() = config.id
    val name get() = config.name
    val isGrass get() = config.surface == "grass"

    val thresholdA: Vec2 = frame.toLocal(config.ends[0].threshold.geo())
    val thresholdB: Vec2 = frame.toLocal(config.ends[1].threshold.geo())
    val outline = Polygon(config.outline.map { frame.toLocal(it.geo()) })

    /** Unit vector from the A threshold towards the B threshold (the A landing direction). */
    val dirA: Vec2 = (thresholdB - thresholdA).normalized()
    val length: Double = thresholdA.distanceTo(thresholdB)
    val headingA: Double = frame.bearingOf(dirA)
    val headingB: Double = (headingA + 180) % 360

    /** Distance along the runway from the A threshold (negative before it). */
    fun along(p: Vec2): Double = (p - thresholdA) dot dirA

    /** Signed distance from the centreline, positive to the left of the A direction. */
    fun lateral(p: Vec2): Double = dirA cross (p - thresholdA)

    fun threshold(end: RunwayEnd) = if (end == RunwayEnd.A) thresholdA else thresholdB
    fun landingDir(end: RunwayEnd) = if (end == RunwayEnd.A) dirA else dirA * -1.0
    fun heading(end: RunwayEnd) = if (end == RunwayEnd.A) headingA else headingB

    /** Designator of [end]: "12", "30", "14L". */
    fun ref(end: RunwayEnd): String = config.ends[end.ordinal].ref

    /**
     * Direction only, without the L/C/R letter: "14" for "14L". From the air we cannot tell
     * parallel runways apart, so finals and takeoffs say "Finale 14", never "Finale 14L".
     */
    fun direction(end: RunwayEnd): String = directionOf(ref(end))

    /** "30 herbe", "12 dur gauche"… (where it landed: known from the position on the ground). */
    fun label(end: RunwayEnd) = "${ref(end)} $name"

    /** The end an aircraft rolling or flying along [trackDeg] is heading for. */
    fun endFromTrack(trackDeg: Double): RunwayEnd =
        if (angleDiffDeg(trackDeg, headingA) <= 90) RunwayEnd.A else RunwayEnd.B

    /** Which third of the runway [p] is in, seen from an aircraft landing towards [end]. */
    fun third(p: Vec2, end: RunwayEnd): RunwayThird {
        val fromThreshold = (p - threshold(end)) dot landingDir(end)
        val f = (fromThreshold / length).coerceIn(0.0, 1.0)
        return when {
            f < 1.0 / 3 -> RunwayThird.START
            f < 2.0 / 3 -> RunwayThird.MIDDLE
            else -> RunwayThird.END
        }
    }
}

enum class RunwayThird(val label: String) { START("début de piste"), MIDDLE("milieu"), END("fin de piste") }

/** Rounds a heading to its runway number, for sanity checks (119° → 12). */
fun runwayNumber(headingDeg: Double): Int = ((headingDeg / 10).roundToInt().let { if (it == 0) 36 else it })

/** Two-digit designator for a heading: 119° → "12", 32° → "03". */
fun runwayRef(headingDeg: Double): String = runwayNumber(headingDeg).toString().padStart(2, '0')

/** "14L" → "14", "05" → "05". */
fun directionOf(ref: String): String = ref.takeWhile { it.isDigit() }.ifEmpty { ref }

/** Heading a designator stands for, in degrees: "14L" → 140. Null when it has no number. */
fun refHeading(ref: String): Double? = ref.takeWhile { it.isDigit() }.toIntOrNull()?.takeIf { it in 1..36 }?.times(10.0)
