package fr.cvvm.picpiste.core.traffic

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.Runway
import fr.cvvm.picpiste.core.airfield.RunwayEnd
import fr.cvvm.picpiste.core.aprs.AircraftType
import fr.cvvm.picpiste.core.geo.angleDiffDeg
import kotlin.math.abs
import kotlin.math.max

data class Classification(
    val status: FlightStatus,
    val runway: Runway? = null,
    val runwayEnd: RunwayEnd? = null,
    val etaS: Double? = null,
)

/**
 * Turns a track into a status. Thresholds follow the spec (§4.1) and are deliberately
 * simple: they are meant to be tuned by replaying real days.
 */
class StatusEngine(private val airfield: Airfield) {
    companion object {
        const val GROUND_AGL_M = 25.0
        const val STOPPED_KMH = 25.0
        const val TAKEOFF_MIN_KMH = 40.0
        const val CLIMBING_MS = 0.8
        const val FINAL_MAX_AGL_M = 200.0
        const val FINAL_MAX_DIST_M = 2_500.0
        const val FINAL_MAX_TRACK_DIFF = 30.0
        const val CIRCUIT_MAX_DIST_M = 3_000.0
        const val CIRCUIT_MAX_AGL_M = 350.0
        const val RUNWAY_MARGIN_M = 15.0
        const val TOW_MAX_DIST_M = 300.0
        const val TOW_MAX_ALT_DIFF_M = 120.0
    }

    /**
     * @param history earlier fixes of the same aircraft, oldest first (without [fix]).
     * @param previous status before this fix.
     * @param tugs latest fixes of tow planes currently tracked.
     */
    fun classify(
        fix: Fix,
        history: List<Fix>,
        previous: FlightStatus?,
        type: AircraftType,
        tugs: List<Fix>,
    ): Classification {
        val gs = fix.groundSpeedKmh
        val nearGround = fix.aglM < GROUND_AGL_M
        val onRunway = airfield.runways.firstOrNull { it.outline.containsWithin(fix.pos, RUNWAY_MARGIN_M) }
        val wasFlying = previous?.isAirborne == true

        if (nearGround && gs < STOPPED_KMH) {
            val landed = wasFlying || previous == FlightStatus.LANDED
            return Classification(if (landed) FlightStatus.LANDED else FlightStatus.ON_GROUND)
        }
        if (nearGround && onRunway != null) {
            val end = endFromTrack(onRunway, fix.trackDeg)
            // Rolling on a runway: landing roll if it was flying, else a takeoff.
            return when {
                wasFlying -> Classification(FlightStatus.FINAL, onRunway, end, 0.0)
                // A glider pulled back to the starter by the cart rolls slower than a takeoff.
                gs < TAKEOFF_MIN_KMH && previous != FlightStatus.TAKEOFF ->
                    Classification(if (previous == FlightStatus.LANDED) FlightStatus.LANDED else FlightStatus.ON_GROUND)
                else -> Classification(FlightStatus.TAKEOFF, onRunway, end)
            }
        }
        if (nearGround && !wasFlying) {
            return Classification(FlightStatus.ON_GROUND) // fast on the ground off the runways: towed or taxiing
        }
        if (nearGround) {
            // Low and fast but not over a runway: short final, or rolling out just off the mapped outline.
            finalApproach(fix, fix.climbMs)?.let { return it }
            if (previous == FlightStatus.FINAL) {
                val (rw, _) = airfield.nearestRunway(fix.pos)
                return Classification(FlightStatus.FINAL, rw, endFromTrack(rw, fix.trackDeg), 0.0)
            }
        }

        val climb = fix.climbMs ?: derivedClimb(fix, history)
        if (climb != null && climb > CLIMBING_MS) {
            if (type == AircraftType.TOW_PLANE && previous in setOf(null, FlightStatus.TAKEOFF, FlightStatus.TOW, FlightStatus.ON_GROUND)) {
                return Classification(FlightStatus.TOW)
            }
            if (type != AircraftType.TOW_PLANE && tugs.any { isTowing(fix, it) }) {
                return Classification(FlightStatus.TOW)
            }
            if (previous == FlightStatus.TAKEOFF && fix.aglM < 150) {
                return Classification(FlightStatus.TAKEOFF, null, null)
            }
        }

        finalApproach(fix, climb)?.let { return it }

        val dist = fix.pos.length
        if (dist < CIRCUIT_MAX_DIST_M && fix.aglM < CIRCUIT_MAX_AGL_M) {
            return Classification(FlightStatus.CIRCUIT, etaS = circuitEta(fix))
        }
        return Classification(FlightStatus.AIRBORNE)
    }

    private fun finalApproach(fix: Fix, climb: Double?): Classification? {
        if (fix.aglM > FINAL_MAX_AGL_M) return null
        if (climb != null && climb > 0.5) return null
        val track = fix.trackDeg ?: return null
        var best: Classification? = null
        var bestLateral = Double.MAX_VALUE
        for (rw in airfield.runways) for (end in RunwayEnd.entries) {
            if (angleDiffDeg(track, rw.heading(end)) > FINAL_MAX_TRACK_DIFF) continue
            val rel = fix.pos - rw.threshold(end)
            val dir = rw.landingDir(end)
            val s = rel dot dir // negative before the threshold
            if (s < -FINAL_MAX_DIST_M || s > rw.length * 0.6) continue
            val lateral = abs(dir cross rel)
            val maxLateral = 120 + max(0.0, -s) * 0.27 // about ±15° cone from the threshold
            if (lateral > maxLateral || lateral >= bestLateral) continue
            bestLateral = lateral
            val speedMs = max(fix.groundSpeedKmh, 40.0) / 3.6
            best = Classification(FlightStatus.FINAL, rw, end, max(0.0, -s) / speedMs)
        }
        return best
    }

    private fun circuitEta(fix: Fix): Double {
        // Rough: remaining circuit is about 1.5 × the straight distance to the field, flown at ~90 km/h.
        val speedMs = max(fix.groundSpeedKmh, 70.0) / 3.6
        return 30 + 1.5 * fix.pos.length / speedMs
    }

    private fun derivedClimb(fix: Fix, history: List<Fix>): Double? {
        val ref = history.lastOrNull { fix.timeMs - it.timeMs >= 5_000 } ?: return null
        return (fix.altitudeM - ref.altitudeM) / ((fix.timeMs - ref.timeMs) / 1000.0)
    }

    private fun isTowing(glider: Fix, tug: Fix): Boolean =
        abs(tug.timeMs - glider.timeMs) < 10_000 &&
            glider.pos.distanceTo(tug.pos) < TOW_MAX_DIST_M &&
            abs(glider.altitudeM - tug.altitudeM) < TOW_MAX_ALT_DIFF_M &&
            (tug.climbMs ?: 1.0) > 0.3

    fun endFromTrack(rw: Runway, trackDeg: Double?): RunwayEnd? = trackDeg?.let(rw::endFromTrack)
}
