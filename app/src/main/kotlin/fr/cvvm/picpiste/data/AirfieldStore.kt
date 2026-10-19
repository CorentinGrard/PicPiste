package fr.cvvm.picpiste.data

import android.content.Context
import android.util.Log
import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.AirfieldConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The airfield this phone uses: the one saved from the editor, else the bundled LFNL.
 * Kept in one JSON file, the same format that is shared with other drivers.
 */
class AirfieldStore(context: Context) {
    private val file = File(context.filesDir, "airfield.json")

    private val _current = MutableStateFlow(load())
    val current: StateFlow<Airfield> = _current.asStateFlow()

    /** True when the phone uses the airfield that ships with the app. */
    val isBundled: Boolean get() = !file.isFile

    private fun load(): Airfield {
        if (file.isFile) {
            runCatching { return Airfield.parse(file.readText()) }
                .onFailure { Log.w("PicPiste", "Saved airfield unreadable, back to $BUNDLED", it) }
        }
        return Airfield.bundled(BUNDLED)
    }

    /** Call from an IO thread. [config] must be valid ([Airfield.decode] checks it again). */
    fun save(config: AirfieldConfig) {
        val text = Airfield.encode(config)
        val airfield = Airfield(Airfield.decode(text))
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) error("Cannot write ${file.path}")
        _current.value = airfield
    }

    fun resetToBundled() {
        file.delete()
        _current.value = Airfield.bundled(BUNDLED)
    }

    companion object {
        const val BUNDLED = "LFNL"
    }
}
