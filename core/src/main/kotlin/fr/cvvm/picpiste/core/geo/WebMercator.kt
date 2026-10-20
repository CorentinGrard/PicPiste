package fr.cvvm.picpiste.core.geo

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

/** Web map tiles (OpenStreetMap / "XYZ" numbering): 256 px squares, y counted from the north. */
object WebMercator {
    const val TILE_PX = 256
    private const val EQUATOR_M_PER_PX_Z0 = 156_543.033_92

    /** Fractional tile coordinates of [p] at zoom [z]. */
    fun tileXY(p: GeoPoint, z: Int): Pair<Double, Double> {
        val n = (1 shl z).toDouble()
        val lat = Math.toRadians(p.lat.coerceIn(-85.0511, 85.0511))
        val x = (p.lon + 180.0) / 360.0 * n
        val y = (1.0 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2.0 * n
        return x to y
    }

    /** North-west corner of tile ([x], [y]) at zoom [z]; x + 1 / y + 1 give the other corners. */
    fun corner(z: Int, x: Int, y: Int): GeoPoint {
        val n = (1 shl z).toDouble()
        val lon = x / n * 360.0 - 180.0
        val lat = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / n))))
        return GeoPoint(lat, lon)
    }

    /** Ground metres per tile pixel at zoom [z] and latitude [lat]. */
    fun metresPerPixel(z: Int, lat: Double) = EQUATOR_M_PER_PX_Z0 * cos(Math.toRadians(lat)) / (1 shl z)

    /** Zoom whose tile pixels are closest to screen pixels when the map shows [pxPerM] screen pixels a metre. */
    fun zoomFor(pxPerM: Double, lat: Double, maxZoom: Int): Int {
        val z = (ln(EQUATOR_M_PER_PX_Z0 * cos(Math.toRadians(lat)) * pxPerM) / ln(2.0)).roundToInt()
        return z.coerceIn(1, maxZoom)
    }
}
