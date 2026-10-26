package fr.cvvm.picpiste.core.traffic

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.Runway
import fr.cvvm.picpiste.core.airfield.RunwayEnd
import fr.cvvm.picpiste.core.airfield.RunwayThird
import fr.cvvm.picpiste.core.aprs.AircraftBeacon
import fr.cvvm.picpiste.core.aprs.AircraftType
import fr.cvvm.picpiste.core.aprs.DeviceStatus
import fr.cvvm.picpiste.core.ddb.DeviceDatabase
import fr.cvvm.picpiste.core.geo.GeoPoint
import fr.cvvm.picpiste.core.geo.Vec2

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

    /** Names devices gave themselves in status messages, with when they were heard. */
    private val statuses = HashMap<String, Pair<DeviceStatus, Long>>()

    /** Field altitude as the aircraft see it (see [ElevationCalibration]); heights are above it. */
    val calibration = ElevationCalibration(airfield)

    private class Track(val address: String) {
        val fixes = ArrayDeque<Fix>()
        var type = AircraftType.UNKNOWN
        /** Stealth bit of the latest beacon: the pilot asked not to be identified. */
        var stealth = false
        /** Latest ADS-B flight ID heard. */
        var callsign: String? = null
        var lastTimeHms = ""
        var c = Classification(FlightStatus.AIRBORNE)
        var hasStatus = false
        var lastFinal: Pair<Runway, RunwayEnd>? = null
        var landedAtMs: Long? = null
        var landedRunway: Runway? = null
        var landedThird: RunwayThird? = null
        /** Where the landed aircraft was when its runway was last checked. */
        var landedPos: Vec2? = null
        val latest get() = fixes.last()
    }

    val size get() = tracks.size

    /** A device named itself (OGN tracker, FANET…): used when the OGN database does not know it. */
    fun onStatus(s: DeviceStatus, receivedAtMs: Long) {
        val old = statuses[s.address]?.first
        // Trackers send their registration and their pilot's name in separate messages.
        statuses[s.address] = DeviceStatus(s.address, s.registration ?: old?.registration, s.name ?: old?.name) to receivedAtMs
    }

    fun onBeacon(b: AircraftBeacon, receivedAtMs: Long) {
        if (b.noTrack) return
        val dev = ddb.lookup(b.address)
        if (dev != null && !dev.isTracked) return

        val track = tracks.getOrPut(b.address) { Track(b.address) }
        if (b.timeHms == track.lastTimeHms) return // same beacon heard by another receiver
        val sentAtMs = sentAt(b.timeHms, receivedAtMs)
        // Relayed late, after a newer one: it would only move the aircraft backwards.
        if (track.fixes.isNotEmpty() && sentAtMs <= track.latest.timeMs) return
        track.lastTimeHms = b.timeHms
        if (b.aircraftType != AircraftType.UNKNOWN) track.type = b.aircraftType
        track.stealth = b.stealth
        if (b.callsign != null) track.callsign = b.callsign

        val geo = GeoPoint(b.lat, b.lon)
        val pos = airfield.frame.toLocal(geo)
        calibration.onFix(b.address, pos, b.altitudeM, b.groundSpeedKmh)
        val fix = Fix(
            timeMs = sentAtMs,
            pos = pos,
            geo = geo,
            altitudeM = b.altitudeM,
            aglM = b.altitudeM - calibration.fieldAltitudeM,
            groundSpeedKmh = b.groundSpeedKmh,
            trackDeg = b.trackDeg,
            climbMs = b.climbMs,
            turnDegS = b.turnDegS,
        )
        val tugs = tracks.values
            .filter { it !== track && it.type == AircraftType.TOW_PLANE && it.fixes.isNotEmpty() }
            .map { it.latest }
        val previous = if (track.hasStatus) track.c.status else null
        var c = engine.classify(fix, track.fixes.toList(), previous, track.type, tugs).let { c ->
            // Climbing away after takeoff: keep the runway it left from (the alert needs it).
            if (c.status == FlightStatus.TAKEOFF && c.runway == null && previous == FlightStatus.TAKEOFF) {
                c.copy(runway = track.c.runway, runwayEnd = track.c.runwayEnd)
            } else {
                c
            }
        }

        if (c.status == FlightStatus.FINAL && c.runway != null && c.runwayEnd != null) {
            track.lastFinal = c.runway to c.runwayEnd
        }
        if (c.status == FlightStatus.LANDED && previous != FlightStatus.LANDED) {
            recordLanding(track, fix)
        } else if (track.landedAtMs != null && movedSinceLanding(track, fix) &&
            (c.status == FlightStatus.LANDED || c.status == FlightStatus.ON_GROUND)
        ) {
            // Taxiing, or pulled by the cart at walking pace: once off the runway it has been
            // cleared and leaves the landed list. Still on a runway: say where it is now.
            track.landedPos = fix.pos
            val rw = airfield.runwayUnder(fix.pos, fix.trackDeg)
            if (rw == null) clearLanding(track) else setLanded(track, rw, fix)
        }
        if (c.status == FlightStatus.LANDED && track.landedRunway == null) {
            // Stopped beside the runway after landing: on the ground, still listed as landed
            // (off the runway) until it is moved.
            c = Classification(FlightStatus.ON_GROUND)
        }
        if (c.status == FlightStatus.TAKEOFF || c.status == FlightStatus.TOW) {
            clearLanding(track)
        }

        track.c = c
        track.hasStatus = true
        track.fixes.addLast(fix)
        while (track.fixes.size > 1 && sentAtMs - track.fixes.first().timeMs > settings.trailMs) {
            track.fixes.removeFirst()
        }
    }

    private fun recordLanding(track: Track, fix: Fix) {
        track.landedAtMs = fix.timeMs
        track.landedPos = fix.pos
        // Just stopped after the landing roll: the runway it rolled on, even near its edge.
        val (rw, lateral) = airfield.nearestRunway(fix.pos)
        val onIt = rw.outline.containsWithin(fix.pos, 30.0) || lateral < 40
        setLanded(track, if (onIt) rw else null, fix)
    }

    /** Faster than taxiing speed, or moved further than GPS noise since the last check. */
    private fun movedSinceLanding(track: Track, fix: Fix): Boolean =
        fix.groundSpeedKmh > MOVING_KMH ||
            track.landedPos.let { it == null || it.distanceTo(fix.pos) > MOVED_M }

    private fun clearLanding(track: Track) {
        track.landedAtMs = null
        track.landedRunway = null
        track.landedThird = null
        track.landedPos = null
        track.lastFinal = null
    }

    private fun setLanded(track: Track, rw: Runway?, fix: Fix) {
        track.landedRunway = rw
        track.landedThird = rw?.let {
            val end = track.lastFinal?.takeIf { f -> f.first === rw }?.second
                ?: engine.endFromTrack(rw, track.fixes.lastOrNull()?.trackDeg)
                ?: RunwayEnd.B
            rw.third(fix.pos, end)
        }
    }

    /**
     * When the aircraft sent a beacon: its `hhmmss` UTC time on the day it was received.
     * Beacons carry whole seconds, so half a second is added; never after it was received,
     * never more than [MAX_DELAY_MS] before (a beacon with a wrong clock).
     */
    private fun sentAt(timeHms: String, receivedAtMs: Long): Long {
        val secs = timeHms.toIntOrNull()?.let { (it / 10_000) * 3600 + (it / 100 % 100) * 60 + it % 100 } ?: return receivedAtMs
        var t = receivedAtMs - Math.floorMod(receivedAtMs, DAY_MS) + secs * 1000L + 500
        // Around midnight UTC the beacon's day may be the one before or after.
        if (t - receivedAtMs > DAY_MS / 2) t -= DAY_MS
        if (receivedAtMs - t > DAY_MS / 2) t += DAY_MS
        return t.coerceIn(receivedAtMs - MAX_DELAY_MS, receivedAtMs)
    }

    private companion object {
        const val DAY_MS = 24 * 3600 * 1000L
        /** OGN usually delivers in 2 s, 5 s at worst; more than this, the beacon's clock is off. */
        const val MAX_DELAY_MS = 30_000L

        /** Faster than this on the ground, a landed aircraft is taxiing (or being pulled). */
        const val MOVING_KMH = 5.0
        /** Moved this far since landing, even slowly (the cart pulls at walking pace): check again. */
        const val MOVED_M = 20.0
        /** Trackers repeat their name every few minutes; forget it after a day without. */
        const val STATUS_KEPT_MS = 24 * 3600 * 1000L
    }

    /**
     * Current picture: every aircraft within the radar radius, [AircraftState.shown] false above
     * the display ceiling or silent for [TrafficSettings.hideAfterMs] (still used for the alerts). Also drops tracks silent for too long.
     */
    fun snapshot(nowMs: Long): List<AircraftState> {
        tracks.values.removeAll { nowMs - it.latest.timeMs > settings.forgetAfterMs }
        statuses.values.removeAll { nowMs - it.second > STATUS_KEPT_MS }
        return tracks.values.mapNotNull { t ->
            val fix = t.latest
            val onField = fix.aglM < StatusEngine.GROUND_AGL_M && airfield.boundary.containsWithin(fix.pos, 300.0)
            if (fix.pos.length > settings.radarRadiusM) return@mapNotNull null
            val age = nowMs - fix.timeMs
            val shown = (fix.aglM <= settings.ceilingAglM || onField) && age <= settings.hideAfterMs
            // No-ident: from the beacon's stealth bit or the owner's DDB setting.
            val dev = ddb.lookup(t.address)?.takeUnless { t.stealth }
            val lost = age > settings.lostAfterMs
            // Moving aircraft are drawn where they should be now; lost ones where they were last heard.
            val moving = !lost && (t.c.status.isAirborne || t.c.status == FlightStatus.TAKEOFF)
            // Unknown to OGN: what the device says about itself (unless no-ident).
            val self = statuses[t.address]?.first?.takeIf { !t.stealth && dev == null }
            AircraftState(
                identity = Identity(
                    address = t.address,
                    type = t.type,
                    registration = dev?.registration?.takeIf { it.isNotBlank() && dev.isIdentified } ?: self?.registration,
                    cn = dev?.cn?.takeIf { it.isNotBlank() && dev.isIdentified },
                    model = dev?.model?.takeIf { it.isNotBlank() && it != "Unknown" && dev.isIdentified },
                    // Unknown to OGN: the ADS-B callsign still names it (unless no-ident).
                    callsign = t.callsign.takeIf { !t.stealth && dev == null },
                    name = self?.name,
                    anonymous = t.stealth || (dev == null && t.callsign == null && self == null) || (dev != null && !dev.isIdentified),
                ),
                fix = fix,
                trail = t.fixes.map { it.pos },
                status = t.c.status,
                runway = t.c.runway,
                runwayEnd = t.c.runwayEnd,
                etaS = t.c.etaS?.let { (it - age / 1000.0).coerceAtLeast(0.0) },
                signalLost = lost,
                ageMs = age,
                distanceM = fix.pos.length,
                landedAtMs = t.landedAtMs,
                landedRunway = t.landedRunway,
                landedThird = t.landedThird,
                shown = shown,
                predicted = moving,
            )
        }
    }
}
