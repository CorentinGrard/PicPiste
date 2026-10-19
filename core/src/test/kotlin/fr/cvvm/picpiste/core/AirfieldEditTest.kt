package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.NewShape
import fr.cvvm.picpiste.core.airfield.RunwayEnd
import fr.cvvm.picpiste.core.airfield.Shape
import fr.cvvm.picpiste.core.airfield.add
import fr.cvvm.picpiste.core.airfield.delete
import fr.cvvm.picpiste.core.airfield.deletePoint
import fr.cvvm.picpiste.core.airfield.insertPoint
import fr.cvvm.picpiste.core.airfield.label
import fr.cvvm.picpiste.core.airfield.movePoint
import fr.cvvm.picpiste.core.airfield.moveShape
import fr.cvvm.picpiste.core.airfield.normalized
import fr.cvvm.picpiste.core.airfield.points
import fr.cvvm.picpiste.core.airfield.problems
import fr.cvvm.picpiste.core.airfield.renameRunway
import fr.cvvm.picpiste.core.airfield.setRunwayRefs
import fr.cvvm.picpiste.core.airfield.shapes
import fr.cvvm.picpiste.core.geo.GeoPoint
import fr.cvvm.picpiste.core.geo.haversineM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AirfieldEditTest {
    private val lfnl = Airfield.bundledConfig("LFNL")

    @Test
    fun moveAThresholdToTheGps() {
        val t = Shape.Threshold(0, RunwayEnd.B)
        val gps = GeoPoint(43.79905, 3.78471)
        val edited = lfnl.movePoint(t, 0, gps)
        assertEquals(listOf(43.79905, 3.78471), edited.points(t).single())
        assertEquals(lfnl.runways[1], edited.runways[1])
        assertEquals("Seuil 30 herbe", lfnl.label(t))
    }

    @Test
    fun insertAndDeletePoints() {
        val s = Shape.Hangar(1)
        val before = lfnl.points(s)
        val (more, at) = lfnl.insertPoint(s, before.lastIndex)
        // After the last point of an outline: the new point closes the ring, at the end.
        assertEquals(before.size, at)
        assertEquals(before.size + 1, more.points(s).size)
        assertEquals(before, more.deletePoint(s, at).points(s))
        // A polygon keeps at least 3 points.
        var c = lfnl
        repeat(10) { c = c.deletePoint(s, 0) }
        assertEquals(3, c.points(s).size)
    }

    @Test
    fun moveAWholeHangar() {
        val s = Shape.Hangar(0)
        val p0 = lfnl.points(s)[0]
        val moved = lfnl.moveShape(s, 0, GeoPoint(p0[0] + 0.001, p0[1]))
        val shift = lfnl.points(s).zip(moved.points(s)).map { (a, b) -> haversineM(GeoPoint(a[0], a[1]), GeoPoint(b[0], b[1])) }
        assertTrue(shift.all { it in 110.0..113.0 }, shift.toString())
    }

    @Test
    fun addAndDeleteShapes() {
        val at = GeoPoint(43.7995, 3.7825)
        val (withRunway, rw) = lfnl.add(NewShape.RUNWAY, at)
        assertEquals(Shape.RunwayOutline(3), rw)
        assertEquals(emptyList(), withRunway.problems())
        val added = Airfield(withRunway.normalized()).runways.last()
        assertEquals("12" to "30", added.ref(RunwayEnd.A) to added.ref(RunwayEnd.B))
        assertEquals(600.0, added.length, 1.0)
        assertEquals(lfnl.runways, withRunway.delete(rw).runways)
        // The last runway cannot go.
        var c = lfnl
        repeat(5) { c = c.delete(Shape.RunwayOutline(0)) }
        assertEquals(1, c.runways.size)

        val (noStarter, _) = lfnl.delete(Shape.Starter) to Unit
        assertTrue(Shape.Starter !in noStarter.shapes())
        val (back, s) = noStarter.add(NewShape.STARTER, at)
        assertEquals(Shape.Starter, s)
        assertEquals(listOf(43.7995, 3.7825), back.starter)
    }

    @Test
    fun renameAndRenumber() {
        val c = lfnl.renameRunway(0, "Herbe nord").setRunwayRefs(0, "30", "12").normalized()
        assertEquals("herbe-nord", c.runways[0].id)
        // normalized() puts the lower designator first, with its own threshold.
        assertEquals(listOf("12", "30"), c.runways[0].ends.map { it.ref })
        assertEquals(lfnl.runways[0].ends[1].threshold, c.runways[0].ends[0].threshold)
        // Two runways with the same name get different ids.
        val twins = lfnl.renameRunway(1, "dur").renameRunway(2, "dur")
        assertNotEquals(twins.runways[1].id, twins.runways[2].id)
        assertEquals(emptyList(), twins.problems())
    }

    @Test
    fun normalizedKeepsTheLfnlFrame() {
        val n = lfnl.normalized()
        assertEquals(lfnl.axisBearingDeg, n.axisBearingDeg, 1.5)
        val shift = haversineM(GeoPoint(lfnl.reference[0], lfnl.reference[1]), GeoPoint(n.reference[0], n.reference[1]))
        assertTrue(shift < 100, "reference moved $shift m")
    }

    @Test
    fun problemsAreReported() {
        assertEquals(emptyList(), lfnl.problems())
        val bad = lfnl.copy(elevationM = Double.NaN, runways = emptyList(), icao = " ")
        assertEquals(3, bad.problems().size, bad.problems().toString())
        val close = lfnl.movePoint(Shape.Threshold(0, RunwayEnd.A), 0, GeoPoint(lfnl.runways[0].ends[1].threshold[0], lfnl.runways[0].ends[1].threshold[1]))
        assertTrue(close.problems().single().contains("trop proches"))
    }
}
