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
    fun gliderGoesThroughCircuitFinalAndLandsOn30Herbe() {
        val seen = statusesOf("AB", 420)
        val statuses = seen.mapNotNull { it.second?.status }.distinct()
        assertTrue(FlightStatus.CIRCUIT in statuses, "statuses: $statuses")
        assertTrue(FlightStatus.FINAL in statuses, "statuses: $statuses")
        assertEquals(FlightStatus.LANDED, statuses.last(), "statuses: $statuses")

        val finals = seen.mapNotNull { it.second }.filter { it.status == FlightStatus.FINAL }
        assertTrue(finals.all { it.runway?.id == "herbe" && it.runwayEnd == RunwayEnd.R30 }, finals.map { it.statusText }.distinct().toString())
        assertEquals("Finale 30 herbe", finals.first().statusText)

        val landed = seen.last { it.second?.status == FlightStatus.LANDED }.second!!
        assertNotNull(landed.landedAtMs)
        assertEquals("herbe", landed.landedRunway?.id)
        assertEquals("F-CXYZ", landed.identity.registration)
    }

    @Test
    fun towTakesOffFromDurGaucheThenTugLandsOnDurDroite() {
        val tug = statusesOf("RQ", 800).mapNotNull { it.second }
        val statuses = tug.map { it.status }.distinct()
        assertTrue(FlightStatus.TAKEOFF in statuses, "statuses: $statuses")
        assertTrue(FlightStatus.TOW in statuses, "statuses: $statuses")
        val takeoff = tug.first { it.status == FlightStatus.TAKEOFF && it.runway != null }
        assertEquals("dur-gauche", takeoff.runway?.id)
        assertEquals(RunwayEnd.R30, takeoff.runwayEnd)
        val final = tug.firstOrNull { it.status == FlightStatus.FINAL }
        assertNotNull(final, "statuses: $statuses")
        assertEquals("dur-droite", final.runway?.id)
        assertEquals(FlightStatus.LANDED, statuses.last(), "statuses: $statuses")

        val glider = statusesOf("12", 300).mapNotNull { it.second }.map { it.status }.distinct()
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
    fun cartOnHerbeGetsAlertWhileGliderInCircuitOrFinal() {
        val herbe = lfnl.runway("herbe")!!
        val onHerbe = (herbe.threshold12 + herbe.threshold30) * 0.5
        val levels = mutableListOf<AlertLevel>()
        val titles = mutableSetOf<String>()
        run(300, cartAt = { onHerbe }) { _, snap ->
            levels += snap.alert.level
            if (snap.alert.level == AlertLevel.DANGER) titles += snap.alert.title
            assertEquals("herbe", snap.cart?.runway?.id)
        }
        assertTrue(AlertLevel.ON_RUNWAY in levels)
        assertTrue(AlertLevel.DANGER in levels)
        assertTrue("Planeur en finale 30 herbe" in titles, titles.toString())
    }

    @Test
    fun cartOffRunwayNeverAlerts() {
        val apron = Vec2(lfnl.starter!!.x - 200, lfnl.starter!!.y - 40)
        run(420, cartAt = { apron }) { _, snap ->
            assertNull(snap.cart?.runway)
            assertEquals(AlertLevel.NONE, snap.alert.level)
        }
    }

    @Test
    fun runwayPresenceHasHysteresis() {
        val d = RunwayPresenceDetector(lfnl)
        val rw = lfnl.runway("dur-gauche")!!
        val mid = (rw.threshold12 + rw.threshold30) * 0.5
        val normal = Vec2(-rw.dir12.y, rw.dir12.x) // left of the 12 direction
        assertEquals(rw, d.update(mid, 4.0, 0))
        // Just outside the edge (inside the exit margin): still on it.
        val edge = mid + normal * (halfWidth(rw, mid, normal) + 5)
        assertEquals(rw, d.update(edge, 4.0, 1_000))
        // Well outside, but only for a moment: still on it.
        val out = mid + normal * (halfWidth(rw, mid, normal) + 30)
        assertEquals(rw, d.update(out, 4.0, 2_000))
        assertEquals(rw, d.update(out, 4.0, 3_000))
        assertNull(d.update(out, 4.0, 4_500))
    }

    private fun halfWidth(rw: fr.cvvm.picpiste.core.airfield.Runway, mid: Vec2, normal: Vec2): Double {
        var w = 0.0
        while (rw.outline.contains(mid + normal * w)) w += 0.5
        return w
    }
}
