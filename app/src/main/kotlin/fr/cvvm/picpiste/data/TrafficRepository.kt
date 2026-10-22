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
import fr.cvvm.picpiste.R
import fr.cvvm.picpiste.core.PicPisteEngine
import fr.cvvm.picpiste.core.Snapshot
import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.aprs.AprsClient
import fr.cvvm.picpiste.core.aprs.AprsEvent
import fr.cvvm.picpiste.core.aprs.AprsParser
import fr.cvvm.picpiste.core.ddb.DdbRepository
import fr.cvvm.picpiste.core.ddb.DeviceDatabase
import fr.cvvm.picpiste.core.demo.DemoTraffic
import fr.cvvm.picpiste.core.geo.GeoPoint
import fr.cvvm.picpiste.core.replay.Recording
import fr.cvvm.picpiste.core.replay.ReplayClock
import fr.cvvm.picpiste.core.traffic.TrafficSettings
import java.io.File
import java.io.Writer
import java.time.LocalDate
import java.time.ZoneId
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

/** What the replay is playing, for the top bar. */
data class ReplayInfo(val file: String?, val nowMs: Long?, val message: String?)

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

    /** What the current engine was made for: kept when the app comes back to it. */
    private var engineFor: Pair<Source, Airfield>? = null
    private val mainHandler = android.os.Handler(Looper.getMainLooper())
    private val delayedStop = Runnable { stop() }
    private val speaker = AlertSpeaker(context)

    /** Wall clock, or the replay's virtual clock. */
    @Volatile
    private var clock: () -> Long = System::currentTimeMillis

    private val _snapshot = MutableStateFlow(engine.snapshot(System.currentTimeMillis()))
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _replay = MutableStateFlow(ReplayInfo(null, null, null))
    val replay: StateFlow<ReplayInfo> = _replay.asStateFlow()

    /** Recorded days: written here by the app, or copied here with adb to replay them. */
    val recordingsDir: File get() = File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }

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
        if (prefs.source.value != Source.LIVE) return@LocationListener
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
                // A new source or a new airfield: start over with a fresh engine. Coming back
                // to the same live airfield keeps the aircraft and the landed list.
                combine(prefs.source, airfields.current, airfields.configured) { s, a, ok -> Triple(s, a, ok) }.collectLatest { (source, field, configured) ->
                    clock = System::currentTimeMillis
                    _replay.value = ReplayInfo(null, null, null)
                    if (source != Source.LIVE || engineFor != source to field) {
                        engine = PicPisteEngine(field, settings()).apply { ddb = liveDdb }
                        engineFor = source to field
                    }
                    // No airfield chosen yet (welcome screen): no traffic, no network.
                    if (!configured) return@collectLatest
                    when (source) {
                        Source.LIVE -> runLive()
                        Source.DEMO -> runDemo()
                        Source.REPLAY -> runReplay()
                    }
                }
            }
            launch {
                while (true) {
                    val now = clock()
                    engine.settings = settings()
                    val snap = engine.snapshot(now)
                    _snapshot.value = snap
                    speaker.update(snap.alert, prefs.sound.value, System.currentTimeMillis())
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
            if (prefs.source.value != Source.DEMO) engine.ddb = db
        }
        _ddbStatus.value = DdbStatus(liveDdb.size, liveDdb.updatedAtMs)
    }

    private suspend fun runLive() {
        val ref = airfield.reference
        val client = AprsClient(AprsClient.rangeFilter(ref.lat, ref.lon, AprsClient.FEED_RADIUS_KM))
        var day: LocalDate? = null
        var out: Writer? = null
        try {
            client.connectForever().collect { e ->
                if (e is AprsEvent.Disconnected) Log.i(TAG, "OGN disconnected: ${e.reason}")
                engine.onAprsEvent(e)
                if (e is AprsEvent.Line && prefs.record.value) {
                    val b = AprsParser.parse(e.text)
                    if (b != null && !b.noTrack) {
                        val today = LocalDate.now(ZoneId.of("Europe/Paris"))
                        if (today != day) {
                            out?.close()
                            day = today
                            out = File(recordingsDir, "${airfield.icao}-$today.tsv").let { java.io.FileWriter(it, true).buffered() }
                        }
                        withContext(Dispatchers.IO) {
                            Recording.write(out!!, e.receivedAtMs, e.text)
                            out!!.flush()
                        }
                    }
                }
            }
        } finally {
            withContext(Dispatchers.IO) { out?.close() }
        }
    }

    private suspend fun runDemo() {
        val demo = DemoTraffic(airfield)
        engine.ddb = demo.devices
        engine.onAprsEvent(AprsEvent.Connected("demo"))
        val start = System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            val since = now - start
            demo.beaconsAt(since, now).forEach { engine.onLine(it, now) }
            engine.onCartPosition(airfield.frame.toGeo(demo.cartAt(since)), 4.0, now)
            delay(1000)
        }
    }

    /** Plays the most recent recording, with the demo cart driving on the main runway. */
    private suspend fun runReplay() {
        val file = withContext(Dispatchers.IO) {
            recordingsDir.listFiles { f -> f.name.endsWith(".tsv") }?.maxByOrNull { it.name }
        }
        if (file == null) {
            _replay.value = ReplayInfo(null, null, context.getString(R.string.no_recording, recordingsDir.path))
            return
        }
        val entries = withContext(Dispatchers.IO) { file.bufferedReader().use { Recording.read(it).toList() } }
        val replay = ReplayClock(entries, prefs.replaySpeed.value)
        if (replay.isEmpty) {
            _replay.value = ReplayInfo(file.name, null, context.getString(R.string.empty_recording))
            return
        }
        clock = { replay.nowMs }
        engine.onAprsEvent(AprsEvent.Connected("replay"))
        val demo = DemoTraffic(airfield)
        var last = System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            replay.speed = prefs.replaySpeed.value
            replay.advance(now - last).forEach { (t, line) -> engine.onLine(line, t) }
            last = now
            val t = replay.nowMs
            engine.onCartPosition(airfield.frame.toGeo(demo.cartAt(t - replay.startMs)), 4.0, t)
            _replay.value = ReplayInfo(file.name, t, null)
            if (replay.isFinished) {
                delay(5_000)
                engine = PicPisteEngine(airfield, settings()).apply { ddb = liveDdb }
                replay.restart()
                last = System.currentTimeMillis()
            }
            delay(200)
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
