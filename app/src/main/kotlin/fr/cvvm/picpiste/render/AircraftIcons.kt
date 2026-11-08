package fr.cvvm.picpiste.render

import android.graphics.Path
import androidx.core.graphics.PathParser
import fr.cvvm.picpiste.core.aprs.AircraftType

/**
 * One icon per kind of aircraft, seen from above, nose up, in a 40 × 40 box centred on the
 * aircraft. Shapes are SVG path data (also what tools/aircraft-icons-preview.py draws):
 * `fill` parts are filled, `line` parts stroked, both in the aircraft's colour.
 */
object AircraftIcons {
    /** [stroke] 0: filled; else the line width, in box units. */
    class Part(val path: Path, val stroke: Float = 0f)

    /** [turns]: drawn along the aircraft's track; a balloon or a ground station has none. */
    class Icon(val parts: List<Part>, val turns: Boolean = true)

    private fun fill(d: String) = Part(PathParser.createPathFromPathData(d))
    private fun line(d: String, width: Float) = Part(PathParser.createPathFromPathData(d), width)

    /** Long thin wings, slim fuselage, T-tail. */
    val GLIDER = Icon(listOf(
        fill("M2 13 H38 V16.5 H2 Z"),
        fill("M18.5 6.5 Q20 3.5 21.5 6.5 V35 H18.5 Z"),
        fill("M13 31 H27 V33.5 H13 Z"),
    ))

    /** Low-wing single-engine plane and its propeller: tow planes, light aircraft. */
    val PLANE = Icon(listOf(
        fill("M20 4.2 L21.3 4.6 L22.2 6.2 L22.6 11.6 L34.6 12.6 L35.8 13.6 L36 16.6 L35.2 17.6 L22.6 18.6 " +
            "L21.5 28.4 L27.4 29.4 L28 30.6 L27.4 32.2 L21.2 32.6 L20 32.6 L18.8 32.6 L12.6 32.2 L12 30.6 " +
            "L12.6 29.4 L18.5 28.4 L17.4 18.6 L4.8 17.6 L4 16.6 L4.2 13.6 L5.4 12.6 L17.4 11.6 L17.8 6.2 L18.7 4.6 Z"),
        fill("M13.5 2.4 H26.5 V3.9 H13.5 Z"),
    ))

    /** Airliner: swept wings with their engines, swept tail. */
    val JET = Icon(listOf(
        fill("M20 2 C21.7 2 22.3 4 22.3 6.5 V29 L20 37.5 L17.7 29 V6.5 C17.7 4 18.3 2 20 2 Z"),
        fill("M22 13.5 L37.5 23 V25.5 L22 21 Z"),
        fill("M18 13.5 L2.5 23 V25.5 L18 21 Z"),
        fill("M27.3 18.6 H29.7 V23.2 H27.3 Z"),
        fill("M10.3 18.6 H12.7 V23.2 H10.3 Z"),
        fill("M21.5 29.5 L28.5 34 V35.8 L21 34.2 Z"),
        fill("M18.5 29.5 L11.5 34 V35.8 L19 34.2 Z"),
    ))

    /** Cabin, tail boom and tail rotor, two crossed main blades. */
    val HELICOPTER = Icon(listOf(
        fill("M20 8.5 C23.6 8.5 24.8 11.8 24.8 15.5 C24.8 19.6 22.6 22.5 20 22.5 C17.4 22.5 15.2 19.6 15.2 15.5 C15.2 11.8 16.4 8.5 20 8.5 Z"),
        fill("M19 21 H21 V34 H19 Z"),
        fill("M15.5 32.5 H24.5 V34.8 H15.5 Z"),
        line("M6 4 L34 27 M34 4 L6 27", 1.8f),
    ))

    /** Delta wing, its trailing edge notched at the keel (the pilot is hidden under it). */
    val HANG_GLIDER = Icon(listOf(
        fill("M20 5 L38.5 29 Q29 25 21.5 28.5 L20 31 L18.5 28.5 Q11 25 1.5 29 Z"),
    ))

    /** Curved canopy, its lines, the pilot hanging under it. */
    val PARAGLIDER = Icon(listOf(
        fill("M2 16 Q20 3 38 16 L37 21.5 Q20 10 3 21.5 Z"),
        line("M5 20.5 L20 31.5 M35 20.5 L20 31.5", 1.6f),
        fill("M16.5 31.5 A3.5 3.5 0 1 0 23.5 31.5 A3.5 3.5 0 1 0 16.5 31.5 Z"),
    ))

    /** Round parachute seen from above: canopy ring and its gores, the skydiver in the middle. */
    val SKYDIVER = Icon(listOf(
        line("M8 20 A12 12 0 1 0 32 20 A12 12 0 1 0 8 20 Z", 4f),
        line("M20 8 V32 M8 20 H32", 1.2f),
        fill("M17.5 20 A2.5 2.5 0 1 0 22.5 20 A2.5 2.5 0 1 0 17.5 20 Z"),
    ))

    /** Envelope, its ropes and the basket: drawn upright, a balloon has no heading. */
    val BALLOON = Icon(listOf(
        fill("M8 16 A12 12 0 1 1 32 16 C32 21.5 27.5 25 23 28 H17 C12.5 25 8 21.5 8 16 Z"),
        line("M17 28 L17.5 31.5 M23 28 L22.5 31.5", 1.2f),
        fill("M16.5 31.5 H23.5 V36.5 H16.5 Z"),
    ), turns = false)

    /** Long envelope with its tail fins. */
    val AIRSHIP = Icon(listOf(
        fill("M20 3.5 C25.5 3.5 27 10 27 19 C27 28.5 24.5 34.5 20 34.5 C15.5 34.5 13 28.5 13 19 C13 10 14.5 3.5 20 3.5 Z"),
        fill("M14.5 27 L8 36.5 L15.5 33.5 Z"),
        fill("M25.5 27 L32 36.5 L24.5 33.5 Z"),
        fill("M19 31 H21 V37.5 H19 Z"),
    ))

    /** Quadcopter: four rotors on crossed arms, a mark on the front. */
    val DRONE = Icon(listOf(
        line("M11 11 L29 29 M29 11 L11 29", 2.4f),
        line("M5.5 11 A5.5 5.5 0 1 0 16.5 11 A5.5 5.5 0 1 0 5.5 11 Z", 1.6f),
        line("M23.5 11 A5.5 5.5 0 1 0 34.5 11 A5.5 5.5 0 1 0 23.5 11 Z", 1.6f),
        line("M5.5 29 A5.5 5.5 0 1 0 16.5 29 A5.5 5.5 0 1 0 5.5 29 Z", 1.6f),
        line("M23.5 29 A5.5 5.5 0 1 0 34.5 29 A5.5 5.5 0 1 0 23.5 29 Z", 1.6f),
        fill("M16 16 H24 V24 H16 Z"),
        fill("M20 10.5 L23 15 H17 Z"),
    ))

    /** A fixed station or obstacle: a diamond, upright. */
    val STATIC = Icon(listOf(
        fill("M20 8 L32 20 L20 32 L8 20 Z"),
    ), turns = false)

    /** Anything else: an arrow along the track. */
    val OTHER = Icon(listOf(
        fill("M20 5 L34 31 L20 25 L6 31 Z"),
    ))

    fun of(type: AircraftType): Icon = when (type) {
        // At a gliding site, an aircraft whose device gives no type is most likely a glider.
        AircraftType.GLIDER, AircraftType.UNKNOWN -> GLIDER
        AircraftType.TOW_PLANE, AircraftType.POWERED, AircraftType.DROP_PLANE -> PLANE
        AircraftType.JET -> JET
        AircraftType.HELICOPTER -> HELICOPTER
        AircraftType.HANG_GLIDER -> HANG_GLIDER
        AircraftType.PARAGLIDER -> PARAGLIDER
        AircraftType.SKYDIVER -> SKYDIVER
        AircraftType.BALLOON -> BALLOON
        AircraftType.AIRSHIP -> AIRSHIP
        AircraftType.UAV -> DRONE
        AircraftType.STATIC -> STATIC
        AircraftType.UFO, AircraftType.RESERVED -> OTHER
    }
}
