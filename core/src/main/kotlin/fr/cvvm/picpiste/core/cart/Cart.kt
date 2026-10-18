package fr.cvvm.picpiste.core.cart

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.Runway
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.traffic.AircraftState
import fr.cvvm.picpiste.core.traffic.FlightStatus
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Tells whether the cart (the phone's GPS) is on a runway.
 *
 * Entering needs the cart inside a runway outline (plus [enterMarginM]); leaving needs it
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
    var current: Runway? = null
        private set
    private var outsideSinceMs: Long? = null

    fun update(pos: Vec2, accuracyM: Double?, timeMs: Long): Runway? {
        if (accuracyM != null && accuracyM > maxAccuracyM) return current // too vague to decide

        val cur = current
        if (cur != null) {
            if (cur.outline.containsWithin(pos, exitMarginM)) {
                outsideSinceMs = null
                return cur
            }
            val since = outsideSinceMs ?: timeMs.also { outsideSinceMs = it }
            if (timeMs - since < exitDelayMs) return cur
            current = null
            outsideSinceMs = null
        }
        current = airfield.runways.firstOrNull { it.outline.containsWithin(pos, enterMarginM) }
        return current
    }

    fun reset() {
        current = null
        outsideSinceMs = null
    }
}

enum class AlertLevel { NONE, ON_RUNWAY, DANGER }

data class CartAlert(
    val level: AlertLevel,
    val cartRunway: Runway? = null,
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
 * Spec §4.1b: off a runway, nothing. On a runway with no traffic, a red border only.
 * On a runway while someone is in the circuit, on final or taking off: banner and sound.
 */
object AlertEngine {
    private val THREATS = setOf(FlightStatus.FINAL, FlightStatus.TAKEOFF, FlightStatus.CIRCUIT)

    fun evaluate(cartRunway: Runway?, aircraft: List<AircraftState>): CartAlert {
        if (cartRunway == null) return CartAlert.NONE
        val threat = aircraft
            .filter { !it.signalLost && it.status in THREATS }
            .minWithOrNull(compareBy<AircraftState>({ priority(it.status) }, { it.etaS ?: Double.MAX_VALUE }))
            ?: return CartAlert(AlertLevel.ON_RUNWAY, cartRunway)

        val noun = threat.identity.type.label
        val where = if (threat.runway != null && threat.runwayEnd != null) " " + threat.runway.label(threat.runwayEnd) else ""
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
            cartRunway = cartRunway,
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
