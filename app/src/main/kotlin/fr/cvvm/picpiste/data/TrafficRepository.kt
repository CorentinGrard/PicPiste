package fr.cvvm.picpiste.data

import kotlin.coroutines.resume
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import android.os.CancellationSignal
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
import fr.cvvm.picpiste.core.geo.GeoPoint
import fr.cvvm.picpiste.core.traffic.TrafficSettings
import java.io.File
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The OGN device database in use, for the settings: [devices] 0 means no names at all.
 * [downloading] while a download runs, [failed] when the last one failed.
 */
data class DdbStatus(val devices: Int, val updatedAtMs: Long, val downloading: Boolean = false, val failed: Boolean = false)

/** The phone's own GPS, whatever the traffic source: used by the airfield editor. */
data class PhoneFix(val geo: GeoPoint, val accuracyM: Double?, val timeMs: Long)

/**
 * Runs the PicPiste engine for the whole app. The phone screen and the Android Auto
 * session each [acquire] it while visible; it stops when nobody uses it.
 * All engine calls happen on one thread ([engineDispatcher]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrafficRepository(private val context: Context) {
    val prefs = Prefs(context)
    val airfields = AirfieldStore(context)
    private val airfield: Airfield get() = engine.airfield

    private val engineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + engineDispatcher)
    private var engine = PicPisteEngine(airfields.current.value)
    @Volatile
    private var liveDdb: DeviceDatabase = DeviceDatabase.EMPTY

    /** Shipped copy, downloaded copy, downloads (also used by [DdbRefreshWorker]). */
    val ddbStore = DdbRepository(
        cacheFile = File(context.filesDir, "ogn-ddb.tsv.gz"),
        // Not ".gz": the Android build unpacks and renames those.
        bundled = { context.assets.open("ogn-ddb.dat") },
    )
    private val _ddbStatus = MutableStateFlow(DdbStatus(0, 0))
    val ddbStatus: StateFlow<DdbStatus> = _ddbStatus.asStateFlow()
    private var ddbJob: Job? = null

    /** The airfield the current engine was made for: kept when the app comes back to it. */
    private var engineFor: Airfield? = null
    private val mainHandler = android.os.Handler(Looper.getMainLooper())
    private val delayedStop = Runnable { stop() }
    private val speaker = AlertSpeaker(context)

    private val _snapshot = MutableStateFlow(engine.snapshot(System.currentTimeMillis()))
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private var users = 0
    private var job: Job? = null
    private var listening = false

    private val _phoneFix = MutableStateFlow<PhoneFix?>(null)
    val phoneFix: StateFlow<PhoneFix?> = _phoneFix.asStateFlow()

    private val locationListener = LocationListener { loc: Location ->
        val geo = GeoPoint(loc.latitude, loc.longitude)
        val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
        val now = System.currentTimeMillis()
        _phoneFix.value = PhoneFix(geo, acc, now)
        scope.launch { engine.onCartPosition(geo, acc, now) }
    }

    /** Call from the main thread. */
    fun acquire() {
        users++
        if (users != 1) return
        // Back within the grace period: still running, nothing was missed.
        mainHandler.removeCallbacks(delayedStop)
        if (job == null) start()
    }

    /**
     * Call from the main thread. Keeps running [STOP_GRACE_MS] after the last user left, so a
     * quick look at another app misses no beacon.
     */
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) mainHandler.postDelayed(delayedStop, STOP_GRACE_MS)
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
                // A new airfield: start over with a fresh engine. Coming back to the same
                // airfield keeps the aircraft and the landed list.
                combine(airfields.current, airfields.configured) { a, ok -> a to ok }.collectLatest { (field, configured) ->
                    if (engineFor != field) {
                        engine = PicPisteEngine(field, settings()).apply { ddb = liveDdb }
                        engineFor = field
                    }
                    // No airfield chosen yet (welcome screen): no traffic, no network.
                    if (configured) runLive()
                }
            }
            launch {
                while (true) {
                    val now = System.currentTimeMillis()
                    engine.settings = settings()
                    val snap = engine.snapshot(now)
                    _snapshot.value = snap
                    speaker.update(snap.alert, prefs.sound.value, System.currentTimeMillis())
                    // The radar moves aircraft on between snapshots itself, at every frame.
                    delay(500)
                }
            }
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
        ddbJob?.cancel()
        ddbJob = null
        stopLocation()
        TrackingService.stop(context)
    }

    private fun settings() = TrafficSettings(ceilingAglM = prefs.ceilingAglM.value.toDouble())

    /**
     * Names come from the OGN device database: the newest copy on the phone at once (the one
     * shipped with the app on a fresh install), then a download when it is over a week old,
     * retried until it works (weak signal at the field).
     */
    private suspend fun loadDdb() {
        if (liveDdb.size == 0) {
            useDdb(withContext(Dispatchers.IO) {
                File(context.cacheDir, "ogn-ddb.json").delete() // the 5 MB JSON kept by 0.9.1 and before
                ddbStore.loadLocal()
            })
        }
        var waitMs = 15_000L
        while (ddbStore.isStale(liveDdb)) {
            if (downloadDdb()) return
            delay(waitMs)
            waitMs = (waitMs * 2).coerceAtMost(5 * 60_000L)
        }
    }

    /** Settings button: download now, whatever the age of the copy. */
    fun refreshDdbNow() {
        if (_ddbStatus.value.downloading) return
        ddbJob = scope.launch { downloadDdb() }
    }

    /** A background download finished ([DdbRefreshWorker]). */
    fun onDdbDownloaded() {
        scope.launch { useDdb(withContext(Dispatchers.IO) { ddbStore.loadLocal() }) }
    }

    private suspend fun downloadDdb(): Boolean {
        _ddbStatus.value = _ddbStatus.value.copy(downloading = true, failed = false)
        val db = withContext(Dispatchers.IO) {
            runCatching { ddbStore.download() }.onFailure { Log.w(TAG, "OGN device database not downloaded: $it") }.getOrNull()
        }
        if (db != null) useDdb(db) else _ddbStatus.value = _ddbStatus.value.copy(downloading = false, failed = true)
        return db != null
    }

    /** Runs on the engine thread. */
    private fun useDdb(db: DeviceDatabase) {
        Log.i(TAG, "OGN device database: ${db.size} devices, from ${db.updatedAtMs}")
        if (db.size > 0 && db.updatedAtMs >= liveDdb.updatedAtMs) {
            liveDdb = db
            engine.ddb = db
        }
        _ddbStatus.value = DdbStatus(liveDdb.size, liveDdb.updatedAtMs)
    }

    private suspend fun runLive() {
        val ref = airfield.reference
        val client = AprsClient(AprsClient.rangeFilter(ref.lat, ref.lon, AprsClient.FEED_RADIUS_KM))
        client.connectForever().collect { e ->
            if (e is AprsEvent.Disconnected) Log.i(TAG, "OGN disconnected: ${e.reason}")
            engine.onAprsEvent(e)
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

    private fun locationGranted() =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Where the phone last was, from any source (GPS, wifi, cell), without waiting. Null without permission. */
    @SuppressLint("MissingPermission")
    fun lastKnownPosition(): GeoPoint? {
        if (!locationGranted()) return null
        val lm = context.getSystemService(LocationManager::class.java)
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { GeoPoint(it.latitude, it.longitude) }
    }

    /**
     * A fresh position, from GPS or, indoors, wifi and cell (to place a new airfield, not to
     * survey points). Null without permission or after [timeoutMs].
     */
    @SuppressLint("MissingPermission")
    suspend fun currentPosition(timeoutMs: Long = 20_000): GeoPoint? {
        if (!locationGranted()) return null
        val lm = context.getSystemService(LocationManager::class.java)
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                runCatching {
                    lm.getCurrentLocation(LocationManager.FUSED_PROVIDER, cancel, context.mainExecutor) { loc ->
                        if (cont.isActive) cont.resume(loc?.let { GeoPoint(it.latitude, it.longitude) })
                    }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    private fun stopLocation() {
        if (!listening) return
        context.getSystemService(LocationManager::class.java).removeUpdates(locationListener)
        listening = false
        scope.launch { engine.onCartPositionLost() }
    }

    companion object {
        private const val TAG = "PicPiste"

        /** How long the feed keeps running after the app leaves the screen. */
        const val STOP_GRACE_MS = 2 * 60_000L
    }
}
