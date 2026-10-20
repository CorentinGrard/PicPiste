package fr.cvvm.picpiste.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import fr.cvvm.picpiste.core.geo.GeoPoint
import fr.cvvm.picpiste.data.MapBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** A tile server. All of them use the usual web map numbering (z / x / y, 256 px). */
enum class TileSource(val attribution: String, val maxZoom: Int) {
    /** French aerial photos, open licence. Covers France and a strip beyond its borders. */
    IGN("© IGN", 19) {
        override fun url(z: Int, x: Int, y: Int) =
            "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=ORTHOIMAGERY.ORTHOPHOTOS" +
                "&STYLE=normal&TILEMATRIXSET=PM&FORMAT=image/jpeg&TILEMATRIX=$z&TILEROW=$y&TILECOL=$x"
    },
    OSM("© OpenStreetMap", 19) {
        override fun url(z: Int, x: Int, y: Int) = "https://tile.openstreetmap.org/$z/$x/$y.png"
    },
    ;

    abstract fun url(z: Int, x: Int, y: Int): String

    companion object {
        /**
         * Sources to try in order for [background] around [at]. Photos only where IGN has them
         * (France and a little beyond); elsewhere, and where IGN has no tile, the plan.
         * No worldwide photo source: the free ones do not allow use in a distributed app.
         */
        fun forBackground(background: MapBackground, at: GeoPoint): List<TileSource> = when (background) {
            MapBackground.NONE -> emptyList()
            MapBackground.PLAN -> listOf(OSM)
            MapBackground.PHOTO ->
                if (at.lat in 41.0..51.5 && at.lon in -5.5..10.0) listOf(IGN, OSM) else listOf(OSM)
        }
    }
}

/**
 * Map tiles for the editor: kept in memory, and on disk so a map seen once at home still
 * shows on the airfield without network. Downloads run two at a time (OpenStreetMap's rule)
 * and only for tiles still on screen.
 */
class MapTiles private constructor(private val dir: File) {
    class Tile(val bitmap: Bitmap, val source: TileSource)

    private val memory = object : LruCache<String, Tile>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Tile) = value.bitmap.byteCount
    }
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))

    /** Tiles the map wants right now; queued downloads for other tiles are dropped. */
    @Volatile
    private var wanted: Set<String> = emptySet()

    private val _loaded = MutableStateFlow(0)
    /** Changes each time a tile arrives, so the map redraws. */
    val loaded: StateFlow<Int> = _loaded

    init {
        scope.launch { trimDisk() }
    }

    fun key(sources: List<TileSource>, z: Int, x: Int, y: Int) = "${sources.joinToString("+")}/$z/$x/$y"

    /** Call each frame, before [request], with the [key]s of every tile on screen. */
    fun setWanted(keys: Set<String>) {
        wanted = keys
    }

    /** The tile if it is in memory, without loading it. */
    fun peek(sources: List<TileSource>, z: Int, x: Int, y: Int): Tile? = memory.get(key(sources, z, x, y))

    /** The tile if it is ready; otherwise starts loading it and returns null. */
    fun request(sources: List<TileSource>, z: Int, x: Int, y: Int): Tile? {
        val k = key(sources, z, x, y)
        memory.get(k)?.let { return it }
        val failed = failedAt[k]
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return null
        if (!pending.add(k)) return null
        scope.launch {
            try {
                if (k !in wanted) return@launch
                val tile = load(sources, z, x, y)
                if (tile != null) {
                    memory.put(k, tile)
                    failedAt.remove(k)
                    _loaded.value++
                } else {
                    failedAt[k] = System.currentTimeMillis()
                }
            } finally {
                pending.remove(k)
            }
        }
        return null
    }

    /** First source that has the tile: disk copy if fresh, else download, else an old disk copy. */
    private fun load(sources: List<TileSource>, z: Int, x: Int, y: Int): Tile? {
        for (source in sources) {
            val file = File(dir, "${source.name}/$z/$x/$y")
            val fresh = file.isFile && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
            if (fresh) decode(file.readBytes())?.let { return Tile(it, source) }
            val bytes = try {
                download(source.url(z, x, y))
            } catch (_: FileNotFoundException) {
                continue // This source has no photo here: try the next one.
            } catch (_: Exception) {
                null
            }
            if (bytes != null) {
                val bmp = decode(bytes) ?: continue
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                }
                return Tile(bmp, source)
            }
            // Offline: an old copy is better than nothing.
            if (file.isFile) decode(file.readBytes())?.let { return Tile(it, source) }
            return null
        }
        return null
    }

    private fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    private fun download(url: String): ByteArray {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "PicPiste (Android, https://github.com/CorentinGrard/PicPiste)")
            val code = conn.responseCode
            if (code == 404) throw FileNotFoundException(url)
            if (code != 200) error("HTTP $code")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** Keeps the disk copy under [MAX_DISK_BYTES], removing the oldest tiles first. */
    private fun trimDisk() {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_DISK_BYTES * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }

    companion object {
        private const val RETRY_MS = 30_000L
        private const val MAX_AGE_MS = 30L * 24 * 3600 * 1000
        private const val MAX_DISK_BYTES = 200L * 1024 * 1024

        @Volatile
        private var instance: MapTiles? = null

        fun get(context: Context): MapTiles = instance ?: synchronized(this) {
            instance ?: MapTiles(File(context.applicationContext.cacheDir, "tiles")).also { instance = it }
        }
    }
}
