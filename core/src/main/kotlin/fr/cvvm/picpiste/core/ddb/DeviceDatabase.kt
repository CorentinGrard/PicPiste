package fr.cvvm.picpiste.core.ddb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

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

class DeviceDatabase(devices: Collection<DdbDevice>) {
    private val byAddress = devices.associateBy { it.deviceId.uppercase() }

    val size get() = byAddress.size

    fun lookup(address: String): DdbDevice? = byAddress[address.uppercase()]

    companion object {
        val EMPTY = DeviceDatabase(emptyList())
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String) = DeviceDatabase(json.decodeFromString(DdbFile.serializer(), text).devices)
    }
}

/**
 * Keeps a daily copy of the OGN device database on disk.
 * Falls back to the cached copy (even old) when the download fails.
 */
class DdbRepository(
    private val cacheFile: File,
    private val download: () -> String = { httpGet(DDB_URL) },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = 24 * 3600 * 1000L,
) {
    companion object {
        const val DDB_URL = "https://ddb.glidernet.org/download/?j=1"
    }

    /** Blocking: call from an IO thread. */
    fun load(): DeviceDatabase {
        val cached = cacheFile.takeIf { it.isFile }
        if (cached != null && nowMs() - cached.lastModified() < maxAgeMs) {
            runCatching { return DeviceDatabase.parse(cached.readText()) }
        }
        return try {
            val text = download()
            val db = DeviceDatabase.parse(text)
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(cacheFile)
            db
        } catch (e: Exception) {
            cached?.let { runCatching { DeviceDatabase.parse(it.readText()) }.getOrNull() } ?: DeviceDatabase.EMPTY
        }
    }
}

fun httpGet(url: String, timeoutMs: Int = 20_000): String {
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    conn.connectTimeout = timeoutMs
    conn.readTimeout = timeoutMs
    conn.setRequestProperty("User-Agent", "PicPiste")
    try {
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}
