package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.aprs.AircraftType
import fr.cvvm.picpiste.core.aprs.AprsFormatter
import fr.cvvm.picpiste.core.ddb.DdbDevice
import fr.cvvm.picpiste.core.ddb.DeviceDatabase
import fr.cvvm.picpiste.core.geo.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** OGN opt-outs: no-track hides the aircraft, no-ident shows it without a name. */
class PrivacyTest {
    private val lfnl = Airfield.bundled("LFNL")
    private val t0 = 1_760_000_000_000L
    private val ddb = DeviceDatabase(listOf(DdbDevice("DD0A01", registration = "F-CXYZ", cn = "AB", model = "ASK 21")))

    private fun snapshotWith(stealth: Boolean = false, noTrack: Boolean = false): Snapshot {
        val engine = PicPisteEngine(lfnl).apply { ddb = this@PrivacyTest.ddb }
        val line = AprsFormatter.aircraft(
            "DD0A01", AircraftType.GLIDER, lfnl.frame.toGeo(Vec2(0.0, 0.0)), lfnl.elevationM,
            null, 0.0, 0.0, t0, noTrack = noTrack, stealth = stealth,
        )
        engine.onLine(line, t0)
        return engine.snapshot(t0)
    }

    @Test
    fun identifiedAircraftShowsItsName() {
        val a = snapshotWith().aircraft.single()
        assertEquals("F-CXYZ", a.identity.registration)
        assertFalse(a.identity.anonymous)
    }

    @Test
    fun stealthBeaconHidesNameEvenIfKnownInDdb() {
        val a = snapshotWith(stealth = true).aircraft.single()
        assertTrue(a.identity.anonymous)
        assertNull(a.identity.registration)
        assertNull(a.identity.cn)
        assertNull(a.identity.model)
        assertEquals("??", a.identity.label)
    }

    @Test
    fun noTrackBeaconIsNotShown() {
        assertTrue(snapshotWith(noTrack = true).aircraft.isEmpty())
    }
}
