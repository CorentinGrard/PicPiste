package fr.cvvm.picpiste.core.cart

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.number
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.traffic.AircraftState
import fr.cvvm.picpiste.core.traffic.FlightStatus
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Tells whether the cart (the phone's GPS) is in the runway zone: the three runways and the
 * grass between them, taken as one ([Airfield.runwayZone]).
 *
 * Entering needs the cart inside the zone (plus [enterMarginM]); leaving needs it
 * [exitMarginM] outside for [exitDelayMs], so the red border does not flicker when the
 * cart drives along the edge (phone GPS is good to about 5 m).
 */
class RunwayPresenceDetector(
    private val airfield: Airfield,
    private val enterMarginM: Double = 2.0,
    private val exitMarginM: Double = 8.0,
    private val exitDelayMs: Long = 2_000,
    private val maxAccuracyM: Double = 30.0,
) {
    var onRunway: Boolean = false
        private set
    private var outsideSinceMs: Long? = null

    fun update(pos: Vec2, accuracyM: Double?, timeMs: Long): Boolean {
        if (accuracyM != null && accuracyM > maxAccuracyM) return onRunway // too vague to decide

        if (onRunway) {
            if (airfield.inRunwayZone(pos, exitMarginM)) {
                outsideSinceMs = null
                return true
            }
            val since = outsideSinceMs ?: timeMs.also { outsideSinceMs = it }
            if (timeMs - since < exitDelayMs) return true
            outsideSinceMs = null
        }
        onRunway = airfield.inRunwayZone(pos, enterMarginM)
        return onRunway
    }

    fun reset() {
        onRunway = false
        outsideSinceMs = null
    }
}

enum class AlertLevel { NONE, ON_RUNWAY, DANGER }

data class CartAlert(
    val level: AlertLevel,
    val threat: AircraftState? = null,
    /** "Planeur en finale 30 herbe" */
    val title: String = "",
    /** "AB · F-CXYZ — dégagez la piste" */
    val detail: String = "",
    /** "20 s", "~2 min" */
    val eta: String = "",
    /** What the phone says out loud. */
    val speech: String = "",
) {
    companion object {
        val NONE = CartAlert(AlertLevel.NONE)
    }
}

/**
 * Spec §4.1b: off the runways, nothing. In the runway zone with no traffic, a red border only.
 * On a runway while someone is in the circuit, on final or taking off: banner and sound.
 */
object AlertEngine {
    private val THREATS = setOf(FlightStatus.FINAL, FlightStatus.TAKEOFF, FlightStatus.CIRCUIT)

    fun evaluate(cartOnRunway: Boolean, aircraft: List<AircraftState>): CartAlert {
        if (!cartOnRunway) return CartAlert.NONE
        val threat = aircraft
            .filter { !it.signalLost && it.status in THREATS }
            .minWithOrNull(compareBy<AircraftState>({ priority(it.status) }, { it.etaS ?: Double.MAX_VALUE }))
            ?: return CartAlert(AlertLevel.ON_RUNWAY)

        val noun = threat.identity.type.label
        // Only the direction: from the air we cannot tell which of the three parallel runways.
        val where = threat.runwayEnd?.let { " ${it.number}" } ?: ""
        val title = when (threat.status) {
            FlightStatus.FINAL -> "$noun en finale$where"
            FlightStatus.TAKEOFF -> "Décollage en cours$where"
            else -> "$noun dans le circuit"
        }
        val who = listOfNotNull(threat.identity.label, threat.identity.registration).distinct().joinToString(" · ")
        val eta = threat.etaS?.takeIf { threat.status != FlightStatus.TAKEOFF }?.let(::formatEta) ?: ""
        val spokenEta = threat.etaS?.takeIf { threat.status != FlightStatus.TAKEOFF }?.let(::spokenEta)
        return CartAlert(
            level = AlertLevel.DANGER,
            threat = threat,
            title = title,
            detail = "$who — dégagez la piste",
            eta = eta,
            speech = listOfNotNull("Attention", title, spokenEta).joinToString(". ") + ".",
        )
    }

    private fun priority(s: FlightStatus) = when (s) {
        FlightStatus.FINAL -> 0
        FlightStatus.TAKEOFF -> 1
        else -> 2
    }
}

/** "20 s" under a minute (rounded to 5 s), "~2 min" above. */
fun formatEta(s: Double): String = when {
    s < 60 -> "${((s / 5).roundToInt() * 5).coerceAtLeast(5)} s"
    else -> "~${(s / 60).roundToInt()} min"
}

fun spokenEta(s: Double): String = when {
    s < 60 -> "${((s / 5).roundToInt() * 5).coerceAtLeast(5)} secondes"
    else -> "${(s / 60).roundToInt()} minute" + if ((s / 60).roundToInt() > 1) "s" else ""
}

fun formatKm(m: Double): String = String.format(Locale.FRANCE, "%.1f km", m / 1000)
