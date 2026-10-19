package fr.cvvm.picpiste.core.i18n

import fr.cvvm.picpiste.core.airfield.NewShape
import fr.cvvm.picpiste.core.airfield.RunwayThird
import fr.cvvm.picpiste.core.aprs.AircraftType
import fr.cvvm.picpiste.core.traffic.FlightStatus
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Every word the core shows or says: statuses, alerts, spoken warnings, airfield import and
 * editor messages. The app screens have their own Android string resources.
 *
 * English is the default; [French] is used when the phone is in French. The app sets [current]
 * from the phone language (see `PicPisteApp`).
 */
sealed class Texts(val locale: Locale) {
    // Traffic
    abstract fun status(s: FlightStatus): String
    abstract fun aircraftType(t: AircraftType): String
    abstract fun third(t: RunwayThird): String
    abstract val onTheRunway: String

    /** "Returning · 2.4 km · 290 m" */
    fun approach(distanceM: Double, aglM: Int): String =
        "%s · %.1f km · %d m".format(locale, status(FlightStatus.APPROACH), distanceM / 1000, aglM)

    // Connection
    abstract val connecting: String
    abstract val connected: String
    abstract fun offline(retryInS: Long): String

    // Cart alert
    abstract fun onFinal(aircraft: String, direction: String?): String
    abstract fun takingOff(direction: String?): String
    abstract fun inCircuit(aircraft: String): String
    abstract fun clearRunway(who: String): String

    /** "Glider on final 30 · signal lost" */
    abstract fun signalLost(what: String): String
    abstract val caution: String
    abstract fun spokenSeconds(s: Int): String
    abstract fun spokenMinutes(min: Int): String

    // Airfield file
    abstract val notAnAirfieldFile: String
    abstract val newerFileVersion: String

    // OpenStreetMap import
    abstract fun invalidCode(code: String): String
    abstract val osmNotResponding: String
    abstract fun noAirfield(code: String): String
    abstract fun noPosition(code: String): String
    abstract fun noRunways(code: String): String
    abstract val osmUnreadable: String
    abstract val refsGuessed: String
    abstract val widthUnknown: String

    /** Start of both elevation warnings, so the editor can drop them once the elevation is set. */
    abstract val elevationWarningPrefix: String
    abstract fun elevationEstimated(m: Int): String
    abstract val elevationUnknown: String

    /** Default runway names for a new or imported runway. */
    abstract val grassName: String
    abstract val pavedName: String
    abstract val newRunwayName: String

    // Editor
    abstract fun newShape(s: NewShape): String
    abstract val boundary: String
    abstract fun runway(name: String): String
    abstract fun threshold(ref: String, runwayName: String): String
    abstract fun apron(n: Int): String
    abstract fun hangar(n: Int): String
    abstract fun taxiway(n: Int): String
    abstract fun windsock(n: Int): String
    abstract val starter: String

    // Airfield problems (the editor will not save until they are fixed)
    abstract val emptyCode: String
    abstract val badReference: String
    abstract val badElevation: String
    abstract val noRunway: String
    abstract fun runwayNeedsThresholds(name: String): String
    abstract fun runwayOutlineTooSmall(name: String): String
    abstract fun thresholdsTooClose(name: String, m: Int): String
    abstract fun runwayTooFar(name: String): String
    abstract val duplicateRunwayNames: String
    abstract val boundaryTooSmall: String
    abstract val polygonTooSmall: String
    abstract val taxiwayTooShort: String
    abstract val misplacedPoint: String

    /** "20 seconds" under a minute (rounded to 5 s), "2 minutes" above. */
    fun spokenEta(s: Double): String = when {
        s < 60 -> spokenSeconds(((s / 5).roundToInt() * 5).coerceAtLeast(5))
        else -> spokenMinutes((s / 60).roundToInt())
    }

    object English : Texts(Locale.ENGLISH) {
        override fun status(s: FlightStatus) = when (s) {
            FlightStatus.TAKEOFF -> "Takeoff"
            FlightStatus.TOW -> "Tow"
            FlightStatus.APPROACH -> "Returning"
            FlightStatus.CIRCUIT -> "Circuit"
            FlightStatus.FINAL -> "Final"
            FlightStatus.LANDED -> "Landed"
            FlightStatus.ON_GROUND -> "On the ground"
            FlightStatus.AIRBORNE -> "Flying"
        }

        override fun aircraftType(t: AircraftType) = when (t) {
            AircraftType.GLIDER -> "Glider"
            AircraftType.TOW_PLANE -> "Tow plane"
            AircraftType.HELICOPTER -> "Helicopter"
            AircraftType.SKYDIVER -> "Skydiver"
            AircraftType.DROP_PLANE -> "Drop plane"
            AircraftType.HANG_GLIDER -> "Hang glider"
            AircraftType.PARAGLIDER -> "Paraglider"
            AircraftType.POWERED -> "Plane"
            AircraftType.JET -> "Jet"
            AircraftType.BALLOON -> "Balloon"
            AircraftType.AIRSHIP -> "Airship"
            AircraftType.UAV -> "Drone"
            AircraftType.STATIC -> "Static"
            AircraftType.UNKNOWN, AircraftType.UFO, AircraftType.RESERVED -> "Aircraft"
        }

        override fun third(t: RunwayThird) = when (t) {
            RunwayThird.START -> "start of runway"
            RunwayThird.MIDDLE -> "middle"
            RunwayThird.END -> "end of runway"
        }

        override val onTheRunway = "on the runway"

        override val connecting = "Connecting…"
        override val connected = "OGN connected"
        override fun offline(retryInS: Long) = "Offline — retrying in $retryInS s"

        override fun onFinal(aircraft: String, direction: String?) = "$aircraft on final" + (direction?.let { " $it" } ?: "")
        override fun takingOff(direction: String?) = "Taking off" + (direction?.let { " $it" } ?: "")
        override fun inCircuit(aircraft: String) = "$aircraft in the circuit"
        override fun clearRunway(who: String) = "$who — clear the runway"
        override fun signalLost(what: String) = "$what · signal lost"
        override val caution = "Caution"
        override fun spokenSeconds(s: Int) = "$s seconds"
        override fun spokenMinutes(min: Int) = if (min == 1) "1 minute" else "$min minutes"

        override val notAnAirfieldFile = "This file is not a PicPiste airfield."
        override val newerFileVersion = "This airfield comes from a newer version of PicPiste: update the app."

        override fun invalidCode(code: String) = "Invalid code “$code”: 3 to 7 letters or digits, e.g. LFPT."
        override val osmNotResponding = "OpenStreetMap is not responding (servers busy or no network). Try again in a minute."
        override fun noAirfield(code: String) = "No airfield “$code” in OpenStreetMap."
        override fun noPosition(code: String) = "Airfield “$code” has no position in OpenStreetMap."
        override fun noRunways(code: String) = "Airfield “$code” has no runway drawn in OpenStreetMap."
        override val osmUnreadable = "Unreadable OpenStreetMap answer."
        override val refsGuessed = "Runway numbers guessed from their direction: check them."
        override val widthUnknown = "Runway width unknown: check the outline."
        override val elevationWarningPrefix = "Field elevation"
        override fun elevationEstimated(m: Int) = "Field elevation estimated ($m m): check it."
        override val elevationUnknown = "Field elevation unknown: enter it."
        override val grassName = "grass"
        override val pavedName = "paved"
        override val newRunwayName = "new"

        override fun newShape(s: NewShape) = when (s) {
            NewShape.RUNWAY -> "Runway"
            NewShape.HANGAR -> "Hangar"
            NewShape.APRON -> "Apron"
            NewShape.TAXIWAY -> "Taxiway"
            NewShape.WINDSOCK -> "Windsock"
            NewShape.STARTER -> "Launch point"
            NewShape.BOUNDARY -> "Airfield boundary"
        }

        override val boundary = "Airfield boundary"
        override fun runway(name: String) = "Runway $name"
        override fun threshold(ref: String, runwayName: String) = "Threshold $ref $runwayName"
        override fun apron(n: Int) = "Apron $n"
        override fun hangar(n: Int) = "Hangar $n"
        override fun taxiway(n: Int) = "Taxiway $n"
        override fun windsock(n: Int) = "Windsock $n"
        override val starter = "Launch point"

        override val emptyCode = "The airfield code is empty."
        override val badReference = "The reference point is invalid."
        override val badElevation = "The field elevation is invalid."
        override val noRunway = "At least one runway is needed."
        override fun runwayNeedsThresholds(name: String) = "Runway $name: it needs two thresholds with their numbers."
        override fun runwayOutlineTooSmall(name: String) = "Runway $name: the outline needs at least 3 points."
        override fun thresholdsTooClose(name: String, m: Int) = "Runway $name: the two thresholds are too close ($m m)."
        override fun runwayTooFar(name: String) = "Runway $name: more than 20 km from the airfield."
        override val duplicateRunwayNames = "Two runways have the same name."
        override val boundaryTooSmall = "The airfield boundary needs at least 3 points."
        override val polygonTooSmall = "An apron or a hangar has fewer than 3 points."
        override val taxiwayTooShort = "A taxiway has fewer than 2 points."
        override val misplacedPoint = "A windsock or the launch point is misplaced."
    }

    object French : Texts(Locale.FRANCE) {
        override fun status(s: FlightStatus) = when (s) {
            FlightStatus.TAKEOFF -> "Décollage"
            FlightStatus.TOW -> "Remorquage"
            FlightStatus.APPROACH -> "Retour"
            FlightStatus.CIRCUIT -> "Circuit"
            FlightStatus.FINAL -> "Finale"
            FlightStatus.LANDED -> "Posé"
            FlightStatus.ON_GROUND -> "Au sol"
            FlightStatus.AIRBORNE -> "En vol"
        }

        override fun aircraftType(t: AircraftType) = when (t) {
            AircraftType.GLIDER -> "Planeur"
            AircraftType.TOW_PLANE -> "Remorqueur"
            AircraftType.HELICOPTER -> "Hélico"
            AircraftType.SKYDIVER -> "Parachutiste"
            AircraftType.DROP_PLANE -> "Avion largueur"
            AircraftType.HANG_GLIDER -> "Delta"
            AircraftType.PARAGLIDER -> "Parapente"
            AircraftType.POWERED -> "Avion"
            AircraftType.JET -> "Jet"
            AircraftType.BALLOON -> "Ballon"
            AircraftType.AIRSHIP -> "Dirigeable"
            AircraftType.UAV -> "Drone"
            AircraftType.STATIC -> "Fixe"
            AircraftType.UNKNOWN, AircraftType.UFO, AircraftType.RESERVED -> "Aéronef"
        }

        override fun third(t: RunwayThird) = when (t) {
            RunwayThird.START -> "début de piste"
            RunwayThird.MIDDLE -> "milieu"
            RunwayThird.END -> "fin de piste"
        }

        override val onTheRunway = "sur la piste"

        override val connecting = "Connexion…"
        override val connected = "OGN connecté"
        override fun offline(retryInS: Long) = "Hors ligne — nouvel essai dans $retryInS s"

        override fun onFinal(aircraft: String, direction: String?) = "$aircraft en finale" + (direction?.let { " $it" } ?: "")
        override fun takingOff(direction: String?) = "Décollage en cours" + (direction?.let { " $it" } ?: "")
        override fun inCircuit(aircraft: String) = "$aircraft dans le circuit"
        override fun clearRunway(who: String) = "$who — dégagez la piste"
        override fun signalLost(what: String) = "$what · signal perdu"
        override val caution = "Attention"
        override fun spokenSeconds(s: Int) = "$s secondes"
        override fun spokenMinutes(min: Int) = if (min == 1) "1 minute" else "$min minutes"

        override val notAnAirfieldFile = "Ce fichier n'est pas un terrain PicPiste."
        override val newerFileVersion = "Ce terrain vient d'une version plus récente de PicPiste : mettez l'app à jour."

        override fun invalidCode(code: String) = "Code « $code » invalide : 3 à 7 lettres ou chiffres, par ex. LFPT."
        override val osmNotResponding = "OpenStreetMap ne répond pas (serveurs occupés ou pas de réseau). Réessayez dans une minute."
        override fun noAirfield(code: String) = "Aucun terrain « $code » dans OpenStreetMap."
        override fun noPosition(code: String) = "Terrain « $code » sans position dans OpenStreetMap."
        override fun noRunways(code: String) = "Le terrain « $code » n'a aucune piste dessinée dans OpenStreetMap."
        override val osmUnreadable = "Réponse OpenStreetMap illisible."
        override val refsGuessed = "Numéros de piste déduits de l'orientation : à vérifier."
        override val widthUnknown = "Largeur de piste inconnue : contour à vérifier."
        override val elevationWarningPrefix = "Altitude terrain"
        override fun elevationEstimated(m: Int) = "Altitude terrain estimée ($m m) : à vérifier."
        override val elevationUnknown = "Altitude terrain inconnue : à saisir."
        override val grassName = "herbe"
        override val pavedName = "dur"
        override val newRunwayName = "nouvelle"

        override fun newShape(s: NewShape) = when (s) {
            NewShape.RUNWAY -> "Piste"
            NewShape.HANGAR -> "Hangar"
            NewShape.APRON -> "Parking"
            NewShape.TAXIWAY -> "Taxiway"
            NewShape.WINDSOCK -> "Manche à air"
            NewShape.STARTER -> "Starter"
            NewShape.BOUNDARY -> "Contour du terrain"
        }

        override val boundary = "Contour du terrain"
        override fun runway(name: String) = "Piste $name"
        override fun threshold(ref: String, runwayName: String) = "Seuil $ref $runwayName"
        override fun apron(n: Int) = "Parking $n"
        override fun hangar(n: Int) = "Hangar $n"
        override fun taxiway(n: Int) = "Taxiway $n"
        override fun windsock(n: Int) = "Manche à air $n"
        override val starter = "Starter"

        override val emptyCode = "Le code du terrain est vide."
        override val badReference = "Le point de référence est invalide."
        override val badElevation = "L'altitude du terrain est invalide."
        override val noRunway = "Il faut au moins une piste."
        override fun runwayNeedsThresholds(name: String) = "Piste $name : il faut deux seuils avec leur numéro."
        override fun runwayOutlineTooSmall(name: String) = "Piste $name : le contour doit avoir au moins 3 points."
        override fun thresholdsTooClose(name: String, m: Int) = "Piste $name : les deux seuils sont trop proches ($m m)."
        override fun runwayTooFar(name: String) = "Piste $name : à plus de 20 km du terrain."
        override val duplicateRunwayNames = "Deux pistes ont le même nom."
        override val boundaryTooSmall = "Le contour du terrain doit avoir au moins 3 points."
        override val polygonTooSmall = "Un parking ou un hangar a moins de 3 points."
        override val taxiwayTooShort = "Un taxiway a moins de 2 points."
        override val misplacedPoint = "Une manche à air ou le starter est mal placé."
    }

    companion object {
        /** Language used everywhere in the core. Set by the app from the phone language. */
        @Volatile
        var current: Texts = English

        /** French for "fr", English for anything else. */
        fun forLanguage(language: String): Texts = if (language.lowercase().startsWith("fr")) French else English
    }
}
