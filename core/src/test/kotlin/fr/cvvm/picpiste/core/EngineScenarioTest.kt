package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.RunwayEnd
import fr.cvvm.picpiste.core.cart.AlertLevel
import fr.cvvm.picpiste.core.cart.RunwayPresenceDetector
import fr.cvvm.picpiste.core.demo.DemoTraffic
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.traffic.AircraftState
import fr.cvvm.picpiste.core.traffic.FlightStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the demo scenario through the real parser and engine, second by second. */
class EngineScenarioTest {
    private val lfnl = Airfield.bundled("LFNL")
    private val t0 = 1_760_000_000_000L

    private fun run(seconds: Int, cartAt: ((Long) -> Vec2?)? = null, each: (Long, Snapshot) -> Unit) {
        val demo = DemoTraffic(lfnl)
        val engine = PicPisteEngine(lfnl).apply { ddb = demo.devices }
        for (s in 0..seconds) {
            val now = t0 + s * 1000L
            demo.beaconsAt(s * 1000L, now).forEach { engine.onLine(it, now) }
            cartAt?.invoke(s * 1000L)?.let { engine.onCartPosition(lfnl.frame.toGeo(it), 4.0, now) }
            each(s * 1000L, engine.snapshot(now))
        }
    }

    private fun statusesOf(label: String, seconds: Int): List<Pair<Long, AircraftState?>> {
        val out = mutableListOf<Pair<Long, AircraftState?>>()
        run(seconds) { t, snap -> out += t to snap.aircraft.firstOrNull { it.identity.label == label } }
        return out
    }

    @Test
    fun gliderGoesThroughCircuitFinalAndLandsOnTheMainRunway() {
        val seen = statusesOf("AB", 420)
        val statuses = seen.mapNotNull { it.second?.status }.distinct()
        assertTrue(FlightStatus.CIRCUIT in statuses, "statuses: $statuses")
        assertTrue(FlightStatus.FINAL in statuses, "statuses: $statuses")
        assertEquals(FlightStatus.LANDED, statuses.last(), "statuses: $statuses")

        val finals = seen.mapNotNull { it.second }.filter { it.status == FlightStatus.FINAL }
        assertTrue(finals.all { it.runway?.id == "herbe" && it.runwayEnd == RunwayEnd.B }, finals.map { it.statusText }.distinct().toString())
        assertEquals("Final 30", finals.first().statusText)

        val landed = seen.last { it.second?.status == FlightStatus.LANDED }.second!!
        assertNotNull(landed.landedAtMs)
        assertEquals("herbe", landed.landedRunway?.id)
        assertEquals("F-CXYZ", landed.identity.registration)
    }

    @Test
    fun towTakesOffFromAnotherRunwayThenTugLandsOnIt() {
        val tug = statusesOf("RQ", 800).mapNotNull { it.second }
        val statuses = tug.map { it.status }.distinct()
        assertTrue(FlightStatus.TAKEOFF in statuses, "statuses: $statuses")
        assertTrue(FlightStatus.TOW in statuses, "statuses: $statuses")
        val takeoff = tug.first { it.status == FlightStatus.TAKEOFF && it.runway != null }
        assertEquals("dur-gauche", takeoff.runway?.id)
        assertEquals(RunwayEnd.B, takeoff.runwayEnd)
        val final = tug.firstOrNull { it.status == FlightStatus.FINAL }
        assertNotNull(final, "statuses: $statuses")
        assertEquals("dur-gauche", final.runway?.id)
        assertEquals(FlightStatus.LANDED, statuses.last(), "statuses: $statuses")

        val glider = statusesOf("KL", 300).mapNotNull { it.second }.map { it.status }.distinct()
        assertTrue(FlightStatus.TOW in glider, "glider in tow statuses: $glider")
    }

    @Test
    fun aircraftAboveCeilingAreHidden() {
        run(800) { _, snap ->
            snap.aircraft.forEach {
                assertTrue(it.fix.aglM <= 400.0 || it.fix.aglM < 25, "${it.identity.label} shown at ${it.fix.aglM} m")
            }
        }
    }

    @Test
    fun arrivalsListShowsFinalFirst() {
        var sawFinalFirst = false
        run(420) { _, snap ->
            if (snap.arrivals.size >= 2 && snap.arrivals.any { it.status == FlightStatus.FINAL }) {
                assertEquals(FlightStatus.FINAL, snap.arrivals.first().status)
                sawFinalFirst = true
            }
        }
        assertTrue(sawFinalFirst, "never saw a final with another arrival")
    }

    @Test
    fun cartOnMainRunwayGetsAlertWhileGliderInCircuitOrFinal() {
        val herbe = lfnl.runway("herbe")!!
        val onHerbe = (herbe.thresholdA + herbe.thresholdB) * 0.5
        val levels = mutableListOf<AlertLevel>()
        val titles = mutableSetOf<String>()
        run(300, cartAt = { onHerbe }) { _, snap ->
            levels += snap.alert.level
            if (snap.alert.level == AlertLevel.DANGER) titles += snap.alert.title
            assertTrue(snap.cart?.onRunway == true)
        }
        assertTrue(AlertLevel.ON_RUNWAY in levels)
        assertTrue(AlertLevel.DANGER in levels)
        assertTrue("Glider on final 30" in titles, titles.toString())
    }

    @Test
    fun cartOffRunwayNeverAlerts() {
        val apron = Vec2(lfnl.starter!!.x - 200, lfnl.starter!!.y - 40)
        run(420, cartAt = { apron }) { _, snap ->
            assertTrue(snap.cart?.onRunway == false)
            assertEquals(AlertLevel.NONE, snap.alert.level)
        }
    }

    @Test
    fun runwayPresenceHasHysteresis() {
        val d = RunwayPresenceDetector(lfnl)
        val rw = lfnl.runway("dur-gauche")!!
        val mid = (rw.thresholdA + rw.thresholdB) * 0.5
        val south = Vec2(rw.dirA.y, -rw.dirA.x) // right of the 12 direction = away from the other runways
        assertTrue(d.update(mid, 4.0, 0))
        // Just outside the south edge (inside the exit margin): still on it.
        val edge = mid + south * (halfWidth(rw, mid, south) + 5)
        assertTrue(d.update(edge, 4.0, 1_000))
        // Well outside, but only for a moment: still on it.
        val out = mid + south * (halfWidth(rw, mid, south) + 30)
        assertTrue(d.update(out, 4.0, 2_000))
        assertTrue(d.update(out, 4.0, 3_000))
        assertFalse(d.update(out, 4.0, 4_500))
    }

    @Test
    fun crossingAllThreeRunwaysIsOneSingleCrossing() {
        val d = RunwayPresenceDetector(lfnl)
        val gauche = lfnl.runway("dur-gauche")!!
        val herbe = lfnl.runway("herbe")!!
        // Drive north (+y) across the middle of the field, 1 m per step, from 40 m south of dur gauche
        // to 40 m north of herbe.
        val x = (gauche.thresholdA.x + gauche.thresholdB.x) / 2
        val y0 = gauche.outline.points.minOf { it.y } - 40
        val y1 = herbe.outline.points.maxOf { it.y } + 40
        var changes = 0
        var last = false
        var t = 0L
        var y = y0
        while (y <= y1) {
            val on = d.update(Vec2(x, y), 4.0, t)
            if (on != last) changes++
            last = on
            y += 1.0
            t += 500
        }
        repeat(10) { d.update(Vec2(x, y1), 4.0, t); t += 500 }
        if (d.onRunway != last) changes++
        assertEquals(2, changes, "entered and left exactly once")
        assertFalse(d.onRunway)
        // The grass between dur droite and herbe counts as runway.
        val gap = Vec2(x, (lfnl.runway("dur-droite")!!.outline.points.maxOf { it.y } + herbe.outline.points.minOf { it.y }) / 2)
        assertTrue(lfnl.inRunwayZone(gap))
    }

    private fun halfWidth(rw: fr.cvvm.picpiste.core.airfield.Runway, mid: Vec2, normal: Vec2): Double {
        var w = 0.0
        while (rw.outline.contains(mid + normal * w)) w += 0.5
        return w
    }
}
