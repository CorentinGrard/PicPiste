package fr.cvvm.picpiste.data

import android.content.Context
import android.util.Log
import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.AirfieldConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** An airfield loaded on this phone, as listed in the settings. */
data class AirfieldEntry(val icao: String, val name: String)

/**
 * The airfields loaded on this phone, one JSON file each (the same format that is shared with
 * other drivers), and which one is in use. Until the driver adds one on the welcome screen,
 * [configured] is false and the app shows that screen; [current] then holds the bundled
 * airfield only so the engine has something.
 */
class AirfieldStore(context: Context) {
    private val dir = File(context.filesDir, "airfields")
    private val selectedFile = File(context.filesDir, "airfield.selected")

    init {
        dir.mkdirs()
        // Before several airfields: a single airfield.json.
        val old = File(context.filesDir, "airfield.json")
        if (old.isFile) {
            runCatching {
                val config = Airfield.parse(old.readText()).config
                fileOf(config.icao).writeText(old.readText())
                selectedFile.writeText(config.icao)
            }.onFailure { Log.w("PicPiste", "Old airfield file unreadable", it) }
            old.delete()
        }
    }

    private val _all = MutableStateFlow(list())
    /** Every airfield on this phone, by code. */
    val all: StateFlow<List<AirfieldEntry>> = _all.asStateFlow()

    private val _current = MutableStateFlow(load())
    val current: StateFlow<Airfield> = _current.asStateFlow()

    private val _configured = MutableStateFlow(_all.value.isNotEmpty())
    /** The driver has added at least one airfield (welcome screen or file). */
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    private fun fileOf(icao: String) = File(dir, icao.filter { it.isLetterOrDigit() }.uppercase() + ".json")

    private fun read(icao: String): Airfield? = fileOf(icao).takeIf { it.isFile }?.let { f ->
        runCatching { Airfield.parse(f.readText()) }
            .onFailure { Log.w("PicPiste", "Airfield ${f.name} unreadable", it) }
            .getOrNull()
    }

    private fun list(): List<AirfieldEntry> =
        (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> read(f.nameWithoutExtension)?.let { AirfieldEntry(it.icao, it.name) } }
            .sortedBy { it.icao }

    private fun load(): Airfield {
        val selected = runCatching { selectedFile.readText().trim() }.getOrNull()
        return selected?.let(::read)
            ?: _all.value.firstNotNullOfOrNull { read(it.icao) }
            ?: Airfield.bundled(BUNDLED)
    }

    /** Adds or replaces [config] and uses it. Call from an IO thread; [config] must be valid. */
    fun save(config: AirfieldConfig) {
        val text = Airfield.encode(config)
        val airfield = Airfield(Airfield.decode(text))
        val file = fileOf(config.icao)
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) error("Cannot write ${file.path}")
        _all.value = list()
        use(airfield)
    }

    /** Switches to an airfield already on the phone. Call from an IO thread. */
    fun select(icao: String) {
        read(icao)?.let(::use)
    }

    /** Removes an airfield; if it was in use, switches to another one. Call from an IO thread. */
    fun delete(icao: String) {
        if (_all.value.size <= 1) return
        fileOf(icao).delete()
        _all.value = list()
        if (_current.value.icao == icao) _all.value.firstNotNullOfOrNull { read(it.icao) }?.let(::use)
    }

    private fun use(airfield: Airfield) {
        selectedFile.writeText(airfield.icao)
        _current.value = airfield
        _configured.value = true
    }

    companion object {
        /** Engine placeholder before the first airfield is added; never shown. */
        const val BUNDLED = Airfield.BUNDLED
    }
}
