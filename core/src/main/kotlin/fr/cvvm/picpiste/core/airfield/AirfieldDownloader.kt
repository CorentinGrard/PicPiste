package fr.cvvm.picpiste.core.airfield

import fr.cvvm.picpiste.core.ddb.HttpStatusException
import fr.cvvm.picpiste.core.ddb.httpGet
import fr.cvvm.picpiste.core.i18n.Texts
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Downloads an airfield from OpenStreetMap by its code (blocking: call from an IO thread).
 *
 * First the airfield files published every week ([PUBLISHED], tools/airfields): every
 * airfield in the world with a runway, extracted from OpenStreetMap, one small file each.
 * An airfield not there (no code, or added to OSM since) comes from OpenStreetMap directly.
 *
 * Then the Overpass API, made for this kind of query. The public Overpass server is often
 * overloaded, though: then the airfield is located with Nominatim (OSM search) and its area
 * downloaded from the main OSM API, one small request each (a few for a big airport, see
 * [MAX_TILES]).
 */
class AirfieldDownloader(
    private val post: (url: String, body: String) -> String = ::httpPostForm,
    private val get: (url: String) -> String = { httpGet(it, timeoutMs = 30_000) },
    private val servers: List<String> = SERVERS,
    private val published: List<String> = PUBLISHED,
    /** Text of a published file (gzipped on the server). */
    private val getPublished: (url: String) -> String = { httpGetGzipText(it, timeoutMs = 30_000) },
) {
    companion object {
        /** The weekly airfield files on GitHub Pages; `<CODE>.json.gz` is added (tools/airfields). */
        val PUBLISHED = listOf("https://corentingrard.github.io/PicPiste/a/")
        val SERVERS = listOf("https://overpass-api.de/api/interpreter")
        const val NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
        const val OSM_API_URL = "https://api.openstreetmap.org/api/0.6/map.json"
        /** Free elevation service (Copernicus 90 m model), used when OSM has no `ele` tag. */
        const val ELEVATION_URL = "https://api.open-meteo.com/v1/elevation"
        /** Margin around the airfield's box for the OSM API download, in degrees (~300 m). */
        private const val BOX_MARGIN_DEG = 0.003
        /**
         * The OSM API refuses an area of more than 50 000 nodes (HTTP 400): a big airport, with
         * its buildings and roads, is fetched in quarters, then quarters of those, up to this
         * many requests (Paris-Charles-de-Gaulle and Marseille: 4).
         */
        const val MAX_TILES = 16
        /** What the import uses from the OSM API answer; the rest (roads, buildings…) is dropped at once. */
        private val AEROWAYS = setOf("aerodrome", "runway", "taxiway", "apron", "hangar", "windsock")
    }

    /** @throws AirfieldImportException with a French message for the screen. */
    fun download(code: String): ImportResult {
        val c = OsmImport.normalizeCode(code)
        if (!OsmImport.CODE.matches(c)) throw AirfieldImportException(Texts.current.invalidCode(code))
        val text = fetchPublished(c) ?: fetchOverpass(c) ?: fetchOsmApi(c)
        val first = OsmImport.build(c, text)
        if (!first.elevationMissing) return first
        val ref = first.config.reference
        val elevation = runCatching { elevationAt(ref[0], ref[1]) }.getOrNull()
        return OsmImport.build(c, text, elevation)
    }

    /**
     * The published file for [code], in the Overpass format. Null when it is not published (404:
     * any other copy holds the same files, so OSM directly) or no copy answered.
     */
    private fun fetchPublished(code: String): String? {
        for (base in published) {
            val text = try {
                getPublished("$base$code.json.gz")
            } catch (e: HttpStatusException) {
                if (e.code == 404) return null
                continue
            } catch (e: Exception) {
                continue
            }
            if (text.trimStart().startsWith("{") && text.contains("\"elements\"")) return text
        }
        return null
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
        return fetchArea(Box(box[2] - BOX_MARGIN_DEG, box[0] - BOX_MARGIN_DEG, box[3] + BOX_MARGIN_DEG, box[1] + BOX_MARGIN_DEG))
    }

    /** West, south, east, north, in degrees. */
    private data class Box(val w: Double, val s: Double, val e: Double, val n: Double) {
        val param: String get() = String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", w, s, e, n)

        fun quarters(): List<Box> {
            val x = (w + e) / 2
            val y = (s + n) / 2
            return listOf(Box(w, s, x, y), Box(x, s, e, y), Box(w, y, x, n), Box(x, y, e, n))
        }
    }

    /**
     * The OSM API answer for [box]. Too many nodes in it: fetched in tiles, each trimmed to the
     * aeroway features as soon as it arrives (a tile of a big airport is 10 MB or more), merged.
     */
    private fun fetchArea(box: Box): String {
        val todo = ArrayDeque(listOf(box))
        val kept = LinkedHashMap<String, JsonObject>()
        var fetched = 0
        while (todo.isNotEmpty()) {
            val b = todo.removeFirst()
            val text = try {
                get("$OSM_API_URL?bbox=${b.param}")
            } catch (e: HttpStatusException) {
                if (e.code != 400) throw AirfieldImportException(Texts.current.osmNotResponding, e)
                if (fetched + todo.size + 4 > MAX_TILES) throw AirfieldImportException(Texts.current.airfieldTooBig, e)
                todo.addAll(b.quarters())
                continue
            } catch (e: Exception) {
                throw AirfieldImportException(Texts.current.osmNotResponding, e)
            }
            fetched++
            // Small field: one answer, used as it is.
            if (b === box) return text
            keepAeroways(text, kept)
        }
        return buildJsonObject { put("elements", JsonArray(kept.values.toList())) }.toString()
    }

    /**
     * Adds to [kept] (by type and id: tiles overlap) what [OsmImport] reads: aeroway features and
     * hangars, the ways that outline an aerodrome relation, and the nodes of all those ways.
     */
    private fun keepAeroways(text: String, kept: MutableMap<String, JsonObject>) {
        val elements = try {
            Json.parseToJsonElement(text).jsonObject["elements"]?.jsonArray
        } catch (e: Exception) {
            throw AirfieldImportException(Texts.current.osmUnreadable, e)
        } ?: throw AirfieldImportException(Texts.current.osmUnreadable)
        val objects = elements.mapNotNull { it as? JsonObject }
        fun type(o: JsonObject) = o["type"]?.jsonPrimitive?.content
        fun id(o: JsonObject) = o["id"]?.jsonPrimitive?.longOrNull
        fun tag(o: JsonObject, k: String) = (o["tags"] as? JsonObject)?.get(k)?.jsonPrimitive?.content
        fun wanted(o: JsonObject) = tag(o, "aeroway") in AEROWAYS || tag(o, "building") == "hangar"

        val relations = objects.filter { type(it) == "relation" && tag(it, "aeroway") == "aerodrome" }
        val memberWays = relations.flatMap { r ->
            (r["members"] as? JsonArray).orEmpty().mapNotNull { m ->
                (m as? JsonObject)?.takeIf { it["type"]?.jsonPrimitive?.content == "way" }?.get("ref")?.jsonPrimitive?.longOrNull
            }
        }.toSet()
        val ways = objects.filter { type(it) == "way" && (wanted(it) || id(it) in memberWays) }
        val wayNodes = ways.flatMap { w -> (w["nodes"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.longOrNull } }.toSet()
        val nodes = objects.filter { type(it) == "node" && (id(it) in wayNodes || wanted(it)) }
        for (o in relations + ways + nodes) kept.getOrPut("${type(o)}/${id(o)}") { o }
    }

    /** Ground elevation (metres) from Open-Meteo, or null. Network call: not on the main thread. */
    fun elevationAt(lat: Double, lon: Double): Double? {
        val url = String.format(Locale.ROOT, "%s?latitude=%.6f&longitude=%.6f", ELEVATION_URL, lat, lon)
        val o = Json.parseToJsonElement(get(url)).jsonObject
        return (o["elevation"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.doubleOrNull
    }
}

/**
 * GET of a gzipped text file. GitHub Pages serves `.gz` files as they are, but a proxy may
 * have unpacked it on the way (Content-Encoding): the gzip header tells.
 */
fun httpGetGzipText(url: String, timeoutMs: Int = 30_000): String {
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    conn.connectTimeout = timeoutMs
    conn.readTimeout = timeoutMs
    conn.setRequestProperty("User-Agent", "PicPiste (Android, https://github.com/CorentinGrard/PicPiste)")
    try {
        if (conn.responseCode !in 200..299) throw HttpStatusException(conn.responseCode)
        val bytes = conn.inputStream.use { it.readBytes() }
        val gzipped = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        return if (gzipped) GZIPInputStream(bytes.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8) else bytes.toString(Charsets.UTF_8)
    } finally {
        conn.disconnect()
    }
}

/**
 * [timeoutMs]: a busy Overpass server queues a query before running it; big airports were
 * measured at 50 s for the whole answer. Giving up sooner does not free the server: the query
 * runs on, and a retry waits behind it.
 */
fun httpPostForm(url: String, body: String, timeoutMs: Int = 90_000): String {
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
