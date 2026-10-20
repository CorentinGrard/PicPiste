package fr.cvvm.picpiste.core.traffic

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.geo.Vec2

/**
 * The field altitude as OGN reports it. The aircraft's GPS altitudes and the airfield file
 * (OpenStreetMap, terrain model) can disagree by tens of metres; heights above the field must
 * use the aircraft's own scale, or an aircraft rolling on the runway looks 20 m underground and
 * one climbing out still "on the ground". So: the altitudes of aircraft stopped on the field,
 * a median per aircraft, then the median of those. Until one is seen, the file's elevation.
 */
class ElevationCalibration(private val airfield: Airfield) {
    private val byAircraft = LinkedHashMap<String, ArrayDeque<Double>>()

    /** Altitude (m) of the ground on the field, in the aircraft's scale. */
    var fieldAltitudeM: Double = airfield.elevationM
        private set

    /** How far the file's elevation is from what the aircraft report (m). */
    val offsetM: Double get() = fieldAltitudeM - airfield.elevationM

    fun onFix(address: String, pos: Vec2, altitudeM: Double, groundSpeedKmh: Double) {
        if (groundSpeedKmh > STOPPED_KMH) return
        if (kotlin.math.abs(altitudeM - airfield.elevationM) > MAX_OFFSET_M) return
        if (!airfield.boundary.containsWithin(pos, 50.0)) return
        val samples = byAircraft.getOrPut(address) { ArrayDeque() }
        samples.addLast(altitudeM)
        while (samples.size > SAMPLES_PER_AIRCRAFT) samples.removeFirst()
        val perAircraft = byAircraft.values.filter { it.size >= MIN_SAMPLES }.map { median(it) }
        if (perAircraft.isNotEmpty()) fieldAltitudeM = median(perAircraft)
    }

    private fun median(v: Collection<Double>): Double = v.sorted().let { s ->
        if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    companion object {
        const val STOPPED_KMH = 3.0
        /** Further off than this, it is not an aircraft on the ground here (or a bad fix). */
        const val MAX_OFFSET_M = 80.0
        const val MIN_SAMPLES = 3
        const val SAMPLES_PER_AIRCRAFT = 20
    }
}
