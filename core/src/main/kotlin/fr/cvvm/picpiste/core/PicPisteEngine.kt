package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.Runway
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

data class CartState(val pos: Vec2, val accuracyM: Double?, val runway: Runway?, val timeMs: Long)

/** Everything a screen needs to draw one frame. */
data class Snapshot(
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
    private var connection = ConnectionState(false, "Connexion…", null)
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
            AprsEvent.Connecting -> connection = connection.copy(connected = false, text = "Connexion…")
            is AprsEvent.Connected -> connection = connection.copy(connected = true, text = "OGN connecté")
            is AprsEvent.Disconnected -> connection = connection.copy(
                connected = false,
                text = "Hors ligne — nouvel essai dans ${e.retryInMs / 1000} s",
            )
            is AprsEvent.Line -> onLine(e.text, e.receivedAtMs)
        }
    }

    fun onLine(line: String, receivedAtMs: Long) {
        connection = connection.copy(lastLineAtMs = receivedAtMs)
        AprsParser.parse(line)?.let { tracker.onBeacon(it, receivedAtMs) }
    }

    fun onCartPosition(geo: GeoPoint, accuracyM: Double?, timeMs: Long) {
        val pos = airfield.frame.toLocal(geo)
        val runway = presence.update(pos, accuracyM, timeMs)
        cart = CartState(pos, accuracyM, runway, timeMs)
    }

    fun onCartPositionLost() {
        cart = null
        presence.reset()
    }

    fun snapshot(nowMs: Long): Snapshot {
        val aircraft = tracker.snapshot(nowMs)
        val arrivals = aircraft
            .filter { it.status.isArrival && !it.signalLost }
            .sortedWith(compareBy({ if (it.status == FlightStatus.FINAL) 0 else 1 }, { it.etaS ?: Double.MAX_VALUE }))
        val landed = aircraft
            .filter { it.landedAtMs != null && (it.status == FlightStatus.LANDED || it.status == FlightStatus.ON_GROUND) }
            .sortedByDescending { it.landedAtMs }
        // Cart fix older than 10 s: do not claim we know where it is.
        val cart = cart?.takeIf { nowMs - it.timeMs < 10_000 }
        return Snapshot(
            timeMs = nowMs,
            aircraft = aircraft,
            arrivals = arrivals,
            landed = landed,
            connection = connection,
            cart = cart,
            alert = AlertEngine.evaluate(cart?.runway, aircraft),
        )
    }
}
