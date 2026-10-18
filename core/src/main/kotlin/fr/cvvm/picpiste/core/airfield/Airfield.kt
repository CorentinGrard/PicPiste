package fr.cvvm.picpiste.core.airfield

import fr.cvvm.picpiste.core.geo.GeoPoint
import fr.cvvm.picpiste.core.geo.LocalFrame
import fr.cvvm.picpiste.core.geo.Polygon
import fr.cvvm.picpiste.core.geo.Vec2
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.roundToInt

/** Airfield config as stored in JSON. Points are `[lat, lon]`. */
@Serializable
data class AirfieldConfig(
    val icao: String,
    val name: String,
    val elevationM: Double,
    val reference: List<Double>,
    val axisBearingDeg: Double,
    val runways: List<RunwayConfig>,
    val boundary: List<List<Double>>,
    val aprons: List<List<List<Double>>> = emptyList(),
    val taxiways: List<TaxiwayConfig> = emptyList(),
    val hangars: List<List<List<Double>>> = emptyList(),
    val windsocks: List<List<Double>> = emptyList(),
    val starter: List<Double>? = null,
    val source: String? = null,
)

@Serializable
data class RunwayConfig(
    val id: String,
    /** How the club calls it: "herbe", "dur droite", "dur gauche". */
    val name: String,
    val surface: String,
    val threshold12: List<Double>,
    val threshold30: List<Double>,
    val outline: List<List<Double>>,
)

@Serializable
data class TaxiwayConfig(val path: List<List<Double>>, val widthM: Double = 8.0)

private val json = Json { ignoreUnknownKeys = true }

private fun List<Double>.geo() = GeoPoint(this[0], this[1])

/** Runway direction, named after its magnetic heading rounded to tens of degrees. */
enum class RunwayEnd { R12, R30 }

/** Airfield geometry in the local frame, ready for the status engine and the radar. */
class Airfield(val config: AirfieldConfig) {
    val icao get() = config.icao
    val elevationM get() = config.elevationM
    val reference = config.reference.geo()
    val frame = LocalFrame(reference, config.axisBearingDeg)

    val runways: List<Runway> = config.runways.map { Runway(it, frame) }
    val boundary = Polygon(config.boundary.map { frame.toLocal(it.geo()) })
    val aprons = config.aprons.map { a -> Polygon(a.map { frame.toLocal(it.geo()) }) }
    val hangars = config.hangars.map { h -> Polygon(h.map { frame.toLocal(it.geo()) }) }
    val taxiways = config.taxiways.map { t -> Taxiway(t.path.map { frame.toLocal(it.geo()) }, t.widthM) }
    val windsocks = config.windsocks.map { frame.toLocal(it.geo()) }
    val starter: Vec2? = config.starter?.let { frame.toLocal(it.geo()) }

    fun runway(id: String): Runway? = runways.firstOrNull { it.id == id }

    /** The runway whose centreline is closest to [p], with that lateral distance. */
    fun nearestRunway(p: Vec2): Pair<Runway, Double> =
        runways.map { it to abs(it.lateral(p)) }.minBy { it.second }

    companion object {
        fun parse(text: String): Airfield = Airfield(json.decodeFromString(AirfieldConfig.serializer(), text))

        /** Loads a bundled airfield from the core resources, e.g. `"LFNL"`. */
        fun bundled(icao: String): Airfield {
            val stream = Airfield::class.java.getResourceAsStream("/airfields/$icao.json")
                ?: error("No bundled airfield $icao")
            return parse(stream.bufferedReader().use { it.readText() })
        }
    }
}

class Taxiway(val path: List<Vec2>, val widthM: Double)

class Runway(val config: RunwayConfig, frame: LocalFrame) {
    val id get() = config.id
    val name get() = config.name
    val isGrass get() = config.surface == "grass"

    val threshold12: Vec2 = frame.toLocal(config.threshold12.geo())
    val threshold30: Vec2 = frame.toLocal(config.threshold30.geo())
    val outline = Polygon(config.outline.map { frame.toLocal(it.geo()) })

    /** Unit vector from the 12 threshold towards the 30 threshold. */
    val dir12: Vec2 = (threshold30 - threshold12).normalized()
    val length: Double = threshold12.distanceTo(threshold30)
    val heading12: Double = frame.bearingOf(dir12)
    val heading30: Double = (heading12 + 180) % 360

    /** Distance along the runway from the 12 threshold (negative before it). */
    fun along(p: Vec2): Double = (p - threshold12) dot dir12

    /** Signed distance from the centreline, positive to the left of the 12 direction. */
    fun lateral(p: Vec2): Double = dir12 cross (p - threshold12)

    fun threshold(end: RunwayEnd) = if (end == RunwayEnd.R12) threshold12 else threshold30
    fun landingDir(end: RunwayEnd) = if (end == RunwayEnd.R12) dir12 else dir12 * -1.0
    fun heading(end: RunwayEnd) = if (end == RunwayEnd.R12) heading12 else heading30

    /** "30 herbe", "12 dur gauche"… */
    fun label(end: RunwayEnd) = "${end.number} $name"

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

val RunwayEnd.number: Int get() = if (this == RunwayEnd.R12) 12 else 30

enum class RunwayThird(val label: String) { START("début de piste"), MIDDLE("milieu"), END("fin de piste") }

/** Rounds a heading to its runway number, for sanity checks (119° → 12). */
fun runwayNumber(headingDeg: Double): Int = ((headingDeg / 10).roundToInt().let { if (it == 0) 36 else it })
