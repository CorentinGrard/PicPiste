package fr.cvvm.picpiste.core.cart

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.AlertZone
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.i18n.Texts
import fr.cvvm.picpiste.core.traffic.AircraftState
import fr.cvvm.picpiste.core.traffic.FlightStatus
import fr.cvvm.picpiste.core.traffic.StatusEngine
import kotlin.math.roundToInt

/**
 * Tells which warning zones the cart (the phone's GPS) is in ([Airfield.alertZones]).
 *
 * Entering a zone needs the cart inside it (plus [enterMarginM]); leaving needs it
 * [exitMarginM] outside for [exitDelayMs], so the red border does not flicker when the
 * cart drives along the edge (phone GPS is good to about 5 m).
 */
class RunwayPresenceDetector(
    airfield: Airfield,
    private val enterMarginM: Double = 2.0,
    private val exitMarginM: Double = 8.0,
    private val exitDelayMs: Long = 2_000,
    private val maxAccuracyM: Double = 30.0,
) {
    private val all = airfield.alertZones
    private val inside = BooleanArray(all.size)
    private val outsideSince = LongArray(all.size) { -1 }

    /** The zones the cart is in now. */
    var zones: List<AlertZone> = emptyList()
        private set

    /** In at least one warning zone (the red border). */
    val onRunway: Boolean get() = zones.isNotEmpty()

    fun update(pos: Vec2, accuracyM: Double?, timeMs: Long): Boolean {
        if (accuracyM != null && accuracyM > maxAccuracyM) return onRunway // too vague to decide
        all.forEachIndexed { i, z ->
            if (inside[i]) {
                if (z.area.containsWithin(pos, exitMarginM)) {
                    outsideSince[i] = -1
                } else {
                    if (outsideSince[i] < 0) outsideSince[i] = timeMs
                    if (timeMs - outsideSince[i] >= exitDelayMs) {
                        inside[i] = false
                        outsideSince[i] = -1
                    }
                }
            } else if (z.area.containsWithin(pos, enterMarginM)) {
                inside[i] = true
            }
        }
        zones = all.filterIndexed { i, _ -> inside[i] }
        return onRunway
    }

    fun reset() {
        inside.fill(false)
        outsideSince.fill(-1)
        zones = emptyList()
    }
}

/**
 * NONE: off the runways. ON_RUNWAY: in a warning zone, nothing coming (nothing shown).
 * CAUTION: in a zone, someone in the circuit (amber banner and border, no voice): which
 * runway it will land on is not known yet.
 * DANGER: in a zone, a final or a takeoff on one of its runways (red banner, border and voice).
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

    /** Without zones: on a runway, every runway and the circuit count. */
    fun evaluate(cartOnRunway: Boolean, aircraft: List<AircraftState>): CartAlert =
        evaluate(if (cartOnRunway) listOf(AlertZone.EVERYWHERE) else emptyList(), aircraft, null)

    /**
     * The cart is in [zones]. A final or a takeoff counts when one of the zones protects its
     * runway ([Airfield.protects]); a glider in the circuit when one of them has the circuit on.
     * [airfield] null: every runway counts.
     */
    fun evaluate(zones: List<AlertZone>, aircraft: List<AircraftState>, airfield: Airfield?): CartAlert {
        if (zones.isEmpty()) return CartAlert.NONE
        val circuit = zones.any { it.circuit }
        fun matters(a: AircraftState) = when (a.status) {
            FlightStatus.FINAL, FlightStatus.TAKEOFF ->
                airfield == null || zones.any { it === AlertZone.EVERYWHERE || airfield.protects(it, a.runway) }
            FlightStatus.CIRCUIT -> circuit
            else -> false
        }
        val threat = aircraft
            .filter(::isThreat)
            .filter(::matters)
            .minWithOrNull(compareBy<AircraftState>({ priority(it.status) }, { it.etaS ?: Double.MAX_VALUE }))
            ?: return CartAlert(AlertLevel.ON_RUNWAY)

        val t = Texts.current
        val noun = threat.identity.type.label
        // The most likely runway ("23R"), said in words ("23 right"). Which zones it matters in
        // still goes by the whole group of parallel runways (Airfield.protects).
        fun what(ref: String?) = when (threat.status) {
            FlightStatus.FINAL -> t.onFinal(noun, ref)
            FlightStatus.TAKEOFF -> t.takingOff(ref)
            else -> t.inCircuit(noun)
        }
        fun lost(s: String) = if (threat.signalLost) t.signalLost(s) else s
        val title = lost(what(threat.runwayRef))
        val spoken = lost(what(threat.spokenRunwayRef))
        val etaS = threat.etaS?.takeIf { threat.status != FlightStatus.TAKEOFF }
        // Red and voice only once we know it is coming to this zone's runways: a voice for an
        // aircraft that then lands elsewhere teaches the driver to ignore it.
        val level = if (threat.status == FlightStatus.CIRCUIT) AlertLevel.CAUTION else AlertLevel.DANGER
        val who = listOfNotNull(threat.identity.label, threat.identity.registration).distinct().joinToString(" · ")
        // A silent glider's ETA is a guess that has run out once it reaches 0: show none.
        val shownEta = etaS?.takeIf { !threat.signalLost || it > 0 }
        return CartAlert(
            level = level,
            threat = threat,
            title = title,
            detail = t.clearRunway(who),
            eta = shownEta?.let(::formatEta) ?: "",
            speech = listOfNotNull(t.caution, spoken, shownEta?.let(t::spokenEta)).joinToString(". ") + ".",
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
