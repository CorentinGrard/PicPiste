package fr.cvvm.picpiste.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.cvvm.picpiste.R
import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.airfield.AirfieldConfig
import fr.cvvm.picpiste.core.airfield.AirfieldDownloader
import fr.cvvm.picpiste.core.airfield.AirfieldImportException
import fr.cvvm.picpiste.core.airfield.OsmImport
import fr.cvvm.picpiste.data.TrafficRepository
import fr.cvvm.picpiste.render.Palette
import fr.cvvm.picpiste.ui.editor.AirfieldFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adds an airfield: on first launch (welcome) or from the settings ([onBack] set).
 * A code already on the phone is just selected; one the app ships (LFNL) is used as is;
 * any other comes from OpenStreetMap, or from a file shared by another driver.
 *
 * @param onReady the airfield is saved and in use; [warnings] lists what to check (OpenStreetMap import).
 */
@Composable
fun Welcome(
    repo: TrafficRepository,
    onBack: (() -> Unit)? = null,
    /** No ICAO code: build it by hand ([CreateAirfield]). */
    onCreate: () -> Unit,
    onReady: (warnings: List<String>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    /** Call from an IO thread; returns [warnings] for [onReady], to call back on the main thread. */
    fun save(config: AirfieldConfig, warnings: List<String>): List<String> {
        repo.airfields.save(config)
        return warnings
    }

    fun search() {
        val c = OsmImport.normalizeCode(code)
        if (c.length < 3 || busy) return
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Already on the phone: keep it (and the driver's corrections).
                    if (repo.airfields.all.value.any { it.icao == c }) {
                        repo.airfields.select(c)
                        return@runCatching null
                    }
                    // Shipped with the app: no need to ask OpenStreetMap.
                    Airfield.bundledConfigOrNull(c)?.let { it to emptyList() }
                        ?: AirfieldDownloader().download(c).let { r -> r.config to r.warnings + context.getString(R.string.osm_check) }
                }.mapCatching { it?.let { (config, warnings) -> save(config, warnings) } ?: emptyList() }
            }
            busy = false
            result.onSuccess(onReady).onFailure { error = (it as? AirfieldImportException)?.message ?: context.getString(R.string.error_detail, it.message ?: "") }
        }
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AirfieldFiles.read(context, uri) }.mapCatching { save(it, emptyList()) }
            }
            result.onSuccess(onReady).onFailure { error = it.message ?: context.getString(R.string.unreadable_file) }
        }
    }

    if (onBack != null) BackHandler(onBack = onBack)

    Column(
        Modifier.fillMaxSize().background(Color(Palette.BG)).safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (onBack != null) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Color(Palette.SURFACE_2)).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { LineIcon(IconKind.BACK, Color(Palette.TEXT)) }
        }
        Text(
            stringResource(if (onBack == null) R.string.welcome_title else R.string.add_airfield_title),
            color = Color(Palette.TEXT), fontSize = 30.sp, fontWeight = FontWeight.Bold,
        )
        Text(stringResource(if (onBack == null) R.string.welcome_text else R.string.add_airfield_text), color = Color(Palette.MUTED), fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            code,
            { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(7); error = null },
            label = { Text(stringResource(R.string.welcome_code_label)) },
            singleLine = true,
            enabled = !busy,
            isError = error != null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = Color(Palette.RED_TEXT), fontSize = 14.sp) }
        Button(
            onClick = ::search,
            enabled = code.length >= 3 && !busy,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(Palette.CART), contentColor = Color(Palette.BG),
                // Stays blue while searching, so the spinner shows.
                disabledContainerColor = if (busy) Color(Palette.CART) else Color(Palette.SURFACE_2),
                disabledContentColor = if (busy) Color(Palette.BG) else Color(Palette.MUTED),
            ),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (busy) CircularProgressIndicator(color = Color(Palette.BG), strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
            else Text(stringResource(R.string.welcome_continue), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        if (busy) {
            Text(stringResource(R.string.searching_hint), color = Color(Palette.MUTED), fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { openFile.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
            enabled = !busy,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(stringResource(R.string.welcome_open_file), color = Color(Palette.TEXT), fontSize = 16.sp) }
        Text(stringResource(R.string.welcome_file_hint), color = Color(Palette.MUTED), fontSize = 13.sp)
        OutlinedButton(
            onClick = onCreate,
            enabled = !busy,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(stringResource(R.string.welcome_create), color = Color(Palette.TEXT), fontSize = 16.sp) }
        Text(stringResource(R.string.welcome_create_hint), color = Color(Palette.MUTED), fontSize = 13.sp)
    }
}
