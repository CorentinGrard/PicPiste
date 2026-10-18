package fr.cvvm.picpiste.core.replay

import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.aprs.AprsClient
import fr.cvvm.picpiste.core.aprs.AprsEvent
import fr.cvvm.picpiste.core.aprs.AprsParser
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Records a flying day around LFNL from a PC or a server, without a phone:
 * `./gradlew :core:record` (stop with Ctrl-C). Writes `recordings/LFNL-<date>.tsv`.
 * Only aircraft beacons are kept; aircraft flagged no-track are dropped.
 */
fun main(args: Array<String>) = runBlocking {
    val dir = File(args.firstOrNull() ?: "recordings").apply { mkdirs() }
    val airfield = Airfield.bundled("LFNL")
    val client = AprsClient(AprsClient.rangeFilter(airfield.reference.lat, airfield.reference.lon, 30))
    var day: LocalDate? = null
    var out: java.io.Writer? = null
    var count = 0
    println("Enregistrement OGN autour de ${airfield.icao} dans ${dir.absolutePath} (Ctrl-C pour arrêter)")
    client.connectForever().collect { e ->
        when (e) {
            is AprsEvent.Connected -> println("Connecté à ${e.server}")
            is AprsEvent.Disconnected -> println("Déconnecté (${e.reason}), nouvel essai dans ${e.retryInMs / 1000} s")
            is AprsEvent.Line -> {
                val b = AprsParser.parse(e.text) ?: return@collect
                if (b.noTrack) return@collect
                val today = LocalDate.now(ZoneId.of("Europe/Paris"))
                if (today != day) {
                    out?.close()
                    day = today
                    out = File(dir, "${airfield.icao}-$today.tsv").let { java.io.FileWriter(it, true).buffered() }
                }
                Recording.write(out!!, e.receivedAtMs, e.text)
                if (++count % 100 == 0) {
                    out!!.flush()
                    println("$count positions")
                }
            }
            AprsEvent.Connecting -> Unit
        }
    }
}
