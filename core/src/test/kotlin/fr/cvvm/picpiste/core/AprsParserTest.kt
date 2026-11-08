package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.aprs.AddressType
import fr.cvvm.picpiste.core.aprs.AircraftType
import fr.cvvm.picpiste.core.aprs.AprsFormatter
import fr.cvvm.picpiste.core.aprs.AprsParser
import fr.cvvm.picpiste.core.geo.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AprsParserTest {
    @Test
    fun parsesFlarmGliderBeacon() {
        val b = AprsParser.parse(
            "FLRDD1234>OGFLR,qAS,LFNL:/123456h4347.95N/00346.92E'299/054/A=000892 !W28! id06DD1234 -019fpm +0.0rot 5.5dB 0e -1.2kHz gps2x3",
        )
        assertNotNull(b)
        assertEquals("DD1234", b.address)
        assertEquals(AircraftType.GLIDER, b.aircraftType)
        assertEquals(AddressType.FLARM, b.addressType)
        assertFalse(b.noTrack)
        assertEquals(43 + 47.952 / 60, b.lat, 1e-9)
        assertEquals(3 + 46.928 / 60, b.lon, 1e-9)
        assertEquals(892 * 0.3048, b.altitudeM, 1e-6)
        assertEquals(299.0, b.trackDeg)
        assertEquals(54 * 1.852, b.groundSpeedKmh, 1e-9)
        assertEquals(-19 * 0.00508, b.climbMs!!, 1e-9)
        assertEquals("LFNL", b.receiver)
        assertEquals("123456", b.timeHms)
    }

    @Test
    fun readsTowPlaneAndNoTrackFlags() {
        // 0x4A = no-track (0x40) + tow plane (2 << 2) + FLARM (2)
        val b = AprsParser.parse("FLRDDAAAA>OGFLR,qAS,Foo:/101010h4348.00N/00347.00E'000/000/A=000600 id4ADDAAAA +000fpm")
        assertNotNull(b)
        assertEquals(AircraftType.TOW_PLANE, b.aircraftType)
        assertTrue(b.noTrack)
        assertNull(b.trackDeg, "course 000 means unknown")
    }

    @Test
    fun ignoresServerCommentsAndReceivers() {
        assertNull(AprsParser.parse("# aprsc 2.1.15-gc67551b 18 Oct 2026 10:00:00 GMT GLIDERN1 1.2.3.4:14580"))
        assertNull(AprsParser.parse("LFNL>OGNSDR,TCPIP*,qAC,GLIDERN2:/101010h4347.95NI00346.92E&/A=000600"))
        assertNull(AprsParser.parse("garbage"))
        assertNull(AprsParser.parse(""))
    }

    @Test
    fun formatterRoundTrips() {
        val geo = GeoPoint(43.7991234, 3.7845678)
        val line = AprsFormatter.aircraft("DD0A01", AircraftType.GLIDER, geo, 320.0, 297.0, 92.0, -1.5, 1_760_000_000_000)
        val b = AprsParser.parse(line)
        assertNotNull(b, line)
        assertEquals(geo.lat, b.lat, 1e-5)
        assertEquals(geo.lon, b.lon, 1e-5)
        assertEquals(320.0, b.altitudeM, 0.2)
        assertEquals(297.0, b.trackDeg)
        assertEquals(92.0, b.groundSpeedKmh, 1.0)
        assertEquals(-1.5, b.climbMs!!, 0.01)
    }

    @Test
    fun readsAdsbFlightId() {
        val b = AprsParser.parse(
            "ICA39BD74>OGADSB,qAS,AVX944:/150904h4631.07N\\00021.64W^102/360/A=025836 !W12! id2139BD74 +1280fpm FL253.93 A1:FHPLU Sq1773",
        )
        assertNotNull(b)
        assertEquals("FHPLU", b.callsign)
        assertNull(AprsParser.parse("FLRDD1234>OGFLR,qAS,LFNL:/123456h4347.95N/00346.92E'299/054/A=000892 id06DD1234")!!.callsign)
    }

    @Test
    fun readsEveryAdsbCategory() {
        // Heard near Paris-CDG: an airliner, category A3, typed "jet" by OGN.
        val jet = AprsParser.parse(
            "ICA44046D>OGADSB,qAS,AVX1217:/180510h4902.95N/00246.90E^043/277/A=006391 !W62! id2544046D +3328fpm FL062.14 A3:EJU4627 Sq5607",
        )!!
        assertEquals("EJU4627", jet.callsign)
        assertEquals(AircraftType.JET, jet.aircraftType)

        // The same line as a helicopter (A7): the category is more precise than OGN's type.
        val heli = AprsParser.parse(
            "ICA3964E4>OGADSB,qAS,AVX1217:/180510h4902.95N/00246.90E^043/120/A=001500 !W62! id213964E4 +0fpm A7:FGZHE",
        )!!
        assertEquals("FGZHE", heli.callsign)
        assertEquals(AircraftType.HELICOPTER, heli.aircraftType)

        // No type from OGN (id01: flags 0): the category gives one.
        val untyped = AprsParser.parse(
            "ICA3964E4>OGADSB,qAS,AVX1217:/180510h4902.95N/00246.90E^043/120/A=001500 !W62! id013964E4 +0fpm A1:FGZHE",
        )!!
        assertEquals(AircraftType.POWERED, untyped.aircraftType)

        // A light aircraft stays as OGN typed it.
        assertEquals(AircraftType.POWERED, AprsParser.parse(
            "ICA39BD74>OGADSB,qAS,AVX944:/150904h4631.07N\\00021.64W^102/360/A=025836 !W12! id2139BD74 +1280fpm FL253.93 A1:FHPLU Sq1773",
        )!!.aircraftType)
    }
}
