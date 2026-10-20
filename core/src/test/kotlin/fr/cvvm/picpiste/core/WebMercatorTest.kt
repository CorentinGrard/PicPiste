package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.geo.GeoPoint
import fr.cvvm.picpiste.core.geo.WebMercator
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebMercatorTest {
    @Test
    fun knownTile() {
        // Paris, Notre-Dame, zoom 17: tile 66391 / 45094.
        val (x, y) = WebMercator.tileXY(GeoPoint(48.85296, 2.34990), 17)
        assertEquals(66391, floor(x).toInt())
        assertEquals(45094, floor(y).toInt())
    }

    @Test
    fun cornerIsTheInverseOfTileXY() {
        val p = WebMercator.corner(15, 16_700, 11_900)
        val (x, y) = WebMercator.tileXY(p, 15)
        assertTrue(abs(x - 16_700) < 1e-6 && abs(y - 11_900) < 1e-6, "$x $y")
    }

    @Test
    fun zoomMatchesScreenScale() {
        // At 45°N a zoom-17 pixel is about 0.84 m: 1.2 screen px a metre is closest to zoom 17.
        assertEquals(17, WebMercator.zoomFor(1.2, 45.0, 19))
        assertEquals(19, WebMercator.zoomFor(40.0, 45.0, 19))
        assertEquals(1, WebMercator.zoomFor(1e-9, 45.0, 19))
    }
}
