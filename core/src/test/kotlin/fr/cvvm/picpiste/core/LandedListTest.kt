package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.aprs.AircraftType
import fr.cvvm.picpiste.core.aprs.AprsFormatter
import fr.cvvm.picpiste.core.demo.DemoTraffic
import fr.cvvm.picpiste.core.geo.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The "Landed" list holds gliders waiting on a runway; once pulled off it, they leave the list. */
class LandedListTest {
    private val lfnl = Airfield.bundled("LFNL")
    private val t0 = 1_760_000_000_000L
    private val glider = "DD0A01" // AB in the demo, lands on the grass runway (the northern one)

    /** Demo traffic until AB has landed and stopped; the engine and the time reached. */
    private fun landed(): Pair<PicPisteEngine, Long> {
        val demo = DemoTraffic(lfnl)
        val engine = PicPisteEngine(lfnl).apply { ddb = demo.devices }
        for (s in 0..300) {
            val now = t0 + s * 1000L
            demo.beaconsAt(s * 1000L, now).filter { it.contains(glider) }
                .forEach { engine.onLine(it, now) }
        }
        val now = t0 + 300_000L
        assertTrue(engine.snapshot(now).landed.any { it.address == glider }, "AB landed")
        return engine to now
    }

    /** Beacons of AB moving [metresPerS] across the runway axis (north, away from the others). */
    private fun pull(engine: PicPisteEngine, from: Long, seconds: Int, metresPerS: Double): Long {
        val start = engine.snapshot(from).aircraft.single { it.address == glider }.fix.pos
        val north = lfnl.frame.bearingOf(Vec2(0.0, 1.0))
        var now = from
        for (s in 1..seconds) {
            now = from + s * 1000L
            val p = start + Vec2(0.0, 1.0) * (metresPerS * s)
            val line = AprsFormatter.aircraft(
                glider, AircraftType.GLIDER, lfnl.frame.toGeo(p), lfnl.elevationM,
                north, metresPerS * 3.6, 0.0, now,
            )
            engine.onLine(line, now)
        }
        return now
    }

    @Test
    fun pulledOffTheRunwayAtWalkingPaceLeavesTheList() {
        val (engine, t) = landed()
        val end = pull(engine, t, 90, 0.8) // ~3 km/h, about 70 m
        val snap = engine.snapshot(end)
        assertTrue(snap.landed.none { it.address == glider }, "still listed: ${snap.landed.map { it.statusText }}")
    }

    @Test
    fun stillOnTheRunwayStaysListed() {
        val (engine, t) = landed()
        val end = pull(engine, t, 30, 0.0) // stopped, beacons keep coming
        val snap = engine.snapshot(end)
        assertEquals(listOf(glider), snap.landed.map { it.address })
    }
}
