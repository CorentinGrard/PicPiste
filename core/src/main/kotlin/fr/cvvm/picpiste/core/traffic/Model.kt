package fr.cvvm.picpiste.core.traffic

import fr.cvvm.picpiste.core.airfield.Runway
import fr.cvvm.picpiste.core.airfield.RunwayEnd
import fr.cvvm.picpiste.core.airfield.RunwayThird
import fr.cvvm.picpiste.core.aprs.AircraftType
import fr.cvvm.picpiste.core.geo.GeoPoint
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.i18n.Texts

data class TrafficSettings(
    /** Only aircraft below this height above the field are shown (plus those on the ground). */
    val ceilingAglM: Double = 400.0,
    val radarRadiusM: Double = 5_000.0,
    /** No beacon for this long: the aircraft is shown greyed, "signal lost". */
    val lostAfterMs: Long = 60_000,
    /** No beacon for this long: the aircraft is dropped. */
    val forgetAfterMs: Long = 15 * 60_000,
    val trailMs: Long = 120_000,
)

enum class FlightStatus {
    TAKEOFF, TOW, APPROACH, CIRCUIT, FINAL, LANDED, ON_GROUND, AIRBORNE;

    /** "Final", "Finale"… in the phone language. */
    val label: String get() = Texts.current.status(this)

    val isAirborne get() = this in AIRBORNE_STATUSES
    val isArrival get() = this == APPROACH || this == CIRCUIT || this == FINAL
}

private val AIRBORNE_STATUSES = setOf(
    FlightStatus.TOW, FlightStatus.APPROACH, FlightStatus.CIRCUIT, FlightStatus.FINAL, FlightStatus.AIRBORNE,
)

/** One position of one aircraft, in the airfield's local frame. */
data class Fix(
    val timeMs: Long,
    val pos: Vec2,
    val geo: GeoPoint,
    val altitudeM: Double,
    val aglM: Double,
    val groundSpeedKmh: Double,
    val trackDeg: Double?,
    val climbMs: Double?,
)

data class Identity(
    val address: String,
    val type: AircraftType,
    val registration: String?,
    val cn: String?,
    val model: String?,
    /** Owner asked not to be identified, or the device is unknown to OGN. */
    val anonymous: Boolean,
) {
    /** Short label for the radar: competition number, else end of the registration. */
    val label: String
        get() = when {
            anonymous -> "??"
            !cn.isNullOrBlank() -> cn
            !registration.isNullOrBlank() -> registration.takeLast(3)
            else -> "??"
        }
}

data class AircraftState(
    val identity: Identity,
    val fix: Fix,
    val trail: List<Vec2>,
    val status: FlightStatus,
    val runway: Runway?,
    val runwayEnd: RunwayEnd?,
    /** Estimated seconds until touchdown, for arrivals. */
    val etaS: Double?,
    val signalLost: Boolean,
    val ageMs: Long,
    val distanceM: Double,
    val landedAtMs: Long?,
    val landedRunway: Runway?,
    val landedThird: RunwayThird?,
) {
    val address get() = identity.address

    /** Landing or takeoff direction, without the L/R letter: "22", "14". */
    val direction: String? get() = runwayEnd?.let { end -> runway?.direction(end) }

    /** "Final 22", "Circuit", "Returning · 2.4 km · 290 m"… */
    val statusText: String
        get() = if (signalLost) Texts.current.signalLost(liveStatusText) else liveStatusText

    private val liveStatusText: String
        get() = when (status) {
            // Direction only ("Finale 14", not "14L"): parallel runways are too close to tell apart from the air.
            FlightStatus.FINAL, FlightStatus.TAKEOFF -> direction?.let { "${status.label} $it" } ?: status.label
            FlightStatus.APPROACH -> Texts.current.approach(distanceM, fix.aglM.toInt())
            FlightStatus.LANDED -> landedText ?: status.label
            else -> status.label
        }

    val landedText: String?
        get() = landedRunway?.let { rw -> "${rw.name}, ${landedThird?.label ?: Texts.current.onTheRunway}" }
}

/** [retryInMs]: when offline, how long before the next attempt (null while connecting). */
data class ConnectionState(val connected: Boolean, val retryInMs: Long?, val lastLineAtMs: Long?) {
    /**
     * Data is flowing. The OGN server sends a line at least every 20 s even when nothing flies,
     * so a longer silence means the link is broken: an empty circuit then proves nothing.
     */
    fun isLive(nowMs: Long): Boolean = connected && lastLineAtMs != null && nowMs - lastLineAtMs < STALE_AFTER_MS

    /** How long since the last line, when connected but silent. */
    fun silentForMs(nowMs: Long): Long? = lastLineAtMs?.let { nowMs - it }?.takeIf { connected && it >= STALE_AFTER_MS }

    companion object {
        const val STALE_AFTER_MS = 60_000L
    }

    val text: String
        get() = when {
            connected -> Texts.current.connected
            retryInMs != null -> Texts.current.offline(retryInMs / 1000)
            else -> Texts.current.connecting
        }
}
