package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.demo.DemoTraffic
import fr.cvvm.picpiste.core.replay.Recording
import fr.cvvm.picpiste.core.replay.ReplayClock
import fr.cvvm.picpiste.core.traffic.FlightStatus
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayTest {
    private val lfnl = Airfield.bundled("LFNL")

    @Test
    fun recordingRoundTripsAndReplaysFasterWithGapsSkipped() {
        val demo = DemoTraffic(lfnl)
        val t0 = 1_760_000_000_000L
        val out = StringWriter()
        // 7 minutes of demo, then a 20-minute quiet gap, then one more beacon.
        for (s in 0..420) demo.beaconsAt(s * 1000L, t0 + s * 1000L).forEach { Recording.write(out, t0 + s * 1000L, it) }
        val late = t0 + 40 * 60_000L
        demo.beaconsAt(0, late).take(1).forEach { Recording.write(out, late, it) }

        val entries = Recording.read(out.toString().reader().buffered()).toList()
        assertTrue(entries.size > 400)

        val replay = ReplayClock(entries, speed = 10)
        val engine = PicPisteEngine(lfnl).apply { ddb = demo.devices }
        val seen = mutableSetOf<FlightStatus>()
        var steps = 0
        while (!replay.isFinished && steps < 10_000) {
            replay.advance(200).forEach { (t, line) -> engine.onLine(line, t) }
            engine.snapshot(replay.nowMs).aircraft.firstOrNull { it.identity.label == "AB" }?.let { seen += it.status }
            steps++
        }
        assertTrue(replay.isFinished)
        // 7 min at ×10 ≈ 210 steps of 200 ms; the 20-minute gap must be skipped, not waited.
        assertTrue(steps < 300, "took $steps steps")
        assertTrue(FlightStatus.FINAL in seen && FlightStatus.LANDED in seen, "statuses: $seen")
        assertEquals(entries.last().first, replay.nowMs.coerceAtMost(entries.last().first))
    }
}
