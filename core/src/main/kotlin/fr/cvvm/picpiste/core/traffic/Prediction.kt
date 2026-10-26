package fr.cvvm.picpiste.core.traffic

import fr.cvvm.picpiste.core.geo.LocalFrame
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.geo.normalizeDeg
import kotlin.math.min

/**
 * Where an aircraft should be now, from its last position: OGN positions reach the phone
 * 2 s late on average, and the next one comes 3 s later (up to 13 s), so the radar would
 * otherwise always show the past. Display only.
 */
object Prediction {
    /** Never guess further than this: past it, a turn or a landing makes the guess worthless. */
    const val MAX_S = 10.0
    /** Slower than this (on the ground, being towed by the cart), the heading means little. */
    const val MIN_KMH = 30.0
    /** A tight thermalling turn is about 20°/s. */
    const val MAX_TURN_DEG_S = 25.0
    private const val STEP_S = 0.25

    /** Position and heading [ageS] seconds after [fix], flying on at its speed and rate of turn. */
    fun ahead(fix: Fix, ageS: Double, frame: LocalFrame): Pair<Vec2, Double?> {
        val track = fix.trackDeg ?: return fix.pos to null
        if (fix.groundSpeedKmh < MIN_KMH || ageS <= 0) return fix.pos to track
        val speedMs = fix.groundSpeedKmh / 3.6
        val turn = (fix.turnDegS ?: 0.0).coerceIn(-MAX_TURN_DEG_S, MAX_TURN_DEG_S)
        var pos = fix.pos
        var heading = track
        var left = min(ageS, MAX_S)
        while (left > 0) {
            val dt = min(STEP_S, left)
            pos += frame.directionOf(heading + turn * dt / 2) * (speedMs * dt)
            heading += turn * dt
            left -= dt
        }
        return pos to normalizeDeg(heading)
    }
}
