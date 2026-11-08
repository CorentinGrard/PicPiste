package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.AirfieldDownloader
import fr.cvvm.picpiste.core.airfield.AirfieldImportException
import fr.cvvm.picpiste.core.airfield.OsmImport
import fr.cvvm.picpiste.core.airfield.RunwayEnd
import fr.cvvm.picpiste.core.airfield.problems
import fr.cvvm.picpiste.core.airfield.refHeading
import fr.cvvm.picpiste.core.airfield.runwayNumber
import fr.cvvm.picpiste.core.ddb.HttpStatusException
import fr.cvvm.picpiste.core.i18n.Texts
import fr.cvvm.picpiste.core.geo.angleDiffDeg
import fr.cvvm.picpiste.core.geo.normalizeDeg
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Real Overpass answers saved in test/resources/osm (OpenStreetMap contributors, ODbL). */
class OsmImportTest {
    private fun fixture(code: String) = javaClass.getResource("/osm/$code.json")!!.readText()

    private fun import(code: String, elevation: Double? = null) = OsmImport.build(code, fixture(code), elevation)

    /** Each runway end points the way its designator says (true vs magnetic: within 20°). */
    private fun assertConsistent(a: Airfield) {
        for (rw in a.runways) for (end in RunwayEnd.entries) {
            val h = refHeading(rw.ref(end))!!
            assertTrue(angleDiffDeg(h, rw.heading(end)) < 20, "${rw.label(end)}: heading ${rw.heading(end)}")
            assertTrue(rw.outline.contains((rw.thresholdA + rw.thresholdB) * 0.5), "${rw.name}: middle inside its outline")
        }
        assertTrue(a.runways.all { it.ref(RunwayEnd.A) < it.ref(RunwayEnd.B) }, "A is the lower designator")
    }

    @Test
    fun lfnlGivesThreeParallelRunways() {
        val r = import("LFNL", elevation = 182.0)
        assertEquals(emptyList(), r.config.problems())
        val a = Airfield(r.config)
        assertConsistent(a)
        assertEquals(3, a.runways.size)
        assertEquals(listOf("12" to "30"), a.runways.map { it.ref(RunwayEnd.A) to it.ref(RunwayEnd.B) }.distinct())
        assertEquals(1, a.runways.count { it.isGrass })
        assertTrue(a.runways.all { it.length in 350.0..700.0 }, a.runways.map { it.length }.toString())
        assertEquals("Saint-Martin-de-Londres", r.config.name)
        assertEquals(182.0, r.config.elevationM)
        assertTrue(r.warnings.any { it.startsWith("Field elevation estimated") }, r.warnings.toString())
        assertTrue(a.config.boundary.size > 10)
        assertTrue(a.hangars.size >= 4 && a.taxiways.isNotEmpty() && a.aprons.isNotEmpty())
        // Same frame axis as the hand-made file (119.2°), within a few degrees.
        assertTrue(angleDiffDeg(r.config.axisBearingDeg, 119.2) < 5, "axis ${r.config.axisBearingDeg}")
        // The map shows the main runway across the screen, the top as close to north as can be.
        assertEquals(normalizeDeg(r.config.axisBearingDeg - 90), r.config.mapUpDeg, 0.1)
        assertEquals(0.0, Airfield(r.config).mapRotationDeg, 0.1)
        // The three runways and the grass between them still form one runway zone.
        val herbe = a.runways.single { it.isGrass }
        val southmost = a.runways.minBy { it.thresholdA.y }
        val between = (herbe.thresholdA + herbe.thresholdB) * 0.5 * 0.5 + (southmost.thresholdA + southmost.thresholdB) * 0.5 * 0.5
        assertTrue(a.inRunwayZone(between))
    }

    @Test
    fun lfngSkipsDisplacedThreshold() {
        val r = import("LFNG", elevation = 2.0)
        val a = Airfield(r.config)
        assertConsistent(a)
        assertEquals(listOf("14"), a.runways.map { it.ref(RunwayEnd.A) })
        // No outline in OSM: a rectangle from the 30 m width tag.
        assertEquals(4, r.config.runways.single().outline.size)
    }

    @Test
    fun lfleKeepsLetteredRunwaysAndDropsTinyStrips() {
        val r = import("LFLE", elevation = 296.0)
        assertEquals(emptyList(), r.config.problems())
        val a = Airfield(r.config)
        assertConsistent(a)
        assertEquals(setOf("14L", "14"), a.runways.map { it.ref(RunwayEnd.A) }.toSet())
        assertEquals("32R", a.runways.single { it.isGrass }.ref(RunwayEnd.B))
        // From the air, only the direction: "Finale 14", not "Finale 14L".
        assertEquals("14", a.runways.single { it.isGrass }.direction(RunwayEnd.A))
    }

    @Test
    fun lfptHasCrossingRunwaysAndElevationFromOsm() {
        val r = import("LFPT")
        assertEquals(emptyList(), r.config.problems())
        assertTrue(r.warnings.none { it.contains("elevation") }, r.warnings.toString())
        assertEquals(99.0, r.config.elevationM)
        val a = Airfield(r.config)
        assertConsistent(a)
        assertEquals(setOf("04", "12"), a.runways.map { it.ref(RunwayEnd.A) }.toSet())
        // Relation outline stitched from its member ways.
        assertTrue(r.config.boundary.size > 20, "boundary ${r.config.boundary.size} points")
        // Crossing runways: no "grass between runways" strips, just the two runways.
        assertEquals(2, a.runwayZone.size)
    }

    /** The weekly published files (tools/airfields) for two big airports. */
    @Test
    fun bigAirportsFromThePublishedFiles() {
        val cdg = Airfield(import("LFPG", elevation = 119.0).config)
        assertConsistent(cdg)
        // Four parallel runways, and a grass strip with no designator in OSM, pointing west:
        // its other end was "heading + 180", over 360 ("37"), and the import crashed.
        assertEquals(setOf("08L", "08R", "09L", "09R"), cdg.runways.filter { !it.isGrass }.map { it.ref(RunwayEnd.A) }.toSet())
        assertEquals(1, cdg.runways.count { it.isGrass })

        val mrs = Airfield(import("LFML", elevation = 21.0).config)
        assertConsistent(mrs)
        assertEquals(setOf("13L", "13R"), mrs.runways.map { it.ref(RunwayEnd.A) }.toSet())
    }

    @Test
    fun runwaysFarFromTheAerodromeAreLeftOut() {
        // An aerodrome node, a runway beside it, and one 30 km away (a range, or a tagging error).
        val near = """{"type":"way","id":1,"tags":{"aeroway":"runway","ref":"09/27"},"geometry":[{"lat":45.0,"lon":5.0},{"lat":45.0,"lon":5.01}]}"""
        val far = """{"type":"way","id":2,"tags":{"aeroway":"runway","ref":"18/36"},"geometry":[{"lat":45.27,"lon":5.0},{"lat":45.28,"lon":5.0}]}"""
        val ad = """{"type":"node","id":3,"lat":45.0,"lon":5.005,"tags":{"aeroway":"aerodrome","icao":"ZZZZ"}}"""
        val r = OsmImport.build("ZZZZ", """{"elements":[$near,$far,$ad]}""", 200.0)
        assertEquals(listOf("09"), r.config.runways.map { it.ends[0].ref })
        assertEquals(emptyList(), r.config.problems())
    }

    @Test
    fun runwayNumbersFromAnyHeading() {
        assertEquals(4, runwayNumber(42.0))
        assertEquals(36, runwayNumber(3.0))
        assertEquals(36, runwayNumber(358.0))
        // The other end of a runway heading 265°: 445°, that is 085°.
        assertEquals(9, runwayNumber(265.0 + 180))
        assertEquals(36, runwayNumber(-2.0))
    }

    @Test
    fun configSurvivesTheJsonRoundTrip() {
        val r = import("LFPT")
        assertEquals(r.config, Airfield.decode(Airfield.encode(r.config)))
    }

    @Test
    fun unknownCodeAndBrokenFiles() {
        val empty = """{"elements": []}"""
        assertFailsWith<AirfieldImportException> { OsmImport.build("ZZZZ", empty) }
        val e = assertFailsWith<IllegalArgumentException> { Airfield.decode("""{"hello": 1}""") }
        assertEquals("This file is not a PicPiste airfield.", e.message)
    }

    @Test
    fun refsAreParsedLikeOnTheRunway() {
        assertEquals("12" to "30", OsmImport.parseRefs("12-30"))
        assertEquals("09" to "27", OsmImport.parseRefs("9/27"))
        assertEquals("14L" to "32R", OsmImport.parseRefs("14l/32r"))
        assertEquals(null, OsmImport.parseRefs("H1"))
        assertEquals(182.0, OsmImport.parseElevation("182 m"))
        assertEquals(304.8, OsmImport.parseElevation("1000 ft")!!, 1e-9)
    }

    @Test
    fun osmApiAnswerGivesTheSameRunwaysAsOverpass() {
        for ((code, ele) in listOf("LFNL" to 182.0, "LFLE" to 296.0)) {
            val viaApi = OsmImport.build(code, fixture("api-$code"), ele).config
            val viaOverpass = import(code, ele).config
            assertEquals(emptyList(), viaApi.problems(), code)
            assertEquals(viaOverpass.runways.map { it.ends.map { e -> e.ref } }.sortedBy { it.toString() },
                viaApi.runways.map { it.ends.map { e -> e.ref } }.sortedBy { it.toString() }, code)
            assertConsistent(Airfield(viaApi))
            assertTrue(viaApi.boundary.size >= 3, "$code boundary")
            // The OSM API gives everything in the box: hangars of the neighbours must not come along.
            val field = Airfield(viaApi)
            assertTrue(field.hangars.all { h -> h.points.any { field.boundary.containsWithin(it, 200.0) } }, "$code hangars off the field")
        }
    }

    @Test
    fun downloaderUsesOverpassAndAsksForTheElevation() {
        val gets = mutableListOf<String>()
        val d = AirfieldDownloader(
            post = { _, _ -> fixture("LFNL") },
            get = { url -> gets += url; """{"elevation":[181.0]}""" },
            servers = listOf("overpass"),
            published = emptyList(),
        )
        val r = d.download(" lfnl ")
        assertEquals("LFNL", r.config.icao)
        assertEquals(181.0, r.config.elevationM)
        assertTrue(gets.single().startsWith(AirfieldDownloader.ELEVATION_URL))
    }

    @Test
    fun downloaderFallsBackToTheOsmApiWhenOverpassIsBusy() {
        val gets = mutableListOf<String>()
        val nominatim = """[{"category":"aeroway","type":"aerodrome","boundingbox":["45.5554951","45.5659287","5.9698634","5.9834984"]}]"""
        val d = AirfieldDownloader(
            post = { _, _ -> """{"elements":[], "remark": "runtime error: Query timed out in \"query\" at line 3"}""" },
            get = { url ->
                gets += url
                when {
                    url.startsWith(AirfieldDownloader.NOMINATIM_URL) -> nominatim
                    url.startsWith(AirfieldDownloader.OSM_API_URL) -> fixture("api-LFLE")
                    else -> """{"elevation":[296.0]}"""
                }
            },
            servers = listOf("busy"),
            published = emptyList(),
        )
        val r = d.download("LFLE")
        assertEquals(setOf("14L", "14"), r.config.runways.map { it.ends[0].ref }.toSet())
        assertTrue(gets[1].endsWith("bbox=5.966863,45.552495,5.986498,45.568929"), gets[1])
    }

    /** The airfield found by Nominatim when Overpass is busy, and the OSM API answering with [api]. */
    private fun busyOverpass(api: (bbox: List<Double>) -> String) = AirfieldDownloader(
        post = { _, _ -> "# 502 Gateway Timeout" },
        get = { url ->
            when {
                url.startsWith(AirfieldDownloader.NOMINATIM_URL) ->
                    """[{"category":"aeroway","type":"aerodrome","boundingbox":["45.5554951","45.5659287","5.9698634","5.9834984"]}]"""
                url.startsWith(AirfieldDownloader.OSM_API_URL) -> api(url.substringAfter("bbox=").split(',').map { it.toDouble() })
                else -> """{"elevation":[296.0]}"""
            }
        },
        servers = listOf("busy"),
        published = emptyList(),
    )

    @Test
    fun downloaderSplitsABigAirportForTheOsmApi() {
        val whole = busyOverpass { fixture("api-LFLE") }.download("LFLE")
        val boxes = mutableListOf<List<Double>>()
        // "Too many nodes" for the whole box (0.0196° wide), fine for its quarters.
        val tiled = busyOverpass { b ->
            boxes += b
            if (b[2] - b[0] > 0.015) throw HttpStatusException(400) else fixture("api-LFLE")
        }.download("LFLE")
        assertEquals(1 + 4, boxes.size)
        // The four quarters cover the whole box, without overlapping.
        assertEquals(boxes[0][0], boxes.drop(1).minOf { it[0] }, 1e-6)
        assertEquals(boxes[0][2], boxes.drop(1).maxOf { it[2] }, 1e-6)
        assertEquals(boxes[0][1], boxes.drop(1).minOf { it[1] }, 1e-6)
        assertEquals(boxes[0][3], boxes.drop(1).maxOf { it[3] }, 1e-6)
        // Each tile here holds the whole airfield: merged, nothing is there twice, nothing is lost.
        assertEquals(whole.config, tiled.config)
        assertTrue(tiled.config.taxiways.isNotEmpty())
    }

    @Test
    fun downloaderSaysWhenAnAirportIsTooBigForTheOsmApi() {
        var calls = 0
        val e = assertFailsWith<AirfieldImportException> {
            busyOverpass { calls++; throw HttpStatusException(400) }.download("LFLE")
        }
        assertEquals(Texts.current.airfieldTooBig, e.message)
        assertTrue(calls <= AirfieldDownloader.MAX_TILES, "$calls requests")
        // Another HTTP error (rate limit…) is not about size.
        val limited = assertFailsWith<AirfieldImportException> { busyOverpass { throw HttpStatusException(509) }.download("LFLE") }
        assertEquals(Texts.current.osmNotResponding, limited.message)
    }

    @Test
    fun downloaderTakesThePublishedFileFirst() {
        val asked = mutableListOf<String>()
        val d = AirfieldDownloader(
            post = { _, _ -> error("no Overpass when the file is published") },
            get = { """{"elevation":[181.0]}""" },
            servers = listOf("overpass"),
            published = listOf("https://down/a/", "https://copy/a/"),
            getPublished = { url ->
                asked += url
                if (url.startsWith("https://down/")) throw IOException("offline") else fixture("LFNL")
            },
        )
        val r = d.download("lfnl")
        assertEquals("LFNL", r.config.icao)
        // The first copy down: the next one.
        assertEquals(listOf("https://down/a/LFNL.json.gz", "https://copy/a/LFNL.json.gz"), asked)
    }

    @Test
    fun downloaderGoesToOpenStreetMapWhenTheAirfieldIsNotPublished() {
        val asked = mutableListOf<String>()
        var posts = 0
        val d = AirfieldDownloader(
            post = { _, _ -> posts++; fixture("LFNL") },
            get = { """{"elevation":[181.0]}""" },
            servers = listOf("overpass"),
            published = listOf("https://pages/a/", "https://copy/a/"),
            getPublished = { url -> asked += url; throw HttpStatusException(404) },
        )
        assertEquals("LFNL", d.download("LFNL").config.icao)
        assertEquals(1, posts)
        // Not published: another copy has the same files, no need to ask it.
        assertEquals(listOf("https://pages/a/LFNL.json.gz"), asked)
    }

    @Test
    fun downloaderGivesUpWithAMessage() {
        val d = AirfieldDownloader(post = { _, _ -> "# 502 Gateway Timeout" }, get = { throw IOException("offline") }, servers = listOf("a"), published = emptyList())
        val e = assertFailsWith<AirfieldImportException> { d.download("LFNL") }
        assertTrue(e.message!!.startsWith("OpenStreetMap is not responding"), e.message)
        assertFailsWith<AirfieldImportException> { d.download("LF NL!") }
        val unknown = AirfieldDownloader(post = { _, _ -> """{"elements":[]}""" }, servers = listOf("a"), published = emptyList())
        assertEquals("No airfield “ZZZZ” in OpenStreetMap.", assertFailsWith<AirfieldImportException> { unknown.download("ZZZZ") }.message)
    }
}
