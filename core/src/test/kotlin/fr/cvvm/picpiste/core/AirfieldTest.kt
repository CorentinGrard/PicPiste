package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.RunwayEnd
import fr.cvvm.picpiste.core.airfield.RunwayThird
import fr.cvvm.picpiste.core.airfield.runwayNumber
import fr.cvvm.picpiste.core.geo.GeoPoint
import fr.cvvm.picpiste.core.geo.LocalFrame
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.geo.haversineM
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AirfieldTest {
    private val lfnl = Airfield.bundled("LFNL")

    @Test
    fun frameRoundTripsAndKeepsDistances() {
        val f = LocalFrame(GeoPoint(43.7995, 3.7825), 119.2)
        val p = GeoPoint(43.81, 3.80)
        val back = f.toGeo(f.toLocal(p))
        assertEquals(p.lat, back.lat, 1e-9)
        assertEquals(p.lon, back.lon, 1e-9)
        assertEquals(haversineM(f.origin, p), f.toLocal(p).length, 2.0)
        // A point along the axis bearing lands on +x.
        val ahead = f.toLocal(f.toGeo(Vec2(1000.0, 0.0)))
        assertEquals(1000.0, ahead.x, 1e-6)
        assertEquals(119.2, f.bearingOf(f.directionOf(119.2)), 1e-9)
    }

    @Test
    fun lfnlHasThreeParallelRunways1230() {
        assertEquals(182.0, lfnl.elevationM)
        assertEquals(listOf("herbe", "dur droite", "dur gauche"), lfnl.runways.map { it.name })
        for (rw in lfnl.runways) {
            assertEquals(12, runwayNumber(rw.headingA), "${rw.name} heading ${rw.headingA}")
            assertEquals(30, runwayNumber(rw.headingB))
            assertTrue(rw.length in 350.0..600.0, "${rw.name} length ${rw.length}")
            assertTrue(rw.outline.contains((rw.thresholdA + rw.thresholdB) * 0.5), "${rw.name} midpoint inside outline")
        }
        // Herbe is the northernmost (left of the 12 direction = +y), dur gauche the southernmost.
        val ys = lfnl.runways.associate { it.name to it.lateral(Vec2(0.0, 0.0)) }
        assertTrue(lfnl.runway("herbe")!!.thresholdA.y > lfnl.runway("dur-droite")!!.thresholdA.y)
        assertTrue(lfnl.runway("dur-droite")!!.thresholdA.y > lfnl.runway("dur-gauche")!!.thresholdA.y)
        assertEquals(3, ys.size)
        assertNotNull(lfnl.starter)
    }

    @Test
    fun thirdsDependOnLandingDirection() {
        val rw = lfnl.runway("herbe")!!
        val nearThreshold30 = rw.thresholdB - rw.dirA * 50.0
        assertEquals(RunwayThird.START, rw.third(nearThreshold30, RunwayEnd.B))
        assertEquals(RunwayThird.END, rw.third(nearThreshold30, RunwayEnd.A))
        assertEquals("30 herbe", rw.label(RunwayEnd.B))
        assertTrue(abs(rw.lateral(nearThreshold30)) < 1e-6)
    }
}
