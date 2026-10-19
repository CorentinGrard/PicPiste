package fr.cvvm.picpiste.ui.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import fr.cvvm.picpiste.R
import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.AirfieldConfig
import java.io.File

/** Airfield files shared between drivers: plain JSON, the same as the app keeps for itself. */
object AirfieldFiles {
    private const val MAX_BYTES = 2_000_000

    fun fileName(config: AirfieldConfig) = "PicPiste-${config.icao}.json"

    /** Reads and checks a file the user picked or opened with PicPiste. Throws with a message for the screen. */
    fun read(context: Context, uri: Uri): AirfieldConfig {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_BYTES + 1) }
            ?: throw IllegalArgumentException(context.getString(R.string.cannot_open_file))
        if (bytes.size > MAX_BYTES) throw IllegalArgumentException(context.getString(R.string.file_too_big))
        return Airfield.decode(bytes.toString(Charsets.UTF_8))
    }

    fun write(context: Context, uri: Uri, config: AirfieldConfig) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(Airfield.encode(config).toByteArray()) }
            ?: throw IllegalStateException(context.getString(R.string.cannot_write_file))
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
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_subject, config.icao))
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_text, config.icao, config.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_title, config.icao)))
    }
}
