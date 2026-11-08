package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.adsb.AdsbLol
import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.OsmImport
import fr.cvvm.picpiste.core.aprs.AircraftType
import fr.cvvm.picpiste.core.aprs.AprsFormatter
import fr.cvvm.picpiste.core.traffic.TrafficSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A real adsb.lol answer around Paris-CDG (test/resources/adsblol-LFPG.json, ODbL). */
class AdsbLolTest {
    private val answer = javaClass.getResource("/adsblol-LFPG.json")!!.readText()
    private val now = 1794162706500L

    private val cdg: Airfield by lazy {
        Airfield(OsmImport.build("LFPG", javaClass.getResource("/osm/LFPG.json")!!.readText(), 119.0).config)
    }

    @Test
    fun readsTheAircraft() {
        val all = AdsbLol.parse(answer).associateBy { it.beacon.address }
        assertEquals(setOf("39BB27", "398568", "39E683", "3CD051"), all.keys)

        val e190 = all.getValue("398568")
        assertEquals("HOP26LJ", e190.beacon.callsign)
        assertEquals("F-HBLI", e190.registration)
        assertEquals(AircraftType.JET, e190.beacon.aircraftType)
        // 550 ft on the standard setting, the crew's QNH 1022.4: 244 m above sea level.
        assertEquals(550 * 0.3048 + (1022.4 - 1013.25) * 8.3, e190.beacon.altitudeM, 0.1)
        assertEquals(131.5 * 1.852, e190.beacon.groundSpeedKmh, 0.01)
        assertEquals(-736 * 0.3048 / 60, e190.beacon.climbMs!!, 0.01)
        assertEquals(85.2, e190.beacon.trackDeg!!, 0.01)
        // Its position is 4.658 s older than the answer.
        assertEquals("183141", e190.beacon.timeHms)

        val taxiing = all.getValue("39E683")
        assertTrue(taxiing.onGround)
        assertEquals("F-HZUD", taxiing.registration)
        assertNull(taxiing.beacon.callsign)
        assertNull(taxiing.beacon.trackDeg)
    }

    @Test
    fun localQnhForAircraftThatDoNotSendTheirs() {
        val text = """{"now":$now,"ac":[
            {"hex":"aaaaaa","lat":49.0,"lon":2.5,"alt_baro":1000,"nav_qnh":1020.0,"seen_pos":1},
            {"hex":"bbbbbb","lat":49.0,"lon":2.5,"alt_baro":1000,"alt_geom":1200,"seen_pos":1},
            {"hex":"~cccccc","lat":49.0,"lon":2.5,"alt_baro":1000,"seen_pos":1},
            {"hex":"dddddd","lat":49.0,"lon":2.5,"alt_baro":1000,"seen_pos":300}
        ]}"""
        val all = AdsbLol.parse(text).associateBy { it.beacon.address }
        // No ICAO address (TIS-B "~…"), or a position 5 minutes old: left out.
        assertEquals(setOf("AAAAAA", "BBBBBB"), all.keys)
        assertEquals(304.8 + (1020 - 1013.25) * 8.3, all.getValue("BBBBBB").beacon.altitudeM, 0.1)
        // Unknown category: powered (an unknown OGN type would be drawn as a glider).
        assertEquals(AircraftType.POWERED, all.getValue("BBBBBB").beacon.aircraftType)
        assertTrue(AdsbLol.parse("not json").isEmpty())
        assertTrue(AdsbLol.parse("""{"msg":"rate limited"}""").isEmpty())
    }

    @Test
    fun requestAroundTheField() {
        // 6 km asked for: 3.24 nautical miles, rounded up.
        assertEquals("https://api.adsb.lol/v2/point/49.0097/2.5479/4", AdsbLol.url(49.0097, 2.5479, 6))
    }

    @Test
    fun aircraftOnTheRadar() {
        val e = PicPisteEngine(cdg)
        e.onAdsbLol(answer, now)
        val tracked = e.snapshot(now + 1_000)
        val e190 = assertNotNull(tracked.aircraft.find { it.address == "398568" })
        assertEquals("F-HBLI", e190.identity.registration)
        assertEquals(AircraftType.JET, e190.identity.type)
        // On the ground: on the field, whatever the file's elevation.
        val taxiing = assertNotNull(tracked.aircraft.find { it.address == "39E683" })
        assertEquals(0.0, taxiing.fix.aglM, 0.01)
        // The Citation climbing through 1 000 m: above the display ceiling, until it is lifted.
        assertTrue(tracked.aircraft.none { it.address == "3CD051" })
        e.settings = TrafficSettings(ceilingAglM = Double.MAX_VALUE)
        assertTrue(e.snapshot(now + 1_000).aircraft.any { it.address == "3CD051" })
    }

    @Test
    fun ognPositionsComeFirst() {
        val e = PicPisteEngine(cdg)
        // The same E190 heard through OGN a moment before, at 300 m.
        val geo = cdg.frame.toGeo(cdg.frame.toLocal(fr.cvvm.picpiste.core.geo.GeoPoint(49.0, 2.52)))
        e.onLine(AprsFormatter.aircraft("398568", AircraftType.JET, geo, 300.0, 85.0, 240.0, -3.5, now - 2_000), now - 1_000)
        e.onAdsbLol(answer, now)
        val e190 = assertNotNull(e.snapshot(now).aircraft.find { it.address == "398568" })
        assertEquals(300.0, e190.fix.altitudeM, 0.5)
        // Not heard through OGN for a while: adsb.lol takes over.
        e.onAdsbLol(answer.replace(Regex("\"now\":\\s*\\d+"), "\"now\":${now + 30_000}"), now + 30_000)
        assertEquals(244.0, e.snapshot(now + 30_000).aircraft.first { it.address == "398568" }.fix.altitudeM, 1.0)
    }
}
