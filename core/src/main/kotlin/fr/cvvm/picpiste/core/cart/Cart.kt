package fr.cvvm.picpiste.core.cart

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.i18n.Texts
import fr.cvvm.picpiste.core.traffic.AircraftState
import fr.cvvm.picpiste.core.traffic.FlightStatus
import fr.cvvm.picpiste.core.traffic.StatusEngine
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

/**
 * NONE: off the runways. ON_RUNWAY: on a runway, circuit clear (red border only).
 * CAUTION: on a runway, someone in the circuit but not close (amber banner, no voice).
 * DANGER: on a runway with a glider on final, a takeoff, or a landing less than
 * [AlertEngine.DANGER_ETA_S] away (red banner and voice).
 */
enum class AlertLevel { NONE, ON_RUNWAY, CAUTION, DANGER }

data class CartAlert(
    val level: AlertLevel,
    val threat: AircraftState? = null,
    /** "Glider on final 22" */
    val title: String = "",
    /** "AB · F-CXYZ — clear the runway" */
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
 * On a runway while someone is in the circuit: an amber banner, then red with voice once the
 * landing is close, or at once for a final or a takeoff.
 */
object AlertEngine {
    private val THREATS = setOf(FlightStatus.FINAL, FlightStatus.TAKEOFF, FlightStatus.CIRCUIT)

    /** A circuit landing closer than this is a danger, not just a caution. */
    const val DANGER_ETA_S = 90.0

    /**
     * Low on final or base, OGN often loses a glider (below the receiver's horizon). One that went
     * silent while flying in the circuit still counts for this long: it is probably landing.
     */
    const val LOST_THREAT_MS = 180_000L

    /** Still worth watching: in the circuit, on final or taking off, or one that just went silent there. */
    fun isThreat(a: AircraftState): Boolean = when {
        !a.signalLost -> a.status in THREATS
        else -> isRecentlyLost(a)
    }

    /** Silent, but was flying in the circuit or on final a moment ago. */
    fun isRecentlyLost(a: AircraftState): Boolean =
        a.signalLost && a.ageMs < LOST_THREAT_MS &&
            (a.status == FlightStatus.FINAL || a.status == FlightStatus.CIRCUIT) &&
            a.fix.aglM >= StatusEngine.GROUND_AGL_M

    fun evaluate(cartOnRunway: Boolean, aircraft: List<AircraftState>): CartAlert {
        if (!cartOnRunway) return CartAlert.NONE
        val threat = aircraft
            .filter(::isThreat)
            .minWithOrNull(compareBy<AircraftState>({ priority(it.status) }, { it.etaS ?: Double.MAX_VALUE }))
            ?: return CartAlert(AlertLevel.ON_RUNWAY)

        val t = Texts.current
        val noun = threat.identity.type.label
        // Only the direction: from the air we cannot tell parallel runways apart.
        val what = when (threat.status) {
            FlightStatus.FINAL -> t.onFinal(noun, threat.direction)
            FlightStatus.TAKEOFF -> t.takingOff(threat.direction)
            else -> t.inCircuit(noun)
        }
        val title = if (threat.signalLost) t.signalLost(what) else what
        val etaS = threat.etaS?.takeIf { threat.status != FlightStatus.TAKEOFF }
        val level = when {
            threat.status != FlightStatus.CIRCUIT -> AlertLevel.DANGER
            etaS != null && etaS < DANGER_ETA_S -> AlertLevel.DANGER
            else -> AlertLevel.CAUTION
        }
        val who = listOfNotNull(threat.identity.label, threat.identity.registration).distinct().joinToString(" · ")
        // A silent glider's ETA is a guess that has run out once it reaches 0: show none.
        val shownEta = etaS?.takeIf { !threat.signalLost || it > 0 }
        return CartAlert(
            level = level,
            threat = threat,
            title = title,
            detail = t.clearRunway(who),
            eta = shownEta?.let(::formatEta) ?: "",
            speech = listOfNotNull(t.caution, title, shownEta?.let(t::spokenEta)).joinToString(". ") + ".",
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

fun formatKm(m: Double): String = String.format(Texts.current.locale, "%.1f km", m / 1000)
