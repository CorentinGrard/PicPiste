package fr.cvvm.picpiste.render

import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.cvvm.picpiste.core.traffic.AircraftState
import fr.cvvm.picpiste.core.traffic.FlightStatus

private fun rgb(s: String) = Color.parseColor(s)

/**
 * One colour set for the whole app. [DARK] comes from the design canvas ("Système visuel");
 * [LIGHT] is made for full sun in the cart: light background, strong contrasts, status colours
 * darkened so they stay readable and distinct on white.
 */
class Colors(
    val isDark: Boolean,
    val BG: Int,
    val MAP_BG: Int,
    val SURFACE: Int,
    val SURFACE_2: Int,
    val RING: Int,
    val BOUNDARY_FILL: Int,
    val BOUNDARY_STROKE: Int,
    val GRASS: Int,
    val GRASS_EDGE: Int,
    val PAVED: Int,
    val APRON: Int,
    val TAXIWAY: Int,
    val HANGAR: Int,
    val HANGAR_EDGE: Int,
    val WINDSOCK: Int,
    val STARTER_FILL: Int,
    val STARTER_EDGE: Int,
    val TEXT: Int,
    val TEXT_2: Int,
    val MUTED: Int,
    val FAINT: Int,
    val CART: Int,
    val CART_FILL: Int,
    val RED: Int,
    val RED_TEXT: Int,
    /** Background of an arrival card when that aircraft is the danger. */
    val DANGER_FILL: Int,
    val BANNER: Int,
    val OK: Int,
    val TAKEOFF: Int,
    val CIRCUIT: Int,
    val APPROACH: Int,
    val TOW: Int,
    val ON_GROUND: Int,
    val AIRBORNE: Int,
    val LOST: Int,
) {
    val FINAL get() = RED
    val LANDED get() = OK

    fun of(status: FlightStatus): Int = when (status) {
        FlightStatus.FINAL -> FINAL
        FlightStatus.TAKEOFF -> TAKEOFF
        FlightStatus.CIRCUIT -> CIRCUIT
        FlightStatus.APPROACH -> APPROACH
        FlightStatus.TOW -> TOW
        FlightStatus.LANDED -> LANDED
        FlightStatus.ON_GROUND -> ON_GROUND
        FlightStatus.AIRBORNE -> AIRBORNE
    }

    fun of(a: AircraftState): Int = if (a.signalLost) LOST else of(a.status)

    companion object {
        val DARK = Colors(
            isDark = true,
            BG = rgb("#0B0E12"),
            MAP_BG = rgb("#0F1419"),
            SURFACE = rgb("#151A21"),
            SURFACE_2 = rgb("#1E252E"),
            RING = rgb("#1E2630"),
            BOUNDARY_FILL = rgb("#131A20"),
            BOUNDARY_STROKE = rgb("#26303B"),
            GRASS = rgb("#1F3A27"),
            GRASS_EDGE = rgb("#2E5A3A"),
            PAVED = rgb("#5A6270"),
            APRON = rgb("#1A222B"),
            TAXIWAY = rgb("#2A323C"),
            HANGAR = rgb("#262E39"),
            HANGAR_EDGE = rgb("#3A4552"),
            WINDSOCK = rgb("#F472D0"),
            STARTER_FILL = rgb("#1A222C"),
            STARTER_EDGE = rgb("#4A5666"),
            TEXT = rgb("#E6EAEF"),
            TEXT_2 = rgb("#C3CBD5"),
            MUTED = rgb("#9AA4B0"),
            FAINT = rgb("#56606C"),
            CART = rgb("#38BDF8"),
            CART_FILL = rgb("#E6F6FF"),
            RED = rgb("#FF5A4E"),
            RED_TEXT = rgb("#FF7B71"),
            DANGER_FILL = rgb("#3A1512"),
            BANNER = rgb("#C8251B"),
            OK = rgb("#3DDC97"),
            TAKEOFF = rgb("#F472D0"),
            CIRCUIT = rgb("#FFC53D"),
            APPROACH = rgb("#5AB0FF"),
            TOW = rgb("#A78BFA"),
            ON_GROUND = rgb("#7FA88C"),
            AIRBORNE = rgb("#C3CBD5"),
            LOST = rgb("#6B7480"),
        )

        val LIGHT = Colors(
            isDark = false,
            BG = rgb("#F3F5F8"),
            MAP_BG = rgb("#FFFFFF"),
            SURFACE = rgb("#FFFFFF"),
            SURFACE_2 = rgb("#E4E9EF"),
            RING = rgb("#D2D9E1"),
            BOUNDARY_FILL = rgb("#EEF1F4"),
            BOUNDARY_STROKE = rgb("#98A3AF"),
            GRASS = rgb("#B7DCBE"),
            GRASS_EDGE = rgb("#4F915D"),
            PAVED = rgb("#5F6773"),
            APRON = rgb("#DCE1E7"),
            TAXIWAY = rgb("#BEC6CF"),
            HANGAR = rgb("#C5CDD6"),
            HANGAR_EDGE = rgb("#6F7A87"),
            WINDSOCK = rgb("#B81F86"),
            STARTER_FILL = rgb("#DCE1E7"),
            STARTER_EDGE = rgb("#5F6A78"),
            TEXT = rgb("#0B0E12"),
            TEXT_2 = rgb("#262E38"),
            MUTED = rgb("#4A5461"),
            FAINT = rgb("#6E7884"),
            CART = rgb("#0369A1"),
            CART_FILL = rgb("#E0F2FE"),
            RED = rgb("#D7261E"),
            RED_TEXT = rgb("#B91C14"),
            DANGER_FILL = rgb("#FDE2DF"),
            BANNER = rgb("#C8251B"),
            OK = rgb("#0B8A4D"),
            TAKEOFF = rgb("#B81F86"),
            CIRCUIT = rgb("#9A6200"),
            APPROACH = rgb("#1660C4"),
            TOW = rgb("#6A43D6"),
            ON_GROUND = rgb("#3F6E50"),
            AIRBORNE = rgb("#3A4350"),
            LOST = rgb("#808994"),
        )
    }
}

/**
 * The phone's current colours (see `Prefs.theme`). Backed by Compose state, so screens
 * redraw when it changes. Android Auto does not use it: the car follows its own day / night.
 */
object Palette {
    var current: Colors by mutableStateOf(Colors.DARK)

    val BG get() = current.BG
    val MAP_BG get() = current.MAP_BG
    val SURFACE get() = current.SURFACE
    val SURFACE_2 get() = current.SURFACE_2
    val BOUNDARY_FILL get() = current.BOUNDARY_FILL
    val BOUNDARY_STROKE get() = current.BOUNDARY_STROKE
    val GRASS get() = current.GRASS
    val GRASS_EDGE get() = current.GRASS_EDGE
    val PAVED get() = current.PAVED
    val APRON get() = current.APRON
    val TAXIWAY get() = current.TAXIWAY
    val HANGAR get() = current.HANGAR
    val HANGAR_EDGE get() = current.HANGAR_EDGE
    val WINDSOCK get() = current.WINDSOCK
    val STARTER_EDGE get() = current.STARTER_EDGE
    val TEXT get() = current.TEXT
    val TEXT_2 get() = current.TEXT_2
    val MUTED get() = current.MUTED
    val FAINT get() = current.FAINT
    val CART get() = current.CART
    val CART_FILL get() = current.CART_FILL
    val RED get() = current.RED
    val RED_TEXT get() = current.RED_TEXT
    val BANNER get() = current.BANNER
    val OK get() = current.OK
    val CIRCUIT get() = current.CIRCUIT
    val LANDED get() = current.LANDED
}

/** Status colour in the phone's current colours. */
val AircraftState.color: Int get() = Palette.current.of(this)
