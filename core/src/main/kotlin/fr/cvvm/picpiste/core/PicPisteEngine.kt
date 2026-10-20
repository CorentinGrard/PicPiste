package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.AlertZone
import fr.cvvm.picpiste.core.aprs.AprsEvent
import fr.cvvm.picpiste.core.aprs.AprsParser
import fr.cvvm.picpiste.core.cart.AlertEngine
import fr.cvvm.picpiste.core.cart.CartAlert
import fr.cvvm.picpiste.core.cart.RunwayPresenceDetector
import fr.cvvm.picpiste.core.ddb.DeviceDatabase
import fr.cvvm.picpiste.core.geo.GeoPoint
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.traffic.AircraftState
import fr.cvvm.picpiste.core.traffic.ConnectionState
import fr.cvvm.picpiste.core.traffic.FlightStatus
import fr.cvvm.picpiste.core.traffic.TrafficSettings
import fr.cvvm.picpiste.core.traffic.TrafficTracker

/** [onRunway]: in a warning zone ([zones]: which ones). */
data class CartState(
    val pos: Vec2,
    val accuracyM: Double?,
    val onRunway: Boolean,
    val timeMs: Long,
    val zones: List<AlertZone> = emptyList(),
)

/** Everything a screen needs to draw one frame. */
data class Snapshot(
    /** The airfield the positions below are measured against. */
    val airfield: Airfield,
    val timeMs: Long,
    val aircraft: List<AircraftState>,
    /** Final first, then by estimated time to touchdown. */
    val arrivals: List<AircraftState>,
    /** Most recent landing first. */
    val landed: List<AircraftState>,
    val connection: ConnectionState,
    val cart: CartState?,
    val alert: CartAlert,
)

/**
 * The whole PicPiste brain, without Android: OGN lines and cart positions in, snapshots out.
 * Not thread-safe: call it from one thread or coroutine.
 */
class PicPisteEngine(
    val airfield: Airfield,
    settings: TrafficSettings = TrafficSettings(),
) {
    private val tracker = TrafficTracker(airfield, settings)
    private val presence = RunwayPresenceDetector(airfield)
    private var connection = ConnectionState(false, null, null)
    private var cart: CartState? = null

    var settings: TrafficSettings
        get() = tracker.settings
        set(value) {
            tracker.settings = value
        }

    var ddb: DeviceDatabase
        get() = tracker.ddb
        set(value) {
            tracker.ddb = value
        }

    fun onAprsEvent(e: AprsEvent) {
        when (e) {
            AprsEvent.Connecting -> connection = connection.copy(connected = false, retryInMs = null)
            is AprsEvent.Connected -> connection = connection.copy(connected = true, retryInMs = null)
            is AprsEvent.Disconnected -> connection = connection.copy(connected = false, retryInMs = e.retryInMs)
            is AprsEvent.Line -> onLine(e.text, e.receivedAtMs)
        }
    }

    fun onLine(line: String, receivedAtMs: Long) {
        connection = connection.copy(lastLineAtMs = receivedAtMs)
        AprsParser.parse(line)?.let { tracker.onBeacon(it, receivedAtMs) }
    }

    fun onCartPosition(geo: GeoPoint, accuracyM: Double?, timeMs: Long) {
        val pos = airfield.frame.toLocal(geo)
        val on = presence.update(pos, accuracyM, timeMs)
        cart = CartState(pos, accuracyM, on, timeMs, presence.zones)
    }

    fun onCartPositionLost() {
        cart = null
        presence.reset()
    }

    fun snapshot(nowMs: Long): Snapshot {
        // Alerts look at everything tracked; the display ceiling only hides aircraft from the screen.
        val tracked = tracker.snapshot(nowMs)
        val aircraft = tracked.filter { it.shown }
        val arrivals = aircraft
            // Silent gliders that were landing stay listed (greyed) while they may still land.
            .filter { (it.status.isArrival && !it.signalLost) || AlertEngine.isRecentlyLost(it) }
            .sortedWith(compareBy({ if (it.status == FlightStatus.FINAL) 0 else 1 }, { it.etaS ?: Double.MAX_VALUE }))
        val landed = aircraft
            .filter { it.landedAtMs != null && (it.status == FlightStatus.LANDED || it.status == FlightStatus.ON_GROUND) }
            .sortedByDescending { it.landedAtMs }
        // Cart fix older than 10 s: do not claim we know where it is.
        val cart = cart?.takeIf { nowMs - it.timeMs < 10_000 }
        return Snapshot(
            airfield = airfield,
            timeMs = nowMs,
            aircraft = aircraft,
            arrivals = arrivals,
            landed = landed,
            connection = connection,
            cart = cart,
            alert = AlertEngine.evaluate(cart?.zones.orEmpty(), tracked, airfield),
        )
    }
}
