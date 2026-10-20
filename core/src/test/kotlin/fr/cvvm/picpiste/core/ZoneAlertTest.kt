package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.AlertZone
import fr.cvvm.picpiste.core.airfield.AlertZoneConfig
import fr.cvvm.picpiste.core.airfield.NewShape
import fr.cvvm.picpiste.core.airfield.OsmImport
import fr.cvvm.picpiste.core.airfield.Runway
import fr.cvvm.picpiste.core.airfield.RunwayEnd
import fr.cvvm.picpiste.core.airfield.Shape
import fr.cvvm.picpiste.core.airfield.add
import fr.cvvm.picpiste.core.airfield.delete
import fr.cvvm.picpiste.core.airfield.renameRunway
import fr.cvvm.picpiste.core.airfield.zoneOutline
import fr.cvvm.picpiste.core.aprs.AircraftType
import fr.cvvm.picpiste.core.cart.AlertEngine
import fr.cvvm.picpiste.core.cart.AlertLevel
import fr.cvvm.picpiste.core.cart.RunwayPresenceDetector
import fr.cvvm.picpiste.core.demo.DemoTraffic
import fr.cvvm.picpiste.core.geo.GeoPoint
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.traffic.AircraftState
import fr.cvvm.picpiste.core.traffic.Fix
import fr.cvvm.picpiste.core.traffic.FlightStatus
import fr.cvvm.picpiste.core.traffic.Identity
import fr.cvvm.picpiste.core.traffic.TrafficSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Warning zones: each one protects its runways; the circuit banner can be turned off per zone. */
class ZoneAlertTest {
    private fun airfield(code: String) =
        Airfield(OsmImport.build(code, javaClass.getResource("/osm/$code.json")!!.readText(), elevationM = 100.0).config)

    private val lfpt = airfield("LFPT") // crossing runways 04/22 and 12/30
    private val lfle = airfield("LFLE") // parallel runways 14L/32R and 14/32

    private fun Airfield.runwayFrom(ref: String) = runways.first { it.ref(RunwayEnd.A) == ref }

    private fun glider(status: FlightStatus, runway: Runway?, etaS: Double? = 40.0) = AircraftState(
        identity = Identity("DD0001", AircraftType.GLIDER, "F-CXYZ", "AB", null, anonymous = false),
        fix = Fix(0, Vec2(0.0, 0.0), GeoPoint(49.1, 2.04), 300.0, 200.0, 90.0, 120.0, -1.0),
        trail = emptyList(),
        status = status,
        runway = runway,
        runwayEnd = runway?.let { RunwayEnd.A },
        etaS = etaS,
        signalLost = false,
        ageMs = 1_000,
        distanceM = 1_000.0,
        landedAtMs = null,
        landedRunway = null,
        landedThird = null,
    )

    private fun zonesAt(a: Airfield, p: Vec2): List<AlertZone> =
        RunwayPresenceDetector(a).apply { update(p, 4.0, 0) }.zones

    private fun level(a: Airfield, p: Vec2, vararg aircraft: AircraftState) =
        AlertEngine.evaluate(zonesAt(a, p), aircraft.toList(), a).level

    @Test
    fun crossingRunwaysOnlyTheCartsRunwayCounts() {
        val r04 = lfpt.runwayFrom("04")
        val r12 = lfpt.runwayFrom("12")
        // On 04/22, well away from the crossing.
        val cart = r04.thresholdA + r04.dirA * (r04.length * 0.1)
        assertTrue(zonesAt(lfpt, cart).isNotEmpty())
        assertFalse(r12.extended(Airfield.APPROACH_ZONE_M).containsWithin(cart, 10.0))

        assertEquals(AlertLevel.ON_RUNWAY, level(lfpt, cart, glider(FlightStatus.FINAL, r12)), "final on the other runway")
        assertEquals(AlertLevel.ON_RUNWAY, level(lfpt, cart, glider(FlightStatus.TAKEOFF, r12)))
        assertEquals(AlertLevel.DANGER, level(lfpt, cart, glider(FlightStatus.FINAL, r04)))
        assertEquals(AlertLevel.DANGER, level(lfpt, cart, glider(FlightStatus.TAKEOFF, r04)))
        // Runway not known: warn, to be safe.
        assertEquals(AlertLevel.DANGER, level(lfpt, cart, glider(FlightStatus.FINAL, null)))
        // The circuit still gives the amber banner (automatic zones have it on).
        assertEquals(AlertLevel.CAUTION, level(lfpt, cart, glider(FlightStatus.CIRCUIT, null, etaS = 200.0)))
    }

    @Test
    fun underTheFinalBeforeTheThreshold() {
        val r12 = lfpt.runwayFrom("12")
        val cart = r12.thresholdA - r12.dirA * 100.0
        assertFalse(r12.outline.contains(cart))
        assertEquals(AlertLevel.DANGER, level(lfpt, cart, glider(FlightStatus.FINAL, r12)))
        // Further out: not a zone any more.
        assertEquals(AlertLevel.NONE, level(lfpt, r12.thresholdA - r12.dirA * 300.0, glider(FlightStatus.FINAL, r12)))
    }

    @Test
    fun closeParallelRunwaysCountAsOne() {
        val left = lfle.runwayFrom("14L")
        val other = lfle.runwayFrom("14")
        val zoneOnLeft = lfle.alertZones.first { left.id in it.runways && it.runways.size == 1 }
        // A glider placed on the other parallel may well be landing on this one.
        assertTrue(lfle.protects(zoneOnLeft, other))
        val cart = (left.thresholdA + left.thresholdB) * 0.5
        assertEquals(AlertLevel.DANGER, level(lfle, cart, glider(FlightStatus.FINAL, other)))
    }

    @Test
    fun circuitBannerCanBeTurnedOffPerZone() {
        val r04 = lfpt.config.runways.first { it.ends[0].ref == "04" }
        fun with(circuit: Boolean) = Airfield(
            lfpt.config.copy(alertZones = listOf(AlertZoneConfig(r04.zoneOutline(150.0), listOf(r04.id), circuit))),
        )
        val quiet = with(circuit = false)
        val cart = quiet.runwayFrom("04").let { it.thresholdA + it.dirA * 200.0 }
        assertEquals(AlertLevel.ON_RUNWAY, level(quiet, cart, glider(FlightStatus.CIRCUIT, null, etaS = 200.0)))
        assertEquals(AlertLevel.DANGER, level(quiet, cart, glider(FlightStatus.FINAL, quiet.runwayFrom("04"))))
        val loud = with(circuit = true)
        assertEquals(AlertLevel.CAUTION, level(loud, cart, glider(FlightStatus.CIRCUIT, null, etaS = 200.0)))
        // Drawn zones replace the automatic ones: 12/30 is not protected any more.
        val on12 = loud.runwayFrom("12").let { (it.thresholdA + it.thresholdB) * 0.5 }
        assertTrue(zonesAt(loud, on12).isEmpty() || loud.runwayFrom("04").outline.contains(on12))
    }

    @Test
    fun zonesFollowRunwayRenameAndDelete() {
        val c = lfpt.config
        val r04 = c.runways.indexOfFirst { it.ends[0].ref == "04" }
        val at = lfpt.frame.toGeo(lfpt.runways[r04].thresholdA)
        val (withZone, shape) = c.add(NewShape.ALERT_ZONE, at)
        assertEquals(Shape.AlertZone(0), shape)
        assertEquals(listOf(c.runways[r04].id), withZone.alertZones[0].runways, "linked to the nearest runway")
        val renamed = withZone.renameRunway(r04, "Nord")
        assertEquals(listOf("nord"), renamed.alertZones[0].runways)
        val deleted = renamed.delete(Shape.RunwayOutline(r04))
        assertEquals(emptyList(), deleted.alertZones[0].runways)
    }

    @Test
    fun displayCeilingDoesNotHideThreats() {
        // Ceiling at 10 m: nothing airborne is drawn, yet a glider landing still warns the cart.
        val a = airfield("LFNL")
        val demo = DemoTraffic(a)
        val engine = PicPisteEngine(a, TrafficSettings(ceilingAglM = 10.0)).apply { ddb = demo.devices }
        val onRunway = a.frame.toGeo((demo.main.thresholdA + demo.main.thresholdB) * 0.5)
        val t0 = 1_760_000_000_000L
        var warned = false
        for (s in 0..420) {
            val now = t0 + s * 1000L
            demo.beaconsAt(s * 1000L, now).forEach { engine.onLine(it, now) }
            engine.onCartPosition(onRunway, 4.0, now)
            val snap = engine.snapshot(now)
            // Below 25 m over the field, aircraft are always drawn (taking off, landing).
            assertTrue(snap.aircraft.none { it.fix.aglM > 25.0 }, "nothing drawn above the ceiling")
            val threat = snap.alert.threat
            if (threat != null && threat.fix.aglM > 25.0 && !threat.shown) warned = true
        }
        assertTrue(warned, "warned about a glider hidden by the ceiling")
    }
}
