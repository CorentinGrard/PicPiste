package fr.cvvm.picpiste.core.demo

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.Runway
import fr.cvvm.picpiste.core.airfield.RunwayEnd
import fr.cvvm.picpiste.core.aprs.AircraftType
import fr.cvvm.picpiste.core.aprs.AprsFormatter
import fr.cvvm.picpiste.core.ddb.DdbDevice
import fr.cvvm.picpiste.core.ddb.DeviceDatabase
import fr.cvvm.picpiste.core.geo.Vec2
import fr.cvvm.picpiste.core.geo.angleDiffDeg
import kotlin.math.max
import kotlin.math.sign

/**
 * Made-up traffic around the airfield, written as OGN beacons so it goes through the
 * real parser and status engine. Lets us try the app on a day without flights.
 *
 * Scenario (repeats every [periodMs]), built from the runway geometry so it works on any
 * airfield: a glider joins the circuit and lands on the main runway, a tow takes off from
 * another runway (the main one if there is no other) and the tug comes back to land on it,
 * a second glider comes back from the circuit side and lands on the main runway.
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

    /** Main runway: the longest one along the radar axis. Gliders land on it. */
    val main: Runway = airfield.runways
        .filter { angleDiffDeg(it.headingA, airfield.config.axisBearingDeg) < 10 }
        .maxByOrNull { it.length } ?: airfield.runways.maxBy { it.length }

    /** Everything lands towards this end of the main runway (the wind is the same for all). */
    val landingEnd = RunwayEnd.B
    private val landingHeading = main.heading(landingEnd)

    /**
     * Circuit side: away from the other parallel runways and the starter, so the circuit does not
     * fly over the launch point. Left of the landing direction when nothing decides.
     */
    private val side: Double = run {
        val u = main.landingDir(landingEnd)
        val left = Vec2(-u.y, u.x)
        val mid = mid(main)
        val others = airfield.runways.filter { it !== main && isParallel(it, main) }.map(::mid) + listOfNotNull(airfield.starter)
        val lean = others.sumOf { sign((it - mid) dot left) }
        if (lean > 0) -1.0 else 1.0
    }

    private val gliderFrame = RunwayFrame(main, landingEnd, side)

    /**
     * Tow runway: another parallel runway if there is one (the farthest from the circuit side),
     * else a crossing runway, else the main runway. Takes off and lands in the end closest to the
     * landing direction.
     */
    val tow: Runway = run {
        val others = airfield.runways.filter { it !== main }
        val parallel = others.filter { isParallel(it, main) }
        parallel.minByOrNull { gliderFrame.lateral(mid(it)) }
            ?: others.maxByOrNull { it.length }
            ?: main
    }
    val towEnd = RunwayEnd.entries.minBy { angleDiffDeg(tow.heading(it), landingHeading) }

    /** The tug turns towards the gliders' circuit after takeoff. */
    private val towFrame: RunwayFrame = run {
        val probe = RunwayFrame(tow, towEnd, 1.0)
        val circuitPoint = gliderFrame.at(main.length / 2, DOWNWIND_M)
        val towardCircuit = probe.lateral(circuitPoint)
        RunwayFrame(tow, towEnd, if (towardCircuit < 0) -1.0 else 1.0)
    }

    /**
     * A runway-relative frame: [a] metres along the landing direction from the threshold
     * (negative on final), [c] metres to the circuit side of the centreline.
     */
    private class RunwayFrame(val rw: Runway, end: RunwayEnd, val side: Double) {
        val t = rw.threshold(end)
        val u = rw.landingDir(end)
        val n = Vec2(-u.y, u.x) * side
        fun at(a: Double, c: Double) = t + u * a + n * c
        fun wp(a: Double, c: Double, aglM: Double, kmh: Double) = at(a, c).let { Wp(it.x, it.y, aglM, kmh) }
        fun lateral(p: Vec2) = (p - t) dot n
    }

    /**
     * Circuit to the main runway from an entry point [entryA], [entryC] (relative to the runway
     * middle): crosswind beyond the far end, downwind, base, final, roll-out.
     */
    private fun gliderCircuit(entryA: Double, entryC: Double): List<Wp> = with(gliderFrame) {
        val l = rw.length
        listOf(
            wp(l / 2 + entryA, entryC, 395.0, 100.0),
            wp(l / 2 + 300, 1_100.0, 300.0, 95.0),
            wp(l + 300, DOWNWIND_M, 260.0, 95.0),
            wp(-650.0, DOWNWIND_M, 230.0, 95.0),
            wp(-BASE_M, 350.0, 170.0, 90.0),
            wp(-BASE_M, 0.0, 140.0, 90.0),
            wp(0.0, 0.0, 4.0, 85.0),
            wp(140.0, 0.0, 0.0, 40.0),
            wp(230.0, 0.0, 0.0, 3.0),
        )
    }

    /** Takeoff roll, tow out of sight (released above the display ceiling), circuit and landing. */
    private val tugPath: Path = with(towFrame) {
        val l = rw.length
        // Lift off by 345 m, or within the runway if it is shorter.
        val liftOff = minOf(345.0, l * 0.8)
        Path(
            listOf(
                wp(15.0, 0.0, 0.0, 0.5),
                wp(16.0, 0.0, 0.0, 0.5), // waiting at the threshold
                wp(liftOff * 0.48, 0.0, 0.0, 70.0),
                wp(liftOff, 0.0, 3.0, 110.0),
                wp(maxOf(l + 100, 600.0), 0.0, 80.0, 120.0),
                wp(l + 1_200, 250.0, 300.0, 120.0),
                wp(l + 2_300, 900.0, 600.0, 120.0), // release, out of the display ceiling
                wp(l + 1_700, 1_600.0, 420.0, 160.0),
                wp(l + 300, DOWNWIND_M, 280.0, 150.0),
                wp(-650.0, DOWNWIND_M, 230.0, 140.0),
                wp(-BASE_M, 300.0, 160.0, 130.0),
                wp(-BASE_M, 0.0, 120.0, 120.0),
                wp(0.0, 0.0, 4.0, 100.0),
                wp(200.0, 0.0, 0.0, 40.0),
                wp(300.0, 0.0, 0.0, 10.0),
            ),
            holdAtEndMs = 60_000,
        )
    }

    private val actors: List<Actor> = listOf(
        Actor("DD0A01", AircraftType.GLIDER, Path(gliderCircuit(-2_600.0, 3_000.0), 120_000), startMs = 0),
        Actor("DD0A02", AircraftType.TOW_PLANE, tugPath, startMs = 90_000),
        // Towed glider: same path a little behind, silent from the release point (index 6, above the ceiling).
        Actor("DD0A03", AircraftType.GLIDER, tugPath, startMs = 90_000, stopAfterMs = tugPath.timeTo(6), delayMs = 2_500),
        Actor("DD0A04", AircraftType.GLIDER, Path(gliderCircuit(1_800.0, 3_600.0), 90_000), startMs = 200_000),
    )

    /**
     * Where the cart waits: beside the starter, or beside the main runway on the side away from
     * the circuit, then pushed out until it is clear of every runway (the starter may be on one).
     */
    private val parked: Vec2 = run {
        val start = airfield.starter?.let { it + (it - mid(main)).normalized() * 50.0 }
            ?: gliderFrame.at(main.length / 2, -120.0)
        val away = gliderFrame.n * -1.0
        generateSequence(start) { it + away * 10.0 }.take(300)
            .firstOrNull { !airfield.inRunwayZone(it, CART_CLEARANCE_M) } ?: start
    }

    private val onRunway = gliderFrame.at(main.length * 0.6, 0.0)

    /** Receiver name in the beacons: the airfield code, as if a station stood on the field. */
    private val receiver = airfield.icao.filter { it.isLetterOrDigit() }.take(9).ifEmpty { "DEMO" }

    val periodMs: Long = actors.maxOf { it.startMs + it.path.durationMs } + 30_000

    /** Names for the demo aircraft, as the OGN database would give them. */
    val devices = DeviceDatabase(
        listOf(
            DdbDevice("DD0A01", "F", "ASK 21", "F-CXYZ", "AB"),
            DdbDevice("DD0A02", "F", "DR400", "F-GRQB", "RQ"),
            DdbDevice("DD0A03", "F", "Pégase", "F-CKLM", "KL"),
            DdbDevice("DD0A04", "F", "Duo Discus", "F-CABC", "PS"),
        ),
    )

    /**
     * Where a pretend cart is, to try the runway border and alert away from the field:
     * every 2 minutes it waits at the starter, drives onto the main runway, parks there, comes back.
     */
    fun cartAt(sinceStartMs: Long): Vec2 {
        val t = (sinceStartMs % 120_000) / 1000.0
        fun lerp(a: Vec2, b: Vec2, f: Double) = a + (b - a) * f.coerceIn(0.0, 1.0)
        return when {
            t < 30 -> parked
            t < 45 -> lerp(parked, onRunway, (t - 30) / 15)
            t < 100 -> onRunway
            t < 115 -> lerp(onRunway, parked, (t - 100) / 15)
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
                receiver = receiver,
            )
        }
    }

    private companion object {
        /** Distance of the downwind leg from the runway centreline. */
        const val DOWNWIND_M = 650.0

        /** Distance of the base leg before the threshold. */
        const val BASE_M = 1_000.0

        /** The parked cart stays this far from any runway, beyond the presence detector's margins. */
        const val CART_CLEARANCE_M = 40.0

        fun mid(rw: Runway) = (rw.thresholdA + rw.thresholdB) * 0.5

        fun isParallel(a: Runway, b: Runway) = angleDiffDeg(a.headingA, b.headingA).let { it < 10 || it > 170 }
    }
}
