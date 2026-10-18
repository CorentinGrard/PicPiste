package fr.cvvm.picpiste.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import fr.cvvm.picpiste.core.PicPisteEngine
import fr.cvvm.picpiste.core.Snapshot
import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.aprs.AprsClient
import fr.cvvm.picpiste.core.aprs.AprsEvent
import fr.cvvm.picpiste.core.ddb.DdbRepository
import fr.cvvm.picpiste.core.ddb.DeviceDatabase
import fr.cvvm.picpiste.core.demo.DemoTraffic
import fr.cvvm.picpiste.core.geo.GeoPoint
import fr.cvvm.picpiste.core.traffic.TrafficSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs the PicPiste engine for the whole app. The phone screen and the Android Auto
 * session each [acquire] it while visible; it stops when nobody uses it.
 * All engine calls happen on one thread ([engineDispatcher]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrafficRepository(private val context: Context) {
    val prefs = Prefs(context)
    val airfield: Airfield = Airfield.bundled("LFNL")

    private val engineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + engineDispatcher)
    private var engine = PicPisteEngine(airfield)
    private var liveDdb: DeviceDatabase = DeviceDatabase.EMPTY
    private val speaker = AlertSpeaker(context)

    private val _snapshot = MutableStateFlow(engine.snapshot(System.currentTimeMillis()))
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private var users = 0
    private var job: Job? = null
    private var listening = false

    private val locationListener = LocationListener { loc: Location ->
        if (prefs.demo.value) return@LocationListener
        val geo = GeoPoint(loc.latitude, loc.longitude)
        val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
        scope.launch { engine.onCartPosition(geo, acc, System.currentTimeMillis()) }
    }

    /** Call from the main thread. */
    fun acquire() {
        users++
        if (users == 1) start()
    }

    /** Call from the main thread. */
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) stop()
    }

    /** After the location permission was granted. */
    fun onPermissionsChanged() {
        if (users > 0) {
            startLocation()
            TrackingService.start(context)
        }
    }

    private fun start() {
        startLocation()
        TrackingService.start(context)
        job = scope.launch {
            launch { loadDdb() }
            launch {
                prefs.demo.collectLatest { demo ->
                    engine = PicPisteEngine(airfield, settings()).apply { ddb = liveDdb }
                    if (demo) runDemo() else runLive()
                }
            }
            launch {
                while (true) {
                    val now = System.currentTimeMillis()
                    engine.settings = settings()
                    val snap = engine.snapshot(now)
                    _snapshot.value = snap
                    speaker.update(snap.alert, prefs.sound.value, now)
                    delay(500)
                }
            }
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
        stopLocation()
        TrackingService.stop(context)
    }

    private fun settings() = TrafficSettings(ceilingAglM = prefs.ceilingAglM.value.toDouble())

    private suspend fun loadDdb() {
        val db = withContext(Dispatchers.IO) {
            DdbRepository(File(context.cacheDir, "ogn-ddb.json")).load()
        }
        Log.i(TAG, "OGN device database: ${db.size} devices")
        liveDdb = db
        if (!prefs.demo.value) engine.ddb = db
    }

    private suspend fun runLive() {
        val ref = airfield.reference
        val client = AprsClient(AprsClient.rangeFilter(ref.lat, ref.lon, 30))
        client.connectForever().collect { e ->
            if (e is AprsEvent.Disconnected) Log.i(TAG, "OGN disconnected: ${e.reason}")
            engine.onAprsEvent(e)
        }
    }

    private suspend fun runDemo() {
        val demo = DemoTraffic(airfield)
        engine.ddb = demo.devices
        engine.onAprsEvent(AprsEvent.Connected("démo"))
        val start = System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            val since = now - start
            demo.beaconsAt(since, now).forEach { engine.onLine(it, now) }
            engine.onCartPosition(airfield.frame.toGeo(demo.cartAt(since)), 4.0, now)
            delay(1000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (listening) return
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val lm = context.getSystemService(LocationManager::class.java)
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
            listening = true
        }.onFailure { Log.w(TAG, "No GPS updates", it) }
    }

    private fun stopLocation() {
        if (!listening) return
        context.getSystemService(LocationManager::class.java).removeUpdates(locationListener)
        listening = false
        scope.launch { engine.onCartPositionLost() }
    }

    companion object {
        private const val TAG = "PicPiste"
    }
}
