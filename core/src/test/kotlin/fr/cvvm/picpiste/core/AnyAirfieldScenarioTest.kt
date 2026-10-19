package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.OsmImport
import fr.cvvm.picpiste.core.cart.AlertLevel
import fr.cvvm.picpiste.core.demo.DemoTraffic
import fr.cvvm.picpiste.core.traffic.AircraftState
import fr.cvvm.picpiste.core.traffic.FlightStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The demo scenario run through the engine on other airfields built from OpenStreetMap:
 * a single paved runway with a grass one beside it (LFNG), parallel runways with L/R letters
 * (LFLE) and crossing runways (LFPT). Proves the engine and the demo do not assume one field.
 */
class AnyAirfieldScenarioTest {
    private val t0 = 1_760_000_000_000L

    private fun airfield(code: String): Airfield {
        val json = javaClass.getResource("/osm/$code.json")!!.readText()
        return Airfield(OsmImport.build(code, json, elevationM = 100.0).config)
    }

    private val airfields = listOf("LFNL", "LFNG", "LFLE", "LFPT").map(::airfield)

    private class Run(val demo: DemoTraffic, val seen: Map<String, List<AircraftState>>, val alerts: List<Pair<AlertLevel, String>>)

    private fun run(a: Airfield, seconds: Int, cartOnRunway: Boolean = false): Run {
        val demo = DemoTraffic(a)
        val engine = PicPisteEngine(a).apply { ddb = demo.devices }
        val seen = mutableMapOf<String, MutableList<AircraftState>>()
        val alerts = mutableListOf<Pair<AlertLevel, String>>()
        val onRunway = (demo.main.thresholdA + demo.main.thresholdB) * 0.5
        for (s in 0..seconds) {
            val now = t0 + s * 1000L
            demo.beaconsAt(s * 1000L, now).forEach { engine.onLine(it, now) }
            if (cartOnRunway) engine.onCartPosition(a.frame.toGeo(onRunway), 4.0, now)
            val snap = engine.snapshot(now)
            snap.aircraft.forEach { seen.getOrPut(it.identity.label) { mutableListOf() } += it }
            alerts += snap.alert.level to snap.alert.title
        }
        return Run(demo, seen, alerts)
    }

    @Test
    fun gliderLandsOnTheMainRunwayEverywhere() {
        for (a in airfields) {
            val r = run(a, 420)
            val glider = r.seen["AB"].orEmpty()
            val statuses = glider.map { it.status }.distinct()
            assertTrue(FlightStatus.CIRCUIT in statuses && FlightStatus.FINAL in statuses, "${a.icao}: $statuses")
            assertEquals(FlightStatus.LANDED, statuses.last(), "${a.icao}: $statuses")
            val finals = glider.filter { it.status == FlightStatus.FINAL }
            assertTrue(
                finals.all { it.runway === r.demo.main && it.runwayEnd == r.demo.landingEnd },
                "${a.icao}: ${finals.map { it.statusText }.distinct()}",
            )
            assertEquals("Finale ${r.demo.main.direction(r.demo.landingEnd)}", finals.first().statusText, a.icao)
            assertTrue(glider.last().landedRunway === r.demo.main, "${a.icao}: landed on ${glider.last().landedRunway?.name}")
        }
    }

    @Test
    fun towTakesOffAndTugLandsOnTheTowRunwayEverywhere() {
        for (a in airfields) {
            val r = run(a, 800)
            val tug = r.seen["RQ"].orEmpty()
            val statuses = tug.map { it.status }.distinct()
            assertTrue(FlightStatus.TAKEOFF in statuses && FlightStatus.TOW in statuses, "${a.icao}: $statuses")
            val takeoff = tug.first { it.status == FlightStatus.TAKEOFF && it.runway != null }
            assertTrue(takeoff.runway === r.demo.tow && takeoff.runwayEnd == r.demo.towEnd, "${a.icao}: ${takeoff.statusText}")
            val final = tug.firstOrNull { it.status == FlightStatus.FINAL }
            assertNotNull(final, "${a.icao}: $statuses")
            assertTrue(final.runway === r.demo.tow, "${a.icao}: ${final.statusText}")
            assertEquals(FlightStatus.LANDED, statuses.last(), "${a.icao}: $statuses")
            assertTrue(FlightStatus.TOW in r.seen["KL"].orEmpty().map { it.status }, "${a.icao}: glider in tow")
        }
    }

    @Test
    fun towUsesAnotherRunwayWhenThereIsOne() {
        for (a in airfields.filter { it.runways.size > 1 }) {
            val demo = DemoTraffic(a)
            assertTrue(demo.tow !== demo.main, a.icao)
        }
    }

    @Test
    fun cartOnMainRunwayGetsDangerAlertEverywhere() {
        for (a in airfields) {
            val r = run(a, 300, cartOnRunway = true)
            val levels = r.alerts.map { it.first }
            assertTrue(AlertLevel.ON_RUNWAY in levels, a.icao)
            val titles = r.alerts.filter { it.first == AlertLevel.DANGER }.map { it.second }.toSet()
            assertTrue("Planeur en finale ${r.demo.main.direction(r.demo.landingEnd)}" in titles, "${a.icao}: $titles")
        }
    }

    @Test
    fun aircraftAboveCeilingAreHiddenEverywhere() {
        for (a in airfields) {
            run(a, 800).seen.values.flatten().forEach {
                assertTrue(it.fix.aglM <= 400.0, "${a.icao}: ${it.identity.label} shown at ${it.fix.aglM} m")
            }
        }
    }
}
