package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.newAirfield
import fr.cvvm.picpiste.core.airfield.setRunwayWidth
import fr.cvvm.picpiste.core.geo.GeoPoint
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.i18n.Texts
import fr.cvvm.picpiste.core.traffic.ElevationCalibration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunwayDetectionTest {
    /** One 600 m runway, east–west, centred on the reference. */
    private val field = Airfield(newAirfield("TEST", "Test", GeoPoint(45.0, 1.0), 100.0))
    private val rw = field.runways.single()

    private fun at(along: Double, lateral: Double) =
        rw.thresholdA + rw.dirA * along + Vec2(-rw.dirA.y, rw.dirA.x) * lateral

    @Test
    fun taxiingAcrossTheEndOfARunwayIsNotOnIt() {
        val nearEnd = at(rw.length - 20, 0.0)
        assertFalse(rw.hasOnIt(nearEnd, trackDeg = rw.headingA + 90), "crossing the end")
        assertTrue(rw.hasOnIt(nearEnd, trackDeg = rw.headingA), "rolling along it")
        assertTrue(rw.hasOnIt(at(rw.length / 2, 0.0), trackDeg = rw.headingA + 90), "crossing the middle")
        assertNull(field.runwayUnder(at(rw.length - 20, 0.0), rw.headingB + 90))
    }

    @Test
    fun gpsMarginOnlyOnNarrowRunways() {
        // 15 m beside a 30 m wide runway, none beside an 80 m wide grass strip.
        val narrow = Airfield(field.config.setRunwayWidth(0, 30.0)).runways.single()
        val mid = narrow.thresholdA + narrow.dirA * (narrow.length / 2)
        val side = Vec2(-narrow.dirA.y, narrow.dirA.x)
        assertTrue(narrow.hasOnIt(mid + side * 25.0, null))
        assertFalse(narrow.hasOnIt(mid + side * 35.0, null))
        val wide = Airfield(field.config.setRunwayWidth(0, 80.0)).runways.single()
        assertTrue(wide.hasOnIt(mid + side * 38.0, null))
        assertFalse(wide.hasOnIt(mid + side * 45.0, null))
    }

    @Test
    fun runwayLettersInEachLanguage() {
        assertEquals("05G", Texts.French.runwayRef("05L"))
        assertEquals("23D", Texts.French.runwayRef("23R"))
        assertEquals("14", Texts.French.runwayRef("14"))
        assertEquals("23 droite", Texts.French.spokenRunwayRef("23R"))
        assertEquals("05L", Texts.English.runwayRef("05L"))
        assertEquals("05 left", Texts.English.spokenRunwayRef("05L"))
    }

    @Test
    fun fieldAltitudeComesFromAircraftStoppedOnTheField() {
        val cal = ElevationCalibration(field)
        assertEquals(100.0, cal.fieldAltitudeM)
        val onField = at(rw.length / 2, 0.0)
        repeat(5) {
            cal.onFix("A", onField, 79.0, 0.0)
            cal.onFix("B", onField, 81.0, 1.0)
            cal.onFix("C", onField, 300.0, 0.0) // not on the ground here
            cal.onFix("D", onField, 150.0, 90.0) // moving: ignored
        }
        assertEquals(80.0, cal.fieldAltitudeM, 0.01)
        assertEquals(-20.0, cal.offsetM, 0.01)
    }
}
