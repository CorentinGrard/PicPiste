package fr.cvvm.picpiste.ui.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.AirfieldConfig
import java.io.File

/** Airfield files shared between drivers: plain JSON, the same as the app keeps for itself. */
object AirfieldFiles {
    private const val MAX_BYTES = 2_000_000

    fun fileName(config: AirfieldConfig) = "PicPiste-${config.icao}.json"

    /** Reads and checks a file the user picked or opened with PicPiste. Throws with a French message. */
    fun read(context: Context, uri: Uri): AirfieldConfig {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_BYTES + 1) }
            ?: throw IllegalArgumentException("Impossible d'ouvrir ce fichier.")
        if (bytes.size > MAX_BYTES) throw IllegalArgumentException("Ce fichier est trop gros pour être un terrain.")
        return Airfield.decode(bytes.toString(Charsets.UTF_8))
    }

    fun write(context: Context, uri: Uri, config: AirfieldConfig) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(Airfield.encode(config).toByteArray()) }
            ?: throw IllegalStateException("Impossible d'écrire ce fichier.")
    }

    /** Android share sheet (messages, mail, Drive…) with the airfield as a JSON attachment. */
    fun share(context: Context, config: AirfieldConfig) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName(config))
        file.writeText(Airfield.encode(config))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Terrain PicPiste ${config.icao}")
            putExtra(Intent.EXTRA_TEXT, "Terrain ${config.icao} (${config.name}) pour PicPiste : ouvrir le fichier avec PicPiste.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Partager le terrain ${config.icao}"))
    }
}
