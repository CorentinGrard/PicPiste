package fr.cvvm.picpiste.core

import fr.cvvm.picpiste.core.aprs.AprsClient
import fr.cvvm.picpiste.core.aprs.AprsEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

class AprsClientTest {
    /** Switching airfield or source cancels the feed: it must stop at once, not at the server's next line. */
    @Test
    fun cancellingStopsAQuietConnectionAtOnce() = runBlocking {
        ServerSocket(0).use { server ->
            // A server that accepts and then says nothing.
            val accepted = thread { runCatching { server.accept().use { Thread.sleep(30_000) } } }
            val client = AprsClient("r/0/0/1", host = "127.0.0.1", port = server.localPort)
            val connected = CompletableDeferred<Unit>()
            val job = launch {
                client.connectOnce().collect { if (it is AprsEvent.Connected) connected.complete(Unit) }
            }
            withTimeout(5_000) { connected.await() }
            val t0 = System.currentTimeMillis()
            withTimeout(3_000) {
                job.cancel()
                job.join()
            }
            assertTrue(System.currentTimeMillis() - t0 < 2_000)
            accepted.interrupt()
        }
    }
}
