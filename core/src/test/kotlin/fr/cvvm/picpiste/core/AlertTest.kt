package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.aprs.AircraftType
import fr.cvvm.picpiste.core.cart.AlertEngine
import fr.cvvm.picpiste.core.cart.AlertLevel
import fr.cvvm.picpiste.core.geo.GeoPoint
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.traffic.AircraftState
import fr.cvvm.picpiste.core.traffic.ConnectionState
import fr.cvvm.picpiste.core.traffic.Fix
import fr.cvvm.picpiste.core.traffic.FlightStatus
import fr.cvvm.picpiste.core.traffic.Identity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Graded cart alerts, gliders that go silent while landing, and a feed that stops. */
class AlertTest {
    private fun glider(status: FlightStatus, etaS: Double?, aglM: Double = 200.0, ageMs: Long = 1_000, lostAfterMs: Long = 60_000) =
        AircraftState(
            identity = Identity("DD0001", AircraftType.GLIDER, "F-CXYZ", "AB", null, anonymous = false),
            fix = Fix(0, Vec2(0.0, 0.0), GeoPoint(43.8, 3.78), 382.0, aglM, 90.0, 120.0, -1.0),
            trail = emptyList(),
            status = status,
            runway = null,
            runwayEnd = null,
            etaS = etaS,
            signalLost = ageMs > lostAfterMs,
            ageMs = ageMs,
            distanceM = 1_000.0,
            landedAtMs = null,
            landedRunway = null,
            landedThird = null,
        )

    @Test
    fun offTheRunwayNothingAndAloneOnItABorder() {
        assertEquals(AlertLevel.NONE, AlertEngine.evaluate(false, listOf(glider(FlightStatus.FINAL, 20.0))).level)
        assertEquals(AlertLevel.ON_RUNWAY, AlertEngine.evaluate(true, emptyList()).level)
    }

    @Test
    fun farCircuitIsACautionCloseOneADanger() {
        val far = AlertEngine.evaluate(true, listOf(glider(FlightStatus.CIRCUIT, 180.0)))
        assertEquals(AlertLevel.CAUTION, far.level)
        assertEquals("~3 min", far.eta)
        assertEquals(AlertLevel.DANGER, AlertEngine.evaluate(true, listOf(glider(FlightStatus.CIRCUIT, 60.0))).level)
        assertEquals(AlertLevel.CAUTION, AlertEngine.evaluate(true, listOf(glider(FlightStatus.CIRCUIT, null))).level)
    }

    @Test
    fun finalAndTakeoffAreAlwaysDangers() {
        assertEquals(AlertLevel.DANGER, AlertEngine.evaluate(true, listOf(glider(FlightStatus.FINAL, 200.0))).level)
        assertEquals(AlertLevel.DANGER, AlertEngine.evaluate(true, listOf(glider(FlightStatus.TAKEOFF, null, aglM = 0.0))).level)
        // The worst one wins: a final beats a far circuit.
        val both = AlertEngine.evaluate(true, listOf(glider(FlightStatus.CIRCUIT, 150.0), glider(FlightStatus.FINAL, 40.0)))
        assertEquals(FlightStatus.FINAL, both.threat?.status)
    }

    @Test
    fun gliderSilentOnFinalStillAlerts() {
        // Went silent 90 s ago at 200 m on final: probably landing, OGN just lost it.
        val lost = glider(FlightStatus.FINAL, etaS = 0.0, ageMs = 90_000)
        assertTrue(lost.signalLost)
        val alert = AlertEngine.evaluate(true, listOf(lost))
        assertEquals(AlertLevel.DANGER, alert.level)
        assertEquals("Glider on final · signal lost", alert.title)
        assertEquals("", alert.eta, "no made-up ETA once it has run out")
    }

    @Test
    fun silentGliderStopsCountingAfterAWhileOrOnTheGround() {
        assertEquals(AlertLevel.ON_RUNWAY, AlertEngine.evaluate(true, listOf(glider(FlightStatus.FINAL, 0.0, ageMs = 200_000))).level)
        // Lost while rolling out on the ground (FLARM switched off after landing): no alert.
        assertEquals(AlertLevel.ON_RUNWAY, AlertEngine.evaluate(true, listOf(glider(FlightStatus.FINAL, 0.0, aglM = 2.0, ageMs = 90_000))).level)
        // Lost far away, not landing: no alert either.
        assertEquals(AlertLevel.ON_RUNWAY, AlertEngine.evaluate(true, listOf(glider(FlightStatus.AIRBORNE, null, ageMs = 90_000))).level)
    }

    @Test
    fun feedIsLiveOnlyWithRecentLines() {
        val now = 1_000_000L
        assertTrue(ConnectionState(true, null, now - 20_000).isLive(now))
        assertNull(ConnectionState(true, null, now - 20_000).silentForMs(now))
        val silent = ConnectionState(true, null, now - 120_000)
        assertFalse(silent.isLive(now))
        assertEquals(120_000L, silent.silentForMs(now))
        assertFalse(ConnectionState(false, 8_000, now - 1_000).isLive(now))
        assertFalse(ConnectionState(true, null, null).isLive(now))
    }
}
