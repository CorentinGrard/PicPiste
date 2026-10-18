package fr.cvvm.picpiste.core.demo

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.aprs.AircraftType
import fr.cvvm.picpiste.core.aprs.AprsFormatter
import fr.cvvm.picpiste.core.ddb.DdbDevice
import fr.cvvm.picpiste.core.ddb.DeviceDatabase
import fr.cvvm.picpiste.core.geo.Vec2
import kotlin.math.max

/**
 * Made-up traffic around the airfield, written as OGN beacons so it goes through the
 * real parser and status engine. Lets us try the app on a day without flights.
 *
 * Scenario (repeats every [periodMs]): a glider joins the circuit and lands on 30 herbe,
 * a tow takes off from 30 dur gauche and the tug comes back to land on 30 dur droite,
 * a second glider comes back from the north and lands on 30 herbe.
 */
class DemoTraffic(private val airfield: Airfield) {
    private data class Wp(val x: Double, val y: Double, val aglM: Double, val kmh: Double)

    private class Path(private val wps: List<Wp>, val holdAtEndMs: Long) {
        private val segMs: List<Long> = wps.zipWithNext { a, b ->
            val dist = Vec2(b.x - a.x, b.y - a.y).length
            val avgMs = max((a.kmh + b.kmh) / 2, 2.0) / 3.6
            (dist / avgMs * 1000).toLong().coerceAtLeast(1000)
        }
        val flyMs = segMs.sum()

        /** Time from the start to reach waypoint [index]. */
        fun timeTo(index: Int): Long = segMs.take(index).sum()
        val durationMs = flyMs + holdAtEndMs

        /** Position at [t] ms after the start, null before the start. */
        fun at(t: Long): Sample? {
            if (t < 0) return null
            var rest = t
            for ((i, d) in segMs.withIndex()) {
                if (rest <= d) {
                    val a = wps[i]
                    val b = wps[i + 1]
                    val f = rest.toDouble() / d
                    val dir = Vec2(b.x - a.x, b.y - a.y)
                    return Sample(
                        pos = Vec2(a.x + dir.x * f, a.y + dir.y * f),
                        aglM = a.aglM + (b.aglM - a.aglM) * f,
                        kmh = a.kmh + (b.kmh - a.kmh) * f,
                        dir = dir,
                        climbMs = (b.aglM - a.aglM) / (d / 1000.0),
                    )
                }
                rest -= d
            }
            val a = wps[wps.size - 2]
            val b = wps.last()
            return Sample(Vec2(b.x, b.y), b.aglM, 0.0, Vec2(b.x - a.x, b.y - a.y), 0.0)
        }
    }

    private data class Sample(val pos: Vec2, val aglM: Double, val kmh: Double, val dir: Vec2, val climbMs: Double)

    private class Actor(
        val address: String,
        val type: AircraftType,
        val path: Path,
        val startMs: Long,
        /** Stop sending after this many ms on the path (glider released from tow, out of sight). */
        val stopAfterMs: Long? = null,
        val delayMs: Long = 0,
    )

    private val herbe = airfield.runway("herbe")!!
    private val droite = airfield.runway("dur-droite")!!
    private val gauche = airfield.runway("dur-gauche")!!

    private fun circuitTo30(startX: Double, startY: Double, rwY: Double, thresholdX: Double) = listOf(
        Wp(startX, startY, 395.0, 100.0),
        Wp(-300.0, 1_100.0, 300.0, 95.0),
        Wp(-600.0, 650.0, 260.0, 95.0),
        Wp(900.0, 650.0, 230.0, 95.0),
        Wp(1_300.0, 350.0, 170.0, 90.0),
        Wp(1_300.0, rwY, 140.0, 90.0),
        Wp(thresholdX, rwY, 4.0, 85.0),
        Wp(thresholdX - 140, rwY, 0.0, 40.0),
        Wp(thresholdX - 230, rwY, 0.0, 3.0),
    )

    private val tugPath: Path = run {
        val y = gauche.threshold30.y
        val x0 = gauche.threshold30.x - 15
        val yd = droite.threshold30.y
        val xd = droite.threshold30.x
        Path(
            listOf(
                Wp(x0, y, 0.0, 0.5),
                Wp(x0 - 1, y, 0.0, 0.5), // waiting at the threshold
                Wp(x0 - 150, y, 0.0, 70.0),
                Wp(x0 - 330, y, 3.0, 110.0),
                Wp(-450.0, y, 80.0, 120.0),
                Wp(-1_500.0, 250.0, 300.0, 120.0),
                Wp(-2_600.0, 900.0, 600.0, 120.0), // release, out of the display ceiling
                Wp(-2_000.0, 1_600.0, 420.0, 160.0),
                Wp(-600.0, 650.0, 280.0, 150.0),
                Wp(900.0, 650.0, 230.0, 140.0),
                Wp(1_300.0, 300.0, 160.0, 130.0),
                Wp(1_300.0, yd, 120.0, 120.0),
                Wp(xd, yd, 4.0, 100.0),
                Wp(xd - 200, yd, 0.0, 40.0),
                Wp(xd - 300, yd, 0.0, 10.0),
            ),
            holdAtEndMs = 60_000,
        )
    }

    private val actors: List<Actor> = listOf(
        Actor("DD0A01", AircraftType.GLIDER, Path(circuitTo30(2_600.0, 3_000.0, herbe.threshold30.y, herbe.threshold30.x), 120_000), startMs = 0),
        Actor("DD0A02", AircraftType.TOW_PLANE, tugPath, startMs = 90_000),
        // Towed glider: same path a little behind, silent from the release point (index 6, above the ceiling).
        Actor("DD0A03", AircraftType.GLIDER, tugPath, startMs = 90_000, stopAfterMs = tugPath.timeTo(6), delayMs = 2_500),
        Actor("DD0A04", AircraftType.GLIDER, Path(circuitTo30(-1_800.0, 3_600.0, herbe.threshold30.y, herbe.threshold30.x), 90_000), startMs = 200_000),
    )

    val periodMs: Long = actors.maxOf { it.startMs + it.path.durationMs } + 30_000

    /** Names for the demo aircraft, as the OGN database would give them. */
    val devices = DeviceDatabase(
        listOf(
            DdbDevice("DD0A01", "F", "ASK 21", "F-CXYZ", "AB"),
            DdbDevice("DD0A02", "F", "DR400", "F-GRQB", "RQ"),
            DdbDevice("DD0A03", "F", "Pégase", "F-CKLM", "12"),
            DdbDevice("DD0A04", "F", "Duo Discus", "F-CABC", "PS"),
        ),
    )

    /**
     * Where a pretend cart is, to try the runway border and alert away from the field:
     * every 2 minutes it waits at the starter, drives onto the grass runway, parks there, comes back.
     */
    fun cartAt(sinceStartMs: Long): Vec2 {
        val parked = airfield.starter?.let { Vec2(it.x - 60, it.y - 25) } ?: Vec2(gauche.threshold30.x, gauche.threshold30.y - 60)
        val onHerbe = herbe.threshold30 - herbe.dir12 * (herbe.length * 0.6)
        val t = (sinceStartMs % 120_000) / 1000.0
        fun lerp(a: Vec2, b: Vec2, f: Double) = a + (b - a) * f.coerceIn(0.0, 1.0)
        return when {
            t < 30 -> parked
            t < 45 -> lerp(parked, onHerbe, (t - 30) / 15)
            t < 100 -> onHerbe
            t < 115 -> lerp(onHerbe, parked, (t - 100) / 15)
            else -> parked
        }
    }

    /** Beacons for every demo aircraft at [timeMs], [sinceStartMs] after the demo started. */
    fun beaconsAt(sinceStartMs: Long, timeMs: Long): List<String> {
        val t = sinceStartMs % periodMs
        return actors.mapNotNull { a ->
            val local = t - a.startMs - a.delayMs
            if (local > a.path.durationMs) return@mapNotNull null
            if (a.stopAfterMs != null && local > a.stopAfterMs) return@mapNotNull null
            val s = a.path.at(local) ?: return@mapNotNull null
            val geo = airfield.frame.toGeo(s.pos)
            val track = if (s.dir.length > 0) airfield.frame.bearingOf(s.dir) else null
            AprsFormatter.aircraft(
                address = a.address,
                type = a.type,
                geo = geo,
                altitudeM = airfield.elevationM + s.aglM,
                trackDeg = track,
                groundSpeedKmh = s.kmh,
                climbMs = s.climbMs,
                timeMs = timeMs,
            )
        }
    }
}
