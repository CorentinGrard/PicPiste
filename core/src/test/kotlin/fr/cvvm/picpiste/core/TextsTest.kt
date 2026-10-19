package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.NewShape
import fr.cvvm.picpiste.core.airfield.OsmImport
import fr.cvvm.picpiste.core.airfield.RunwayThird
import fr.cvvm.picpiste.core.aprs.AircraftType
import fr.cvvm.picpiste.core.cart.AlertLevel
import fr.cvvm.picpiste.core.demo.DemoTraffic
import fr.cvvm.picpiste.core.i18n.Texts
import fr.cvvm.picpiste.core.traffic.FlightStatus
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** English by default, French on a French phone, nothing else. */
class TextsTest {
    @AfterTest
    fun backToDefault() {
        Texts.current = Texts.English
    }

    @Test
    fun languageChoice() {
        assertEquals(Texts.French, Texts.forLanguage("fr"))
        assertEquals(Texts.French, Texts.forLanguage("FR"))
        assertEquals(Texts.English, Texts.forLanguage("en"))
        assertEquals(Texts.English, Texts.forLanguage("de"), "unsupported languages fall back to English")
        assertEquals(Texts.English, Texts.forLanguage(""))
    }

    @Test
    fun everyLabelExistsInBothLanguages() {
        for (t in listOf(Texts.English, Texts.French)) {
            FlightStatus.entries.forEach { assertTrue(t.status(it).isNotBlank()) }
            AircraftType.entries.forEach { assertTrue(t.aircraftType(it).isNotBlank()) }
            RunwayThird.entries.forEach { assertTrue(t.third(it).isNotBlank()) }
            NewShape.entries.forEach { assertTrue(t.newShape(it).isNotBlank()) }
            // The editor drops elevation warnings by this prefix once the elevation is entered.
            assertTrue(t.elevationUnknown.startsWith(t.elevationWarningPrefix))
            assertTrue(t.elevationEstimated(120).startsWith(t.elevationWarningPrefix))
        }
    }

    @Test
    fun alertAndStatusInFrench() {
        Texts.current = Texts.French
        val lfnl = Airfield.bundled("LFNL")
        val demo = DemoTraffic(lfnl)
        val engine = PicPisteEngine(lfnl).apply { ddb = demo.devices }
        val onRunway = (demo.main.thresholdA + demo.main.thresholdB) * 0.5
        val t0 = 1_760_000_000_000L
        val titles = mutableSetOf<String>()
        val speech = mutableSetOf<String>()
        val statuses = mutableSetOf<String>()
        for (s in 0..300) {
            val now = t0 + s * 1000L
            demo.beaconsAt(s * 1000L, now).forEach { engine.onLine(it, now) }
            engine.onCartPosition(lfnl.frame.toGeo(onRunway), 4.0, now)
            val snap = engine.snapshot(now)
            if (snap.alert.level == AlertLevel.DANGER) {
                titles += snap.alert.title
                speech += snap.alert.speech
                assertTrue(snap.alert.detail.endsWith("dégagez la piste"), snap.alert.detail)
            }
            snap.aircraft.forEach { statuses += it.statusText }
        }
        assertTrue("Planeur en finale 30" in titles, titles.toString())
        assertTrue(speech.all { it.startsWith("Attention. ") }, speech.toString())
        assertTrue("Finale 30" in statuses, statuses.toString())
        assertTrue(statuses.any { it.startsWith("Retour · ") && it.contains(",") }, "French decimal comma: $statuses")
    }

    @Test
    fun spokenEtaInBothLanguages() {
        assertEquals("20 seconds", Texts.English.spokenEta(21.0))
        assertEquals("1 minute", Texts.English.spokenEta(70.0))
        assertEquals("3 minutes", Texts.English.spokenEta(170.0))
        assertEquals("20 secondes", Texts.French.spokenEta(21.0))
        assertEquals("3 minutes", Texts.French.spokenEta(170.0))
    }

    @Test
    fun importInFrench() {
        Texts.current = Texts.French
        val json = javaClass.getResource("/osm/LFNL.json")!!.readText()
        val r = OsmImport.build("LFNL", json)
        assertTrue(r.elevationMissing)
        assertTrue(r.warnings.any { it == "Altitude terrain inconnue : à saisir." }, r.warnings.toString())
        assertEquals(1, r.config.runways.count { it.name == "herbe" })
    }
}
