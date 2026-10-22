package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.aprs.AircraftType
import fr.cvvm.picpiste.core.traffic.Identity
import kotlin.test.Test
import kotlin.test.assertEquals

/** Radar label: competition number first, else the last 2 letters of the registration. */
class IdentityLabelTest {
    private fun id(registration: String?, cn: String?, anonymous: Boolean = false, callsign: String? = null) =
        Identity("DD0A01", AircraftType.GLIDER, registration, cn, null, anonymous, callsign)

    @Test
    fun competitionNumberWins() = assertEquals("AB", id("F-CXYZ", "AB").label)

    @Test
    fun lastTwoLettersOfRegistration() = assertEquals("LO", id("F-HMLO", null).label)

    @Test
    fun blankCompetitionNumberFallsBackToRegistration() = assertEquals("YZ", id("F-CXYZ", " ").label)

    @Test
    fun nothingKnown() = assertEquals("??", id(null, null).label)

    @Test
    fun anonymousHidesEverything() = assertEquals("??", id("F-CXYZ", "AB", anonymous = true).label)

    @Test
    fun adsbCallsignWhenUnknownToOgn() = assertEquals("FHPLU", id(null, null, callsign = "FHPLU").label)

    @Test
    fun registrationBeatsCallsign() = assertEquals("YZ", id("F-CXYZ", null, callsign = "AFR12").label)
}
