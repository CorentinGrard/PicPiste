package fr.cvvm.picpiste.core.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the sun is, to switch the phone between light (day) and dark (night) colours from the
 * airfield's real sunrise and sunset rather than the phone's dark-mode schedule.
 * Low-precision formula (about 0.1°), plenty for that.
 */
object Sun {
    /** Sunrise and sunset: the top of the sun on the horizon, with refraction. */
    const val HORIZON_DEG = -0.833

    /** Sun elevation above the horizon in degrees at [timeMs], seen from [lat], [lon]. */
    fun elevationDeg(lat: Double, lon: Double, timeMs: Long): Double {
        val d = timeMs / 86_400_000.0 - 10_957.5 // days since J2000.0 (2000-01-01 12:00 UTC)
        val g = Math.toRadians(normalizeDeg(357.529 + 0.98560028 * d)) // mean anomaly
        val q = normalizeDeg(280.459 + 0.98564736 * d) // mean longitude
        val l = Math.toRadians(q + 1.915 * sin(g) + 0.020 * sin(2 * g)) // ecliptic longitude
        val e = Math.toRadians(23.439 - 0.00000036 * d) // obliquity
        val ra = atan2(cos(e) * sin(l), cos(l))
        val dec = asin(sin(e) * sin(l))
        val gmstDeg = normalizeDeg(280.46061837 + 360.98564736629 * d)
        val ha = Math.toRadians(gmstDeg + lon) - ra
        val phi = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(ha)))
    }

    fun isDaylight(lat: Double, lon: Double, timeMs: Long): Boolean = elevationDeg(lat, lon, timeMs) >= HORIZON_DEG
}
