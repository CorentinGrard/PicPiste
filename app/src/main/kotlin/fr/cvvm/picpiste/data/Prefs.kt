package fr.cvvm.picpiste.data

import android.content.Context
import androidx.annotation.StringRes
import fr.cvvm.picpiste.R
import fr.cvvm.picpiste.core.i18n.Texts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Phone colours. Android Auto ignores it and follows the car's day / night. */
enum class Theme(@StringRes val labelRes: Int) {
    AUTO(R.string.theme_auto),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark),
    ;

    /** Whether this setting means dark colours. Auto: dark from sunset to sunrise at the airfield. */
    fun isDark(daylight: Boolean) = when (this) {
        AUTO -> !daylight
        LIGHT -> false
        DARK -> true
    }
}

/**
 * Vertical reference for the aircraft heights shown. Both come from the aircraft's GPS:
 * altitude is above mean sea level (an altimeter set to QNH), height is above the airfield
 * (set to QFE).
 */
enum class AltitudeRef(@StringRes val labelRes: Int) {
    ALTITUDE(R.string.altitude_ref_qnh),
    HEIGHT(R.string.altitude_ref_qfe),
}

/** What the airfield editor shows under the airfield. */
enum class MapBackground(@StringRes val labelRes: Int) {
    PHOTO(R.string.map_photo),
    PLAN(R.string.map_plan),
    NONE(R.string.map_none),
    ;

    fun next() = entries[(ordinal + 1) % entries.size]
}

/** Per-phone settings. Nothing here is shared with other drivers. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("picpiste", Context.MODE_PRIVATE)

    private val _sound = MutableStateFlow(sp.getBoolean(KEY_SOUND, true))
    private val _ceiling = MutableStateFlow(sp.getInt(KEY_CEILING, 400))
    private val _zoom = MutableStateFlow(sp.getFloat(KEY_ZOOM_RADIUS, 1_500f).toDouble().coerceIn(MIN_RADIUS_M, MAX_RADIUS_M))
    private val _theme = MutableStateFlow(runCatching { Theme.valueOf(sp.getString(KEY_THEME, null) ?: "") }.getOrDefault(Theme.AUTO))
    private val _altitudeRef = MutableStateFlow(
        runCatching { AltitudeRef.valueOf(sp.getString(KEY_ALTITUDE_REF, null) ?: "") }.getOrDefault(AltitudeRef.ALTITUDE),
    )
    private val _mapBackground = MutableStateFlow(
        runCatching { MapBackground.valueOf(sp.getString(KEY_MAP_BACKGROUND, null) ?: "") }.getOrDefault(MapBackground.PHOTO),
    )

    val sound: StateFlow<Boolean> = _sound.asStateFlow()
    /** Display ceiling, metres above the field. */
    val ceilingAglM: StateFlow<Int> = _ceiling.asStateFlow()
    /** Radar zoom: metres from the centre to the nearest edge of the map. */
    val zoomRadiusM: StateFlow<Double> = _zoom.asStateFlow()
    /** Auto follows the phone's dark theme setting. */
    val theme: StateFlow<Theme> = _theme.asStateFlow()
    val mapBackground: StateFlow<MapBackground> = _mapBackground.asStateFlow()
    val altitudeRef: StateFlow<AltitudeRef> = _altitudeRef.asStateFlow()

    fun setAltitudeRef(v: AltitudeRef) {
        sp.edit().putString(KEY_ALTITUDE_REF, v.name).apply()
        _altitudeRef.value = v
    }

    fun setMapBackground(v: MapBackground) {
        sp.edit().putString(KEY_MAP_BACKGROUND, v.name).apply()
        _mapBackground.value = v
    }

    fun setTheme(v: Theme) {
        sp.edit().putString(KEY_THEME, v.name).apply()
        _theme.value = v
    }

    fun setSound(v: Boolean) {
        sp.edit().putBoolean(KEY_SOUND, v).apply()
        _sound.value = v
    }

    fun setCeiling(v: Int) {
        sp.edit().putInt(KEY_CEILING, v).apply()
        _ceiling.value = v
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
        private const val KEY_SOUND = "sound"
        private const val KEY_CEILING = "ceiling"
        private const val KEY_ZOOM_RADIUS = "zoomRadius"
        private const val KEY_THEME = "theme"
        private const val KEY_MAP_BACKGROUND = "mapBackground"
        private const val KEY_ALTITUDE_REF = "altitudeRef"

        /** Zoom button presets, metres from the centre to the nearest edge of the map. */
        val ZOOM_RADII_M = listOf(700.0, 1_500.0, 4_000.0)
        const val MIN_RADIUS_M = 300.0
        const val MAX_RADIUS_M = 6_000.0

        fun zoomLabel(radiusM: Double): String = when {
            radiusM < 1000 -> "${(radiusM / 50).toInt() * 50} m"
            radiusM < 2000 -> String.format(Texts.current.locale, "%.1f km", radiusM / 1000)
            else -> "${Math.round(radiusM / 1000)} km"
        }
    }
}
