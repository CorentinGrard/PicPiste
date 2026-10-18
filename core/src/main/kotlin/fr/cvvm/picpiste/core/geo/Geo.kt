package fr.cvvm.picpiste.core.geo

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_M = 6_371_008.8

data class GeoPoint(val lat: Double, val lon: Double)

/** A point in metres in an airfield's local frame. */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(k: Double) = Vec2(x * k, y * k)
    infix fun dot(o: Vec2) = x * o.x + y * o.y
    infix fun cross(o: Vec2) = x * o.y - y * o.x
    val length: Double get() = hypot(x, y)
    fun normalized(): Vec2 = length.let { if (it == 0.0) this else Vec2(x / it, y / it) }
    fun distanceTo(o: Vec2) = (this - o).length
}

/**
 * Flat local frame centred on an airfield, rotated so the runway axis is the x axis.
 *
 * x grows along [axisBearingDeg] (from the 12 end towards the 30 end at LFNL),
 * y grows to the left of that axis. Drawn with y up, this is the radar view.
 * Accurate to well under a metre within 10 km, which is all we need.
 */
class LocalFrame(val origin: GeoPoint, val axisBearingDeg: Double) {
    private val cosLat = cos(Math.toRadians(origin.lat))
    private val axis = Math.toRadians(axisBearingDeg)
    private val sinA = sin(axis)
    private val cosA = cos(axis)

    fun toLocal(p: GeoPoint): Vec2 {
        val east = Math.toRadians(p.lon - origin.lon) * cosLat * EARTH_RADIUS_M
        val north = Math.toRadians(p.lat - origin.lat) * EARTH_RADIUS_M
        // Unit vector along the axis is (sinA, cosA) in (east, north); left of it is (-cosA, sinA).
        return Vec2(east * sinA + north * cosA, -east * cosA + north * sinA)
    }

    fun toGeo(v: Vec2): GeoPoint {
        val east = v.x * sinA - v.y * cosA
        val north = v.x * cosA + v.y * sinA
        return GeoPoint(
            lat = origin.lat + Math.toDegrees(north / EARTH_RADIUS_M),
            lon = origin.lon + Math.toDegrees(east / (EARTH_RADIUS_M * cosLat)),
        )
    }

    /** Unit vector in the local frame for a true bearing (degrees). */
    fun directionOf(bearingDeg: Double): Vec2 {
        val rel = Math.toRadians(bearingDeg - axisBearingDeg)
        return Vec2(cos(rel), -sin(rel))
    }

    /** True bearing (degrees, 0–360) of a local direction vector. */
    fun bearingOf(dir: Vec2): Double {
        val rel = Math.toDegrees(atan2(-dir.y, dir.x))
        return normalizeDeg(axisBearingDeg + rel)
    }
}

fun normalizeDeg(d: Double): Double = ((d % 360.0) + 360.0) % 360.0

/** Smallest absolute difference between two bearings, 0–180. */
fun angleDiffDeg(a: Double, b: Double): Double {
    val d = abs(normalizeDeg(a) - normalizeDeg(b))
    return if (d > 180) 360 - d else d
}

fun haversineM(a: GeoPoint, b: GeoPoint): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
}

/** A simple polygon in local metres. */
class Polygon(val points: List<Vec2>) {
    init {
        require(points.size >= 3) { "A polygon needs at least 3 points" }
    }

    fun contains(p: Vec2): Boolean {
        var inside = false
        var j = points.lastIndex
        for (i in points.indices) {
            val a = points[i]
            val b = points[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Distance from [p] to the polygon's edge (0 on the edge). */
    fun distanceToEdge(p: Vec2): Double {
        var best = Double.MAX_VALUE
        var j = points.lastIndex
        for (i in points.indices) {
            best = minOf(best, distanceToSegment(p, points[j], points[i]))
            j = i
        }
        return best
    }

    /** True when [p] is inside, or outside by at most [marginM]. */
    fun containsWithin(p: Vec2, marginM: Double): Boolean =
        contains(p) || (marginM > 0 && distanceToEdge(p) <= marginM)

    /** True when [p] is inside and at least [marginM] away from the edge. */
    fun containsDeep(p: Vec2, marginM: Double): Boolean =
        contains(p) && (marginM <= 0 || distanceToEdge(p) >= marginM)
}

fun distanceToSegment(p: Vec2, a: Vec2, b: Vec2): Double {
    val ab = b - a
    val len2 = ab dot ab
    if (len2 == 0.0) return p.distanceTo(a)
    val t = (((p - a) dot ab) / len2).coerceIn(0.0, 1.0)
    return p.distanceTo(a + ab * t)
}
