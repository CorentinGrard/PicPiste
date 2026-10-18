package fr.cvvm.picpiste.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the traffic comes from. */
enum class Source(val label: String) {
    LIVE("En direct"),
    DEMO("Démo"),
    REPLAY("Rejeu"),
}

/** Per-phone settings. Nothing here is shared with other drivers. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("picpiste", Context.MODE_PRIVATE)

    private val _source = MutableStateFlow(
        runCatching { Source.valueOf(sp.getString(KEY_SOURCE, null) ?: "") }.getOrNull()
            ?: if (sp.getBoolean(KEY_DEMO_OLD, false)) Source.DEMO else Source.LIVE,
    )
    private val _sound = MutableStateFlow(sp.getBoolean(KEY_SOUND, true))
    private val _record = MutableStateFlow(sp.getBoolean(KEY_RECORD, false))
    private val _ceiling = MutableStateFlow(sp.getInt(KEY_CEILING, 400))
    private val _zoom = MutableStateFlow(sp.getFloat(KEY_ZOOM_RADIUS, 1_500f).toDouble().coerceIn(MIN_RADIUS_M, MAX_RADIUS_M))
    private val _replaySpeed = MutableStateFlow(sp.getInt(KEY_REPLAY_SPEED, 4))

    val source: StateFlow<Source> = _source.asStateFlow()
    val sound: StateFlow<Boolean> = _sound.asStateFlow()
    /** Save the live OGN beacons of the day, to replay and tune the engine later. */
    val record: StateFlow<Boolean> = _record.asStateFlow()
    /** Display ceiling, metres above the field. */
    val ceilingAglM: StateFlow<Int> = _ceiling.asStateFlow()
    /** Radar zoom: metres from the centre to the nearest edge of the map. */
    val zoomRadiusM: StateFlow<Double> = _zoom.asStateFlow()
    val replaySpeed: StateFlow<Int> = _replaySpeed.asStateFlow()

    fun setSource(v: Source) {
        sp.edit().putString(KEY_SOURCE, v.name).apply()
        _source.value = v
    }

    fun setSound(v: Boolean) {
        sp.edit().putBoolean(KEY_SOUND, v).apply()
        _sound.value = v
    }

    fun setRecord(v: Boolean) {
        sp.edit().putBoolean(KEY_RECORD, v).apply()
        _record.value = v
    }

    fun setCeiling(v: Int) {
        sp.edit().putInt(KEY_CEILING, v).apply()
        _ceiling.value = v
    }

    fun setReplaySpeed(v: Int) {
        sp.edit().putInt(KEY_REPLAY_SPEED, v).apply()
        _replaySpeed.value = v
    }

    /** Pinch: [factor] > 1 zooms in. Clamped to [MIN_RADIUS_M]..[MAX_RADIUS_M]. */
    fun zoomBy(factor: Double) = setZoomRadius(zoomRadiusM.value / factor)

    fun setZoomRadius(radiusM: Double) {
        val v = radiusM.coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)
        if (v == _zoom.value) return
        _zoom.value = v
        sp.edit().putFloat(KEY_ZOOM_RADIUS, v.toFloat()).apply()
    }

    /** Button: next preset out, back to the closest view after the widest one. */
    fun cycleZoom() = setZoomRadius(ZOOM_RADII_M.firstOrNull { it > zoomRadiusM.value * 1.05 } ?: ZOOM_RADII_M.first())

    companion object {
        private const val KEY_SOURCE = "source"
        private const val KEY_DEMO_OLD = "demo"
        private const val KEY_SOUND = "sound"
        private const val KEY_RECORD = "record"
        private const val KEY_CEILING = "ceiling"
        private const val KEY_ZOOM_RADIUS = "zoomRadius"
        private const val KEY_REPLAY_SPEED = "replaySpeed"

        /** Zoom button presets, metres from the centre to the nearest edge of the map. */
        val ZOOM_RADII_M = listOf(700.0, 1_500.0, 4_000.0)
        const val MIN_RADIUS_M = 300.0
        const val MAX_RADIUS_M = 6_000.0
        val REPLAY_SPEEDS = listOf(1, 4, 10)

        fun zoomLabel(radiusM: Double): String = when {
            radiusM < 1000 -> "${(radiusM / 50).toInt() * 50} m"
            radiusM < 2000 -> String.format(java.util.Locale.FRANCE, "%.1f km", radiusM / 1000)
            else -> "${Math.round(radiusM / 1000)} km"
        }
    }
}
