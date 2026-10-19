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
 * The airfield this phone uses, kept in one JSON file (the same format that is shared with other
 * drivers). Until the driver picks one on the welcome screen, [configured] is false and the app
 * shows that screen; [current] then holds the bundled airfield only so the engine has something.
 */
class AirfieldStore(context: Context) {
    private val file = File(context.filesDir, "airfield.json")

    private val _current = MutableStateFlow(load())
    val current: StateFlow<Airfield> = _current.asStateFlow()

    private val _configured = MutableStateFlow(file.isFile)

    /** The driver has chosen an airfield (welcome screen, editor or file). */
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

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
        _configured.value = true
    }

    /** Uses the airfield shipped with the app (saved like any other). */
    fun resetToBundled() = save(Airfield.bundledConfig(BUNDLED))

    companion object {
        const val BUNDLED = Airfield.BUNDLED
    }
}
