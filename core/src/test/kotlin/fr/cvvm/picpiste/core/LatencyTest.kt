package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.aprs.AircraftType
import fr.cvvm.picpiste.core.aprs.AprsFormatter
import fr.cvvm.picpiste.core.aprs.AprsParser
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.geo.angleDiffDeg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val fr.cvvm.picpiste.core.traffic.AircraftState.shownPos get() = shownAt(0, Airfield.bundled("LFNL").frame).first
private val fr.cvvm.picpiste.core.traffic.AircraftState.shownTrackDeg get() = shownAt(0, Airfield.bundled("LFNL").frame).second

/** OGN positions arrive late: ages count from when the aircraft sent them, and the radar moves them on. */
class LatencyTest {
    private val lfnl = Airfield.bundled("LFNL")
    /** A whole second, as in the beacons. */
    private val t0 = 1_760_000_000_000L

    private fun beacon(sentMs: Long, at: Vec2 = Vec2(0.0, 0.0), kmh: Double = 108.0, track: Double = 0.0, aglM: Double = 300.0, rot: String = "+0.0") =
        AprsFormatter.aircraft("DD0A01", AircraftType.GLIDER, lfnl.frame.toGeo(at), lfnl.elevationM + aglM, track, kmh, 0.0, sentMs)
            .replace("+0.0rot", "${rot}rot")

    @Test
    fun ageCountsFromWhenTheAircraftSentIt() {
        val e = PicPisteEngine(lfnl)
        e.onLine(beacon(t0), t0 + 3_000) // 3 s in the OGN network
        // Sent somewhere in second t0: counted from its middle.
        assertEquals(3_500L - 1_000, e.snapshot(t0 + 3_000).aircraft.single().ageMs)
    }

    @Test
    fun beaconRelayedAfterANewerOneIsIgnored() {
        val e = PicPisteEngine(lfnl)
        e.onLine(beacon(t0 + 5_000, at = Vec2(500.0, 0.0)), t0 + 6_000)
        e.onLine(beacon(t0, at = Vec2(0.0, 0.0)), t0 + 7_000)
        val a = e.snapshot(t0 + 7_000).aircraft.single()
        assertEquals(500.0, a.fix.pos.x, 1.0)
    }

    @Test
    fun clockFarOffFallsBackToArrivalTime() {
        val e = PicPisteEngine(lfnl)
        e.onLine(beacon(t0 - 600_000), t0)
        assertTrue(e.snapshot(t0).aircraft.single().ageMs <= 30_000)
    }

    @Test
    fun flyingStraightIsMovedOnBySpeed() {
        val e = PicPisteEngine(lfnl)
        e.onLine(beacon(t0, track = 90.0), t0 + 2_000)
        val a = e.snapshot(t0 + 4_500).aircraft.single() // 4 s after it was sent
        val moved = a.shownPos - a.fix.pos
        assertEquals(120.0, moved.length, 2.0) // 30 m/s × 4 s
        assertEquals(90.0, lfnl.frame.bearingOf(moved), 1.0)
        // Statuses and alerts keep the received position.
        assertEquals(a.fix.pos.length, a.distanceM, 0.01)
    }

    @Test
    fun guessStopsAfterTenSeconds() {
        val e = PicPisteEngine(lfnl)
        e.onLine(beacon(t0), t0 + 2_000)
        val a = e.snapshot(t0 + 30_500).aircraft.single()
        assertEquals(300.0, (a.shownPos - a.fix.pos).length, 2.0)
    }

    @Test
    fun turningFollowsTheRateOfTurn() {
        val e = PicPisteEngine(lfnl)
        // +6.0rot = 18°/s: half a circle in 10 s.
        e.onLine(beacon(t0, rot = "+6.0"), t0 + 2_000)
        val a = e.snapshot(t0 + 10_500).aircraft.single()
        assertEquals(0.0, angleDiffDeg(a.shownTrackDeg!!, 180.0), 2.0)
        // Diameter of that half circle: 300 m flown over π / 2.
        assertEquals(300.0 / Math.PI * 2, (a.shownPos - a.fix.pos).length, 5.0)
    }

    @Test
    fun slowOrLostAircraftStayWhereTheyWereHeard() {
        val e = PicPisteEngine(lfnl)
        e.onLine(beacon(t0, kmh = 8.0, aglM = 0.0), t0)
        val slow = e.snapshot(t0 + 5_000).aircraft.single()
        assertEquals(slow.fix.pos, slow.shownPos)

        val lost = PicPisteEngine(lfnl)
        lost.onLine(beacon(t0), t0)
        val a = lost.snapshot(t0 + 90_000).aircraft.single()
        assertTrue(a.signalLost)
        assertEquals(a.fix.pos, a.shownPos)
    }

    @Test
    fun rateOfTurnIsParsed() {
        assertEquals(-12.6, AprsParser.parse(beacon(t0, rot = "-4.2"))!!.turnDegS!!, 0.01)
    }
}
