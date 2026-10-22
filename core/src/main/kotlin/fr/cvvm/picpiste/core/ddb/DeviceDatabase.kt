package fr.cvvm.picpiste.core.ddb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** One device from the OGN device database (ddb.glidernet.org). */
@Serializable
data class DdbDevice(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_type") val deviceType: String = "",
    @SerialName("aircraft_model") val model: String = "",
    val registration: String = "",
    val cn: String = "",
    val tracked: String = "Y",
    val identified: String = "Y",
) {
    /** Owner opted out of tracking: never show it. */
    val isTracked get() = tracked != "N"

    /** Owner opted out of identification: show it without a name. */
    val isIdentified get() = identified != "N"
}

@Serializable
private data class DdbFile(val devices: List<DdbDevice> = emptyList())

/**
 * The OGN device database, with [updatedAtMs] the time it was downloaded from OGN
 * (0 when unknown).
 */
class DeviceDatabase(devices: Collection<DdbDevice>, val updatedAtMs: Long = 0) {
    private val byAddress = devices.associateBy { it.deviceId.uppercase() }

    val size get() = byAddress.size

    fun lookup(address: String): DdbDevice? = byAddress[address.uppercase()]

    /**
     * Compact form: gzipped, a `#ogn-ddb <updatedAtMs>` header, then one line per device
     * `address TAB registration TAB cn TAB model TAB tracked identified` (e.g. `YN`).
     * About 370 KB instead of the 5 MB JSON.
     */
    fun writeCompact(out: OutputStream) {
        GZIPOutputStream(out).bufferedWriter().use { w ->
            w.write("$HEADER $updatedAtMs\n")
            for (d in byAddress.values) {
                w.write(listOf(d.deviceId, d.registration, d.cn, d.model, d.tracked.take(1) + d.identified.take(1)).joinToString("\t") { it.clean() })
                w.write("\n")
            }
        }
    }

    companion object {
        val EMPTY = DeviceDatabase(emptyList())
        private const val HEADER = "#ogn-ddb"
        private val json = Json { ignoreUnknownKeys = true }

        /** The JSON served by ddb.glidernet.org. */
        fun parse(text: String, updatedAtMs: Long = 0) =
            DeviceDatabase(json.decodeFromString(DdbFile.serializer(), text).devices, updatedAtMs)

        /** Reads [writeCompact]'s form. Throws on anything else. */
        fun readCompact(input: InputStream): DeviceDatabase = GZIPInputStream(input).bufferedReader().use { r ->
            val header = r.readLine()?.split(' ') ?: error("empty")
            require(header.size == 2 && header[0] == HEADER) { "not a compact OGN database" }
            val devices = r.lineSequence().filter { it.isNotEmpty() }.map { line ->
                val f = line.split('\t')
                require(f.size == 5) { "bad line: $line" }
                DdbDevice(
                    deviceId = f[0],
                    registration = f[1],
                    cn = f[2],
                    model = f[3],
                    tracked = f[4].getOrElse(0) { 'Y' }.toString(),
                    identified = f[4].getOrElse(1) { 'Y' }.toString(),
                )
            }.toList()
            DeviceDatabase(devices, header[1].toLong())
        }

        private fun String.clean() = replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    }
}

/**
 * The OGN device database on the phone: a copy shipped in the app (refreshed at each release),
 * replaced by a downloaded one when newer. Downloads happen in the background on Wi-Fi
 * ([download]) and, when the copy is old, while the app runs.
 */
class DdbRepository(
    private val cacheFile: File,
    /** The copy shipped with the app, if any. */
    private val bundled: () -> InputStream? = { null },
    private val fetch: () -> String = { httpGet(DDB_URL, timeoutMs = 30_000) },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val DDB_URL = "https://ddb.glidernet.org/download/?j=1"

        /**
         * Older than this, the running app downloads a new copy, even on mobile data. Younger
         * copies wait for the daily background refresh on Wi-Fi.
         */
        const val MAX_AGE_MS = 7 * 24 * 3600 * 1000L

        /** OGN answers "429 Too many requests" to back-to-back downloads. */
        const val MIN_INTERVAL_MS = 3600 * 1000L
    }

    /** The newest copy on the phone, without network: downloaded, else shipped. Empty if neither reads. */
    fun loadLocal(): DeviceDatabase {
        val cached = cacheFile.takeIf { it.isFile }?.let { f -> runCatching { f.inputStream().use(DeviceDatabase::readCompact) }.getOrNull() }
        val shipped = runCatching { bundled()?.use(DeviceDatabase::readCompact) }.getOrNull()
        return listOfNotNull(cached, shipped).filter { it.size > 0 }.maxByOrNull { it.updatedAtMs } ?: DeviceDatabase.EMPTY
    }

    fun isStale(db: DeviceDatabase) = db.size == 0 || nowMs() - db.updatedAtMs > MAX_AGE_MS

    /**
     * Blocking: downloads the database and keeps it on disk. Throws when it fails. A copy
     * downloaded less than [MIN_INTERVAL_MS] ago (by the background refresh or the app) is
     * returned as is.
     */
    @Synchronized
    fun download(): DeviceDatabase {
        if (cacheFile.isFile && nowMs() - cacheFile.lastModified() in 0 until MIN_INTERVAL_MS) {
            runCatching { cacheFile.inputStream().use(DeviceDatabase::readCompact) }.getOrNull()?.let { return it }
        }
        val db = DeviceDatabase.parse(fetch(), nowMs())
        check(db.size > 0) { "empty OGN database" }
        cacheFile.parentFile?.mkdirs()
        val tmp = File(cacheFile.path + ".tmp")
        tmp.outputStream().use(db::writeCompact)
        check(tmp.renameTo(cacheFile)) { "cannot write ${cacheFile.path}" }
        return db
    }
}

fun httpGet(url: String, timeoutMs: Int = 20_000): String {
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    conn.connectTimeout = timeoutMs
    conn.readTimeout = timeoutMs
    // OSM services (Nominatim, OSM API) ask apps to say who they are.
    conn.setRequestProperty("User-Agent", "PicPiste (Android, https://github.com/CorentinGrard/PicPiste)")
    try {
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}
