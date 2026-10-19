package fr.cvvm.picpiste.core.traffic

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.Runway
import fr.cvvm.picpiste.core.airfield.RunwayEnd
import fr.cvvm.picpiste.core.airfield.RunwayThird
import fr.cvvm.picpiste.core.aprs.AircraftBeacon
import fr.cvvm.picpiste.core.aprs.AircraftType
import fr.cvvm.picpiste.core.ddb.DeviceDatabase
import fr.cvvm.picpiste.core.geo.GeoPoint

/**
 * Keeps one track per aircraft and its status. Not thread-safe: feed it from one thread.
 * Nothing is written to disk; tracks are forgotten after [TrafficSettings.forgetAfterMs].
 */
class TrafficTracker(
    val airfield: Airfield,
    var settings: TrafficSettings = TrafficSettings(),
    var ddb: DeviceDatabase = DeviceDatabase.EMPTY,
) {
    private val engine = StatusEngine(airfield)
    private val tracks = LinkedHashMap<String, Track>()

    private class Track(val address: String) {
        val fixes = ArrayDeque<Fix>()
        var type = AircraftType.UNKNOWN
        /** Stealth bit of the latest beacon: the pilot asked not to be identified. */
        var stealth = false
        var lastTimeHms = ""
        var c = Classification(FlightStatus.AIRBORNE)
        var hasStatus = false
        var lastFinal: Pair<Runway, RunwayEnd>? = null
        var landedAtMs: Long? = null
        var landedRunway: Runway? = null
        var landedThird: RunwayThird? = null
        val latest get() = fixes.last()
    }

    val size get() = tracks.size

    fun onBeacon(b: AircraftBeacon, receivedAtMs: Long) {
        if (b.noTrack) return
        val dev = ddb.lookup(b.address)
        if (dev != null && !dev.isTracked) return

        val track = tracks.getOrPut(b.address) { Track(b.address) }
        if (b.timeHms == track.lastTimeHms) return // same beacon heard by another receiver
        track.lastTimeHms = b.timeHms
        if (b.aircraftType != AircraftType.UNKNOWN) track.type = b.aircraftType
        track.stealth = b.stealth

        val geo = GeoPoint(b.lat, b.lon)
        val fix = Fix(
            timeMs = receivedAtMs,
            pos = airfield.frame.toLocal(geo),
            geo = geo,
            altitudeM = b.altitudeM,
            aglM = b.altitudeM - airfield.elevationM,
            groundSpeedKmh = b.groundSpeedKmh,
            trackDeg = b.trackDeg,
            climbMs = b.climbMs,
        )
        val tugs = tracks.values
            .filter { it !== track && it.type == AircraftType.TOW_PLANE && it.fixes.isNotEmpty() }
            .map { it.latest }
        val previous = if (track.hasStatus) track.c.status else null
        val c = engine.classify(fix, track.fixes.toList(), previous, track.type, tugs)

        if (c.status == FlightStatus.FINAL && c.runway != null && c.runwayEnd != null) {
            track.lastFinal = c.runway to c.runwayEnd
        }
        if (c.status == FlightStatus.LANDED && previous != FlightStatus.LANDED) {
            recordLanding(track, fix)
        }
        if (c.status == FlightStatus.TAKEOFF || c.status == FlightStatus.TOW) {
            track.landedAtMs = null
            track.landedRunway = null
            track.landedThird = null
            track.lastFinal = null
        }

        track.c = c
        track.hasStatus = true
        track.fixes.addLast(fix)
        while (track.fixes.size > 1 && receivedAtMs - track.fixes.first().timeMs > settings.trailMs) {
            track.fixes.removeFirst()
        }
    }

    private fun recordLanding(track: Track, fix: Fix) {
        track.landedAtMs = fix.timeMs
        val (rw, lateral) = airfield.nearestRunway(fix.pos)
        val onIt = rw.outline.containsWithin(fix.pos, 30.0) || lateral < 40
        val end = track.lastFinal?.takeIf { it.first === rw }?.second
            ?: engine.endFromTrack(rw, track.fixes.lastOrNull()?.trackDeg)
            ?: RunwayEnd.B
        track.landedRunway = if (onIt) rw else null
        track.landedThird = if (onIt) rw.third(fix.pos, end) else null
    }

    /** Current picture, visible aircraft only. Also drops tracks silent for too long. */
    fun snapshot(nowMs: Long): List<AircraftState> {
        tracks.values.removeAll { nowMs - it.latest.timeMs > settings.forgetAfterMs }
        return tracks.values.mapNotNull { t ->
            val fix = t.latest
            val onField = fix.aglM < StatusEngine.GROUND_AGL_M && airfield.boundary.containsWithin(fix.pos, 300.0)
            val visible = fix.pos.length <= settings.radarRadiusM && (fix.aglM <= settings.ceilingAglM || onField)
            if (!visible) return@mapNotNull null
            // No-ident: from the beacon's stealth bit or the owner's DDB setting.
            val dev = ddb.lookup(t.address)?.takeUnless { t.stealth }
            val age = nowMs - fix.timeMs
            AircraftState(
                identity = Identity(
                    address = t.address,
                    type = t.type,
                    registration = dev?.registration?.takeIf { it.isNotBlank() && dev.isIdentified },
                    cn = dev?.cn?.takeIf { it.isNotBlank() && dev.isIdentified },
                    model = dev?.model?.takeIf { it.isNotBlank() && it != "Unknown" && dev.isIdentified },
                    anonymous = t.stealth || dev == null || !dev.isIdentified,
                ),
                fix = fix,
                trail = t.fixes.map { it.pos },
                status = t.c.status,
                runway = t.c.runway,
                runwayEnd = t.c.runwayEnd,
                etaS = t.c.etaS?.let { (it - age / 1000.0).coerceAtLeast(0.0) },
                signalLost = age > settings.lostAfterMs,
                ageMs = age,
                distanceM = fix.pos.length,
                landedAtMs = t.landedAtMs,
                landedRunway = t.landedRunway,
                landedThird = t.landedThird,
            )
        }
    }
}
