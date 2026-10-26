package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.aprs.AircraftType
import fr.cvvm.picpiste.core.aprs.AprsFormatter
import fr.cvvm.picpiste.core.aprs.AprsParser
import fr.cvvm.picpiste.core.aprs.DeviceStatus
import fr.cvvm.picpiste.core.ddb.DdbDevice
import fr.cvvm.picpiste.core.ddb.DeviceDatabase
import fr.cvvm.picpiste.core.geo.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Personal trackers missing from the OGN database name themselves in status messages. */
class DeviceNameTest {
    private val lfnl = Airfield.bundled("LFNL")
    private val t0 = 1_760_000_000_000L

    @Test
    fun fanetName() = assertEquals(
        DeviceStatus("FC070C", name = "Höhematte"),
        AprsParser.parseStatus("""FNTFC070C>OGNFNT,qAS,LSXI1:>063852h Name="Höhematte" sF1 cr4 7.4dB -12.9kHz"""),
    )

    @Test
    fun ognTrackerRegistration() = assertEquals(
        DeviceStatus("04EA30", registration = "SQ9CAT"),
        AprsParser.parseStatus("OGN04EA30>OGNTRK,qAS,Liwocz:>063856h Reg=SQ9CAT Hard=HTIT"),
    )

    @Test
    fun ognTrackerPilot() = assertEquals(
        DeviceStatus("DD1234", name = "Paul"),
        AprsParser.parseStatus("OGNDD1234>OGNTRK,qAS,LFNL:>063856h Pilot=Paul"),
    )

    @Test
    fun statusWithoutNameIsIgnored() {
        assertNull(AprsParser.parseStatus("OGN0440D0>OGNTRK,qAS,AVX905:>064027h Soft=0.1.15"))
        assertNull(AprsParser.parseStatus("LEVC>OGNDVS,TCPIP*,qAC,GLIDERN1:>063852h 19:0 2.563s/0ms 21dB/-3kHz 271/0kt"))
    }

    @Test
    fun naviterBeaconIsAPosition() {
        val b = assertNotNull(
            AprsParser.parse("NAVFD0DD6>OGNAVI,qAS,NAVITER3:/063854h5018.02N/01717.56E'070/000/A=001368 !W11! id0440FD0DD6 +000fpm +0.0rot"),
        )
        assertEquals("FD0DD6", b.address)
        assertEquals(AircraftType.GLIDER, b.aircraftType)
        assertFalse(b.stealth)
        assertFalse(b.noTrack)
    }

    @Test
    fun naviterStealthAndNoTrack() {
        val b = assertNotNull(
            AprsParser.parse("NAVFD0DD6>OGNAVI,qAS,NAVITER3:/063854h5018.02N/01717.56E'070/000/A=001368 !W11! idC440FD0DD6 +000fpm"),
        )
        assertTrue(b.stealth)
        assertTrue(b.noTrack)
    }

    private fun engine(ddb: DeviceDatabase = DeviceDatabase.EMPTY, stealth: Boolean = false) = PicPisteEngine(lfnl).apply {
        this.ddb = ddb
        onLine("""FNTDD0A01>OGNFNT,qAS,LFNL:>120000h Name="Corentin Grard" sF1""", t0)
        onLine("OGNDD0A01>OGNTRK,qAS,LFNL:>120001h Reg=F-CXYZ", t0)
        onLine(
            AprsFormatter.aircraft(
                "DD0A01", AircraftType.GLIDER, lfnl.frame.toGeo(Vec2(0.0, 0.0)), lfnl.elevationM,
                null, 0.0, 0.0, t0, stealth = stealth,
            ),
            t0,
        )
    }

    @Test
    fun unknownDeviceShowsItsOwnName() {
        val id = engine().snapshot(t0).aircraft.single().identity
        assertFalse(id.anonymous)
        assertEquals("F-CXYZ", id.registration)
        assertEquals("Corentin Grard", id.name)
        assertEquals("YZ", id.label)
    }

    @Test
    fun nameAloneGivesTheLabel() {
        val e = PicPisteEngine(lfnl)
        e.onLine("""FNTDD0A01>OGNFNT,qAS,LFNL:>120000h Name="Corentin Grard" sF1""", t0)
        e.onLine(AprsFormatter.aircraft("DD0A01", AircraftType.GLIDER, lfnl.frame.toGeo(Vec2(0.0, 0.0)), lfnl.elevationM, null, 0.0, 0.0, t0), t0)
        assertEquals("Corentin", e.snapshot(t0).aircraft.single().identity.label)
    }

    @Test
    fun stealthHidesTheOwnName() {
        val id = engine(stealth = true).snapshot(t0).aircraft.single().identity
        assertTrue(id.anonymous)
        assertNull(id.name)
        assertEquals("??", id.label)
    }

    @Test
    fun ognDatabaseWins() {
        val ddb = DeviceDatabase(listOf(DdbDevice("DD0A01", registration = "F-CABC", identified = "N")))
        val id = engine(ddb).snapshot(t0).aircraft.single().identity
        // The owner asked OGN not to identify it: its own messages do not override that.
        assertTrue(id.anonymous)
        assertNull(id.name)
        assertNull(id.registration)
    }
}
