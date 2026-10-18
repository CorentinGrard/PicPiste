package fr.cvvm.picpiste.render

import android.graphics.Color
import fr.cvvm.picpiste.core.traffic.AircraftState
import fr.cvvm.picpiste.core.traffic.FlightStatus

/** Colours from the design canvas ("Système visuel"). */
object Palette {
    val BG = Color.parseColor("#0B0E12")
    val MAP_BG = Color.parseColor("#0F1419")
    val SURFACE = Color.parseColor("#151A21")
    val SURFACE_2 = Color.parseColor("#1E252E")
    val RING = Color.parseColor("#1E2630")
    val BOUNDARY_FILL = Color.parseColor("#131A20")
    val BOUNDARY_STROKE = Color.parseColor("#26303B")
    val GRASS = Color.parseColor("#1F3A27")
    val GRASS_EDGE = Color.parseColor("#2E5A3A")
    val PAVED = Color.parseColor("#5A6270")
    val APRON = Color.parseColor("#1A222B")
    val TAXIWAY = Color.parseColor("#2A323C")
    val HANGAR = Color.parseColor("#262E39")
    val HANGAR_EDGE = Color.parseColor("#3A4552")
    val WINDSOCK = Color.parseColor("#F472D0")
    val STARTER_FILL = Color.parseColor("#1A222C")
    val STARTER_EDGE = Color.parseColor("#4A5666")
    val TEXT = Color.parseColor("#E6EAEF")
    val TEXT_2 = Color.parseColor("#C3CBD5")
    val MUTED = Color.parseColor("#9AA4B0")
    val FAINT = Color.parseColor("#56606C")
    val CART = Color.parseColor("#38BDF8")
    val CART_FILL = Color.parseColor("#E6F6FF")
    val RED = Color.parseColor("#FF5A4E")
    val RED_TEXT = Color.parseColor("#FF7B71")
    val BANNER = Color.parseColor("#C8251B")
    val OK = Color.parseColor("#3DDC97")

    val FINAL = RED
    val TAKEOFF = Color.parseColor("#F472D0")
    val CIRCUIT = Color.parseColor("#FFC53D")
    val APPROACH = Color.parseColor("#5AB0FF")
    val TOW = Color.parseColor("#A78BFA")
    val LANDED = OK
    val ON_GROUND = Color.parseColor("#7FA88C")
    val AIRBORNE = Color.parseColor("#C3CBD5")
    val LOST = Color.parseColor("#6B7480")
}

fun FlightStatus.color(): Int = when (this) {
    FlightStatus.FINAL -> Palette.FINAL
    FlightStatus.TAKEOFF -> Palette.TAKEOFF
    FlightStatus.CIRCUIT -> Palette.CIRCUIT
    FlightStatus.APPROACH -> Palette.APPROACH
    FlightStatus.TOW -> Palette.TOW
    FlightStatus.LANDED -> Palette.LANDED
    FlightStatus.ON_GROUND -> Palette.ON_GROUND
    FlightStatus.AIRBORNE -> Palette.AIRBORNE
}

val AircraftState.color: Int get() = if (signalLost) Palette.LOST else status.color()
