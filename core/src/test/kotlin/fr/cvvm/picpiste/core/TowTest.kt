package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.aprs.AircraftType
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.traffic.Fix
import fr.cvvm.picpiste.core.traffic.FlightStatus
import fr.cvvm.picpiste.core.traffic.StatusEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TowTest {
    private val lfnl = Airfield.bundled("LFNL")
    private val engine = StatusEngine(lfnl)
    private val t0 = 1_760_000_000_000L

    private fun fix(tS: Int, x: Double, y: Double, aglM: Double, climb: Double) = Fix(
        timeMs = t0 + tS * 1000L, pos = Vec2(x, y), geo = lfnl.frame.toGeo(Vec2(x, y)),
        altitudeM = lfnl.elevationM + aglM, aglM = aglM, groundSpeedKmh = 120.0, trackDeg = 90.0, climbMs = climb,
    )

    @Test
    fun towHoldsThroughAWeakVarioAndAMissedTugBeacon() {
        // Tug 80 m ahead, its last beacon 14 s old; the glider climbs only 0.2 m/s this second.
        val tug = fix(0, 1_000.0, 500.0, 220.0, 0.1)
        val glider = fix(14, 1_420.0, 500.0, 240.0, 0.2)
        assertEquals(FlightStatus.TOW, engine.classify(glider, emptyList(), FlightStatus.TOW, AircraftType.GLIDER, listOf(tug)).status)
        // Not in tow before: a weak vario is not enough to start.
        assertNotEquals(FlightStatus.TOW, engine.classify(glider, emptyList(), FlightStatus.CIRCUIT, AircraftType.GLIDER, listOf(tug)).status)
    }

    @Test
    fun releaseEndsTheTow() {
        val tug = fix(10, 1_000.0, 500.0, 150.0, -8.0) // diving back
        val glider = fix(10, 1_000.0, 1_100.0, 300.0, 0.5) // turned away, 600 m off
        assertNotEquals(FlightStatus.TOW, engine.classify(glider, emptyList(), FlightStatus.TOW, AircraftType.GLIDER, listOf(tug)).status)
        assertNotEquals(FlightStatus.TOW, engine.classify(tug, emptyList(), FlightStatus.TOW, AircraftType.TOW_PLANE, emptyList()).status)
    }
}
