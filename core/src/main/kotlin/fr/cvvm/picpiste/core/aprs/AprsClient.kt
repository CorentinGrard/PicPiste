package fr.cvvm.picpiste.core.aprs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random

sealed interface AprsEvent {
    data object Connecting : AprsEvent
    data class Connected(val server: String) : AprsEvent
    data class Line(val text: String, val receivedAtMs: Long) : AprsEvent
    data class Disconnected(val reason: String, val retryInMs: Long) : AprsEvent
}

/**
 * Read-only client for the OGN APRS feed.
 *
 * The server pushes every position as it arrives: we keep one TCP connection open,
 * send a keepalive now and then, and reconnect with backoff when the link drops
 * or goes quiet (the server sends a comment line about every 20 s).
 */
class AprsClient(
    private val filter: String,
    private val host: String = "aprs.glidernet.org",
    private val port: Int = 14_580,
    private val appVersion: String = "0.1",
    private val callsign: String = "PPST" + Random.nextInt(1000, 9999),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val READ_TIMEOUT_MS = 60_000
        const val KEEPALIVE_EVERY_MS = 120_000L
        private const val MIN_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L

        /** Filter for every station within [radiusKm] of a point. */
        fun rangeFilter(lat: Double, lon: Double, radiusKm: Int) = "r/%.4f/%.4f/%d".format(java.util.Locale.ROOT, lat, lon, radiusKm)
    }

    val loginLine: String get() = "user $callsign pass -1 vers PicPiste $appVersion filter $filter\r\n"

    /** One connection, ends (normally or with an exception) when the link drops. */
    fun connectOnce(): Flow<AprsEvent> = flow {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 15_000)
            socket.soTimeout = READ_TIMEOUT_MS
            socket.tcpNoDelay = true
            val writer = socket.getOutputStream().bufferedWriter(Charsets.ISO_8859_1)
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            writer.write(loginLine)
            writer.flush()
            emit(AprsEvent.Connected("$host:$port"))
            var lastKeepalive = nowMs()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: throw EOFException("Server closed the connection")
                val now = nowMs()
                if (now - lastKeepalive >= KEEPALIVE_EVERY_MS) {
                    writer.write("#keepalive\r\n")
                    writer.flush()
                    lastKeepalive = now
                }
                emit(AprsEvent.Line(line, now))
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Stays connected until cancelled, reconnecting with exponential backoff. */
    fun connectForever(): Flow<AprsEvent> = channelFlow {
        var backoff = MIN_BACKOFF_MS
        while (true) {
            send(AprsEvent.Connecting)
            val reason = try {
                connectOnce().collect { event ->
                    if (event is AprsEvent.Connected) backoff = MIN_BACKOFF_MS
                    send(event)
                }
                "Connexion fermée"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: e::class.simpleName ?: "Erreur réseau"
            }
            send(AprsEvent.Disconnected(reason, backoff))
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }
}
