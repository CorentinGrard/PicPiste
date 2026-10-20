package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.NewShape
import fr.cvvm.picpiste.core.airfield.RunwayEnd
import fr.cvvm.picpiste.core.airfield.Shape
import fr.cvvm.picpiste.core.airfield.add
import fr.cvvm.picpiste.core.airfield.mapUpSnaps
import fr.cvvm.picpiste.core.airfield.translate
import fr.cvvm.picpiste.core.airfield.squared
import fr.cvvm.picpiste.core.airfield.widthM
import fr.cvvm.picpiste.core.airfield.moveRunwayEnd
import fr.cvvm.picpiste.core.airfield.setRunwayWidth
import fr.cvvm.picpiste.core.airfield.rotateRunway
import fr.cvvm.picpiste.core.airfield.codeFromName
import fr.cvvm.picpiste.core.airfield.newAirfield
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
        assertEquals("Threshold 30 herbe", lfnl.label(t))
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

        // A starter from an older file can still be removed; none can be added any more.
        assertTrue(Shape.Starter !in lfnl.delete(Shape.Starter).shapes())
        val (withBuilding, b) = lfnl.add(NewShape.BUILDING, at)
        assertEquals(Shape.Hangar(lfnl.hangars.size), b)
        assertEquals(lfnl.hangars.size + 1, withBuilding.hangars.size)
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
        assertTrue(close.problems().single().contains("too close"))
    }

    @Test
    fun newAirfieldByHandIsValidAndUsable() {
        val at = GeoPoint(44.0, 5.0)
        val c = newAirfield("LESMEES", "Les Mées", at, 410.0)
        assertTrue(c.problems().isEmpty(), c.problems().toString())
        assertEquals(1, c.runways.size)
        assertEquals(listOf("09", "27"), c.runways[0].ends.map { it.ref })
        assertTrue(haversineM(at, GeoPoint(c.reference[0], c.reference[1])) < 1.0)
        // Saved and read back like any other airfield.
        val back = Airfield.parse(Airfield.encode(c))
        assertEquals("Les Mées", back.name)
    }

    @Test
    fun codeFromTheName() {
        assertEquals("LESMEES", codeFromName("Les Mées", emptySet()))
        assertEquals("LESMEES2", codeFromName("Les Mées", setOf("LESMEES")))
        assertEquals("SAINTMAR", codeFromName("Saint-Martin-de-Londres", emptySet()))
        assertEquals("TERRAIN", codeFromName("  ", emptySet()))
    }

    private fun rw(c: fr.cvvm.picpiste.core.airfield.AirfieldConfig, i: Int) = Airfield(c).runways[i]

    @Test
    fun runwaysAreRectanglesBetweenTheirThresholds() {
        // The bundled outlines come from OpenStreetMap (5 to 10 corners): loaded as rectangles.
        lfnl.runways.forEach { r ->
            assertEquals(4, r.outline.size)
            assertEquals(r, r.squared(), "squaring twice changes nothing")
        }
        assertTrue(lfnl.runways[0].widthM() in 50.0..58.0, "grass width ${lfnl.runways[0].widthM()}")
        assertTrue(lfnl.runways[1].widthM() in 8.0..13.0, "paved width ${lfnl.runways[1].widthM()}")
    }

    @Test
    fun rotateKeepsLengthWidthAndCentre() {
        val before = rw(lfnl, 0)
        val turned = lfnl.rotateRunway(0, 30.0)
        val after = rw(turned, 0)
        assertEquals(before.length, after.length, 0.5)
        assertEquals(lfnl.runways[0].widthM(), turned.runways[0].widthM(), 0.5)
        assertTrue(((before.thresholdA + before.thresholdB) * 0.5).distanceTo((after.thresholdA + after.thresholdB) * 0.5) < 0.5)
        val diff = ((after.headingA - before.headingA) + 360) % 360
        assertEquals(30.0, diff, 0.5)
    }

    @Test
    fun widthAndEndsAndMove() {
        val wide = lfnl.setRunwayWidth(1, 25.0)
        assertEquals(25.0, wide.runways[1].widthM(), 0.3)
        val gps = GeoPoint(43.79905, 3.78471)
        val moved = wide.moveRunwayEnd(1, RunwayEnd.B, gps)
        assertEquals(listOf(43.79905, 3.78471), moved.runways[1].ends[1].threshold)
        assertEquals(25.0, moved.runways[1].widthM(), 0.3)
        assertEquals(4, moved.runways[1].outline.size)
        val shifted = lfnl.translate(Shape.RunwayOutline(0), 0.001, 0.0)
        assertEquals(lfnl.runways[0].ends[0].threshold[0] + 0.001, shifted.runways[0].ends[0].threshold[0], 1e-7)
        assertEquals(lfnl.runways[0].outline[2][0] + 0.001, shifted.runways[0].outline[2][0], 1e-7)
    }

    @Test
    fun numbersFollowTheRunwayWhenItTurns() {
        val c = fr.cvvm.picpiste.core.airfield.newAirfield("X", "X", GeoPoint(44.0, 5.0), 100.0)
        assertEquals(listOf("09", "27"), c.runways[0].ends.map { it.ref })
        val turned = c.rotateRunway(0, 50.0)
        assertEquals(listOf("14", "32"), turned.runways[0].ends.map { it.ref })
        // Numbers set by hand that do not match the heading stay.
        val custom = c.setRunwayRefs(0, "10L", "28R").rotateRunway(0, 50.0)
        assertEquals(listOf("10L", "28R"), custom.runways[0].ends.map { it.ref })
    }

    @Test
    fun mapSnapsToNorthAndToTheRunways() {
        val c = fr.cvvm.picpiste.core.airfield.newAirfield("X", "X", GeoPoint(44.0, 5.0), 100.0).rotateRunway(0, 50.0)
        val snaps = c.mapUpSnaps()
        fun snap(up: Double) = fr.cvvm.picpiste.core.airfield.snapMapUp(up, snaps, 5.0)
        // Runway heading 140 / 320: up along it (140, 320) or across it (50, 230).
        assertEquals(0.0, snap(3.0), 0.5)
        assertEquals(0.0, snap(357.0), 0.5)
        assertEquals(140.0, snap(137.0), 0.5)
        assertEquals(50.0, snap(53.0), 0.5)
        assertEquals(230.0, snap(226.0), 0.5)
        assertEquals(100.0, snap(100.0), 0.01) // nothing close: as is
    }
}
