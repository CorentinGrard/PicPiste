package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.geo.Sun
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SunTest {
    // Saint-Martin-de-Londres
    private val lat = 43.80
    private val lon = 3.78
    private fun at(iso: String) = ZonedDateTime.parse(iso).toInstant().toEpochMilli()

    @Test
    fun noonInSummerIsHighAndMidnightIsDark() {
        val noon = Sun.elevationDeg(lat, lon, at("2026-06-21T11:45:00Z"))
        assertTrue(noon in 67.0..71.0, "summer noon elevation $noon") // 90 - 43.8 + 23.4 ≈ 69.6
        assertFalse(Sun.isDaylight(lat, lon, at("2026-06-21T23:45:00Z")))
    }

    @Test
    fun sunsetAroundTheRightTime() {
        // Sunset in Montpellier on 19 October 2026 is about 18:52 local time (16:52 UTC).
        assertTrue(Sun.isDaylight(lat, lon, at("2026-10-19T16:35:00Z")))
        assertFalse(Sun.isDaylight(lat, lon, at("2026-10-19T17:10:00Z")))
        // Sunrise about 08:12 local (06:12 UTC).
        assertFalse(Sun.isDaylight(lat, lon, at("2026-10-19T05:55:00Z")))
        assertTrue(Sun.isDaylight(lat, lon, at("2026-10-19T06:30:00Z")))
    }
}
