package fr.cvvm.picpiste.core.airfield

import fr.cvvm.picpiste.core.ddb.httpGet
import fr.cvvm.picpiste.core.i18n.Texts
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Downloads an airfield from OpenStreetMap by its code (blocking: call from an IO thread).
 *
 * First the Overpass API, made for this kind of query. The public Overpass server is often
 * overloaded, though: then the airfield is located with Nominatim (OSM search) and its area
 * downloaded from the main OSM API, one small request each.
 */
class AirfieldDownloader(
    private val post: (url: String, body: String) -> String = ::httpPostForm,
    private val get: (url: String) -> String = { httpGet(it, timeoutMs = 30_000) },
    private val servers: List<String> = SERVERS,
) {
    companion object {
        val SERVERS = listOf("https://overpass-api.de/api/interpreter")
        const val NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
        const val OSM_API_URL = "https://api.openstreetmap.org/api/0.6/map.json"
        /** Free elevation service (Copernicus 90 m model), used when OSM has no `ele` tag. */
        const val ELEVATION_URL = "https://api.open-meteo.com/v1/elevation"
        /** Margin around the airfield's box for the OSM API download, in degrees (~300 m). */
        private const val BOX_MARGIN_DEG = 0.003
    }

    /** @throws AirfieldImportException with a French message for the screen. */
    fun download(code: String): ImportResult {
        val c = OsmImport.normalizeCode(code)
        if (!OsmImport.CODE.matches(c)) throw AirfieldImportException(Texts.current.invalidCode(code))
        val text = fetchOverpass(c) ?: fetchOsmApi(c)
        val first = OsmImport.build(c, text)
        if (!first.elevationMissing) return first
        val ref = first.config.reference
        val elevation = runCatching { elevationAt(ref[0], ref[1]) }.getOrNull()
        return OsmImport.build(c, text, elevation)
    }

    /** Overpass answer, or null when no server gave a usable one (busy, down, offline). */
    private fun fetchOverpass(code: String): String? {
        val body = "data=" + URLEncoder.encode(OsmImport.query(code), Charsets.UTF_8)
        for (url in servers) {
            val text = runCatching { post(url, body) }.getOrNull() ?: continue
            // An empty answer is fine: the code is unknown.
            // "remark": "runtime error: Query timed out…" comes with a partial answer: not usable.
            val partial = Regex("\"remark\"\\s*:\\s*\"[^\"]*(error|timed out)", RegexOption.IGNORE_CASE).containsMatchIn(text)
            if (text.trimStart().startsWith("{") && text.contains("\"elements\"") && !partial) return text
        }
        return null
    }

    /** Finds the airfield with Nominatim, then downloads its area from the OSM API. */
    private fun fetchOsmApi(code: String): String {
        val found = try {
            Json.parseToJsonElement(get("$NOMINATIM_URL?q=${URLEncoder.encode(code, Charsets.UTF_8)}&format=jsonv2&limit=10")).jsonArray
        } catch (e: Exception) {
            throw AirfieldImportException(Texts.current.osmNotResponding, e)
        }
        val place = found.map { it.jsonObject }.firstOrNull {
            it["category"]?.jsonPrimitive?.content == "aeroway" && it["type"]?.jsonPrimitive?.content == "aerodrome"
        } ?: throw AirfieldImportException(Texts.current.noAirfield(code))
        // boundingbox: [south, north, west, east]
        val box = place["boundingbox"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toDoubleOrNull() }
            ?.takeIf { it.size == 4 }
            ?: throw AirfieldImportException(Texts.current.noPosition(code))
        val bbox = String.format(
            Locale.ROOT, "%.6f,%.6f,%.6f,%.6f",
            box[2] - BOX_MARGIN_DEG, box[0] - BOX_MARGIN_DEG, box[3] + BOX_MARGIN_DEG, box[1] + BOX_MARGIN_DEG,
        )
        return try {
            get("$OSM_API_URL?bbox=$bbox")
        } catch (e: Exception) {
            throw AirfieldImportException(Texts.current.osmNotResponding, e)
        }
    }

    private fun elevationAt(lat: Double, lon: Double): Double? {
        val url = String.format(Locale.ROOT, "%s?latitude=%.6f&longitude=%.6f", ELEVATION_URL, lat, lon)
        val o = Json.parseToJsonElement(get(url)).jsonObject
        return (o["elevation"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.doubleOrNull
    }
}

fun httpPostForm(url: String, body: String, timeoutMs: Int = 35_000): String {
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    conn.connectTimeout = 10_000
    conn.readTimeout = timeoutMs
    conn.requestMethod = "POST"
    conn.doOutput = true
    // Overpass asks apps to say who they are.
    conn.setRequestProperty("User-Agent", "PicPiste (Android, https://github.com/CorentinGrard/PicPiste)")
    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
    try {
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}
