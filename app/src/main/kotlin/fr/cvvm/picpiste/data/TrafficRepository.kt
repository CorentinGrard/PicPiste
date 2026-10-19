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
    private var liveDdb: DeviceDatabase = DeviceDatabase.EMPTY
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
                // A new source or a new airfield: start over with a fresh engine.
                combine(prefs.source, airfields.current, airfields.configured) { s, a, ok -> Triple(s, a, ok) }.collectLatest { (source, field, configured) ->
                    clock = System::currentTimeMillis
                    _replay.value = ReplayInfo(null, null, null)
                    engine = PicPisteEngine(field, settings()).apply { ddb = liveDdb }
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
        if (prefs.source.value != Source.DEMO) engine.ddb = db
    }

    private suspend fun runLive() {
        val ref = airfield.reference
        val client = AprsClient(AprsClient.rangeFilter(ref.lat, ref.lon, 30))
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
