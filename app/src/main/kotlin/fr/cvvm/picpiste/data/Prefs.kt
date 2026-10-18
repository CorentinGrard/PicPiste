package fr.cvvm.picpiste.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Per-phone settings. Nothing here is shared with other drivers. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("picpiste", Context.MODE_PRIVATE)

    private val _demo = MutableStateFlow(sp.getBoolean(KEY_DEMO, false))
    private val _sound = MutableStateFlow(sp.getBoolean(KEY_SOUND, true))
    private val _ceiling = MutableStateFlow(sp.getInt(KEY_CEILING, 400))
    private val _zoom = MutableStateFlow(sp.getInt(KEY_ZOOM, 1))

    /** Made-up traffic and cart instead of the live OGN feed. */
    val demo: StateFlow<Boolean> = _demo.asStateFlow()
    val sound: StateFlow<Boolean> = _sound.asStateFlow()
    /** Display ceiling, metres above the field. */
    val ceilingAglM: StateFlow<Int> = _ceiling.asStateFlow()
    /** Index into [ZOOM_RADII_M]. */
    val zoom: StateFlow<Int> = _zoom.asStateFlow()

    fun setDemo(v: Boolean) = put(KEY_DEMO, v) { _demo.value = v }
    fun setSound(v: Boolean) = put(KEY_SOUND, v) { _sound.value = v }
    fun setCeiling(v: Int) = put(KEY_CEILING, v) { _ceiling.value = v }
    fun cycleZoom() = ((zoom.value + 1) % ZOOM_RADII_M.size).let { v -> put(KEY_ZOOM, v) { _zoom.value = v } }

    private fun put(key: String, v: Any, after: () -> Unit) {
        sp.edit().apply {
            when (v) {
                is Boolean -> putBoolean(key, v)
                is Int -> putInt(key, v)
            }
        }.apply()
        after()
    }

    companion object {
        private const val KEY_DEMO = "demo"
        private const val KEY_SOUND = "sound"
        private const val KEY_CEILING = "ceiling"
        private const val KEY_ZOOM = "zoom"

        /** Radar zoom levels: metres from the centre to the nearest edge of the map. */
        val ZOOM_RADII_M = listOf(700.0, 1_500.0, 4_000.0)

        fun zoomLabel(radiusM: Double) = if (radiusM < 1000) "${radiusM.toInt()} m" else "${(radiusM / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }} km"
    }
}
