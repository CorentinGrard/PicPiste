package fr.cvvm.picpiste.ui.editor

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.cvvm.picpiste.core.airfield.AirfieldDownloader
import fr.cvvm.picpiste.core.airfield.AirfieldImportException
import fr.cvvm.picpiste.core.airfield.NewShape
import fr.cvvm.picpiste.core.airfield.Shape
import fr.cvvm.picpiste.core.airfield.add
import fr.cvvm.picpiste.core.airfield.canDelete
import fr.cvvm.picpiste.core.airfield.canDeletePoint
import fr.cvvm.picpiste.core.airfield.delete
import fr.cvvm.picpiste.core.airfield.deletePoint
import fr.cvvm.picpiste.core.airfield.insertPoint
import fr.cvvm.picpiste.core.airfield.label
import fr.cvvm.picpiste.core.airfield.movePoint
import fr.cvvm.picpiste.core.airfield.normalized
import fr.cvvm.picpiste.core.airfield.points
import fr.cvvm.picpiste.core.airfield.problems
import fr.cvvm.picpiste.core.airfield.renameRunway
import fr.cvvm.picpiste.core.airfield.setRunwayRefs
import fr.cvvm.picpiste.core.airfield.setRunwaySurface
import fr.cvvm.picpiste.data.AirfieldStore
import fr.cvvm.picpiste.data.PhoneFix
import fr.cvvm.picpiste.data.TrafficRepository
import fr.cvvm.picpiste.render.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private fun c(argb: Int) = Color(argb)

/** A GPS fix good enough to record a point: recent and within this accuracy. */
private const val SURVEY_MAX_ACCURACY_M = 15.0
private const val SURVEY_MAX_AGE_MS = 10_000L

/**
 * Airfield screen: find an airfield by its code (OpenStreetMap), correct it by dragging points
 * or by driving to them ("Ici" records the phone's GPS position), save it, share it.
 *
 * @param incoming a file opened with PicPiste from another app, to import.
 */
@Composable
fun AirfieldEditor(repo: TrafficRepository, incoming: Uri?, onIncomingHandled: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by repo.airfields.current.collectAsStateWithLifecycle()
    val fix by repo.phoneFix.collectAsStateWithLifecycle()
    val state = remember { EditorState(current.config) }

    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    fun importUri(uri: Uri) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AirfieldFiles.read(context, uri) } }
            result.onSuccess { state.load(it, listOf("Terrain ${it.icao} importé depuis un fichier : vérifiez-le puis enregistrez.")) }
                .onFailure { toast(it.message ?: "Fichier illisible.") }
        }
    }

    LaunchedEffect(incoming) {
        incoming?.let { importUri(it); onIncomingHandled() }
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importUri) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val config = state.draft.normalized()
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { AirfieldFiles.write(context, uri, config) } }
                .onSuccess { toast("Fichier enregistré.") }
                .onFailure { toast(it.message ?: "Impossible d'écrire ce fichier.") }
        }
    }

    val problems = state.draft.problems()

    fun save() {
        val config = state.draft.normalized()
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { repo.airfields.save(config) } }
                .onSuccess {
                    state.markSaved(config)
                    toast("Terrain ${config.icao} enregistré : le radar l'utilise.")
                }
                .onFailure { toast(it.message ?: "Enregistrement impossible.") }
        }
    }

    BackHandler { if (state.changed) dialog = EditorDialog.Discard else onClose() }

    Box(Modifier.fillMaxSize().background(c(Palette.BG))) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // Top bar
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RoundButton("←") { if (state.changed) dialog = EditorDialog.Discard else onClose() }
                Text("Terrain", color = c(Palette.TEXT), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                RoundButton("↶", enabled = state.canUndo) { state.undo() }
                Box {
                    RoundButton("⋯") { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Chercher un terrain par son code") }, onClick = { menu = false; dialog = EditorDialog.Search })
                        DropdownMenuItem(text = { Text("Importer un fichier") }, onClick = { menu = false; openFile.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) })
                        DropdownMenuItem(
                            text = { Text("Partager") },
                            enabled = problems.isEmpty(),
                            onClick = { menu = false; runCatching { AirfieldFiles.share(context, state.draft.normalized()) }.onFailure { toast(it.message ?: "Partage impossible.") } },
                        )
                        DropdownMenuItem(
                            text = { Text("Enregistrer dans un fichier") },
                            enabled = problems.isEmpty(),
                            onClick = { menu = false; saveFile.launch(AirfieldFiles.fileName(state.draft)) },
                        )
                        DropdownMenuItem(text = { Text("Revenir au terrain intégré (${AirfieldStore.BUNDLED})") }, onClick = { menu = false; dialog = EditorDialog.Reset })
                    }
                }
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(c(if (state.changed && problems.isEmpty()) Palette.OK else Palette.SURFACE_2))
                        .clickable(enabled = state.changed && problems.isEmpty()) { save() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        if (state.changed) "Enregistrer" else "Enregistré",
                        color = if (state.changed && problems.isEmpty()) c(Palette.BG) else c(Palette.MUTED),
                        fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Airfield info
            Column(
                Modifier.fillMaxWidth().clickable { dialog = EditorDialog.Info }.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    "${state.draft.icao} · ${state.draft.name}",
                    color = c(Palette.TEXT), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Altitude ${state.draft.elevationM.toInt()} m · ${state.draft.runways.size} piste(s) · toucher pour modifier",
                    color = c(Palette.MUTED), fontSize = 13.sp,
                )
                (problems.map { it to Palette.RED_TEXT } + state.warnings.map { it to Palette.CIRCUIT }).forEach { (msg, color) ->
                    Text("• $msg", color = c(color), fontSize = 13.sp)
                }
            }

            EditorMap(state, fix, Modifier.fillMaxWidth().weight(1f))

            SelectionPanel(state, fix, onRunway = { dialog = EditorDialog.Runway(it) }, onAdd = { dialog = EditorDialog.Add })
        }

        if (busy) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircularProgressIndicator()
                    Text("Recherche dans OpenStreetMap…", color = c(Palette.TEXT), fontSize = 16.sp)
                    Text("Jusqu'à une minute si les serveurs sont chargés.", color = c(Palette.MUTED), fontSize = 13.sp)
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        EditorDialog.Search -> SearchDialog(
            initial = if (state.draft.icao == AirfieldStore.BUNDLED) "" else state.draft.icao,
            onDismiss = { dialog = null },
        ) { code ->
            dialog = null
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { AirfieldDownloader().download(code) } }
                busy = false
                result.onSuccess { state.load(it.config, it.warnings + "Dessin OpenStreetMap : vérifiez les pistes sur place.") }
                    .onFailure { toast((it as? AirfieldImportException)?.message ?: "Erreur : ${it.message}") }
            }
        }
        EditorDialog.Info -> InfoDialog(state, onDismiss = { dialog = null })
        is EditorDialog.Runway -> RunwayDialog(state, d.index, onDismiss = { dialog = null })
        EditorDialog.Add -> AddDialog(onDismiss = { dialog = null }) { kind, atGps ->
            dialog = null
            val at = if (atGps) fix?.geo ?: state.centreGeo() else state.centreGeo()
            val (config, shape) = state.draft.add(kind, at)
            state.edit(config)
            state.select(shape)
        }
        EditorDialog.Discard -> Confirm(
            title = "Quitter sans enregistrer ?",
            text = "Les modifications du terrain seront perdues.",
            confirm = "Quitter",
            onDismiss = { dialog = null },
        ) { dialog = null; onClose() }
        EditorDialog.Reset -> Confirm(
            title = "Revenir à ${AirfieldStore.BUNDLED} ?",
            text = "Le terrain intégré à l'app remplace le terrain enregistré sur ce téléphone.",
            confirm = "Revenir",
            onDismiss = { dialog = null },
        ) {
            dialog = null
            repo.airfields.resetToBundled()
            val bundled = repo.airfields.current.value.config
            state.load(bundled, emptyList())
            state.markSaved(bundled)
        }
    }
}

private sealed interface EditorDialog {
    data object Search : EditorDialog
    data object Info : EditorDialog
    data class Runway(val index: Int) : EditorDialog
    data object Add : EditorDialog
    data object Discard : EditorDialog
    data object Reset : EditorDialog
}

/** Bottom panel: what is selected and what can be done with it. */
@Composable
private fun SelectionPanel(state: EditorState, fix: PhoneFix?, onRunway: (Int) -> Unit, onAdd: () -> Unit) {
    // Re-read the fix age every second, so the GPS button greys out when the fix gets old.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(c(Palette.SURFACE))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val s = state.selected
        if (s == null) {
            Text(
                "Touchez une piste, un hangar, un seuil… pour le modifier. Glissez pour déplacer la carte, pincez pour zoomer.",
                color = c(Palette.MUTED), fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("+ Ajouter", onClick = onAdd)
            }
            return@Column
        }

        val pts = state.draft.points(s)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.draft.label(s), color = c(Palette.TEXT), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (pts.size > 1) Text("Point ${state.vertex + 1} sur ${pts.size}", color = c(Palette.MUTED), fontSize = 13.sp)
            }
            RoundButton("✕") { state.select(null) }
        }

        // Survey: drive to the point, then record the phone's position.
        val age = fix?.let { now - it.timeMs }
        val acc = fix?.accuracyM
        val usable = fix != null && age!! < SURVEY_MAX_AGE_MS && (acc == null || acc <= SURVEY_MAX_ACCURACY_M)
        val gpsLabel = when {
            fix == null || age!! >= SURVEY_MAX_AGE_MS -> "📍 Ici : pas de GPS"
            acc != null && acc > SURVEY_MAX_ACCURACY_M -> "📍 Ici : GPS ±${acc.toInt()} m, trop imprécis"
            acc != null -> "📍 Placer ce point ici (±${acc.toInt()} m)"
            else -> "📍 Placer ce point ici"
        }
        Chip(gpsLabel, enabled = usable, accent = true, modifier = Modifier.fillMaxWidth()) {
            state.edit(state.draft.movePoint(s, state.vertex, fix!!.geo))
            // Next point, so a survey is: drive, tap, drive, tap…
            if (pts.size > 1) state.vertex = (state.vertex + 1) % pts.size
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pts.size > 1) {
                Chip("◀") { state.vertex = (state.vertex - 1 + pts.size) % pts.size }
                Chip("▶") { state.vertex = (state.vertex + 1) % pts.size }
            }
            if (s.kind != Shape.Kind.POINT) {
                Chip("+ point") {
                    val (config, at) = state.draft.insertPoint(s, state.vertex)
                    state.edit(config)
                    state.vertex = at
                }
                Chip("− point", enabled = state.draft.canDeletePoint(s)) { state.edit(state.draft.deletePoint(s, state.vertex)) }
                Chip(if (state.moveWhole) "✓ Tout déplacer" else "Tout déplacer", accent = state.moveWhole) { state.moveWhole = !state.moveWhole }
            }
            val runway = (s as? Shape.RunwayOutline)?.index ?: (s as? Shape.Threshold)?.index
            if (runway != null) Chip("Propriétés") { onRunway(runway) }
            Chip("Supprimer", enabled = state.draft.canDelete(s), danger = true) {
                state.edit(state.draft.delete(s))
                state.select(null)
            }
        }
        Text(
            if (state.moveWhole) "Glissez un point pour déplacer tout l'élément." else "Glissez un point pour le déplacer.",
            color = c(Palette.FAINT), fontSize = 12.sp,
        )
    }
}

@Composable
private fun RoundButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(c(Palette.SURFACE_2)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) c(Palette.TEXT) else c(Palette.FAINT), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Chip(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> Palette.SURFACE_2
        accent -> Palette.CART
        else -> Palette.SURFACE_2
    }
    val fg = when {
        !enabled -> Palette.FAINT
        accent -> Palette.BG
        danger -> Palette.RED_TEXT
        else -> Palette.TEXT
    }
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(c(bg)).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = c(fg), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
private fun SearchDialog(initial: String, onDismiss: () -> Unit, onSearch: (String) -> Unit) {
    var code by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chercher un terrain") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Code OACI du terrain (par ex. LFPT). Le dessin vient d'OpenStreetMap : il remplace le terrain affiché, à vérifier avant d'enregistrer.", fontSize = 14.sp)
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(7) },
                    singleLine = true,
                    label = { Text("Code") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                )
            }
        },
        confirmButton = { TextButton(enabled = code.length >= 3, onClick = { onSearch(code) }) { Text("Chercher") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun InfoDialog(state: EditorState, onDismiss: () -> Unit) {
    val d = state.draft
    var icao by remember { mutableStateOf(d.icao) }
    var name by remember { mutableStateOf(d.name) }
    var ele by remember { mutableStateOf(d.elevationM.toInt().toString()) }
    val eleValue = ele.toDoubleOrNull()?.takeIf { it in -500.0..5_000.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Terrain") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(icao, { icao = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(7) }, label = { Text("Code") }, singleLine = true)
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Nom") }, singleLine = true)
                OutlinedTextField(
                    ele, { ele = it.filter { ch -> ch.isDigit() || ch == '-' }.take(5) },
                    label = { Text("Altitude terrain (m)") }, singleLine = true, isError = eleValue == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text("L'altitude sert à calculer la hauteur des planeurs : vérifiez-la (carte VAC).", fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = icao.length >= 3 && name.isNotBlank() && eleValue != null, onClick = {
                state.edit(d.copy(icao = icao, name = name.trim(), elevationM = eleValue!!))
                state.warnings = state.warnings.filterNot { it.startsWith("Altitude") }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun RunwayDialog(state: EditorState, index: Int, onDismiss: () -> Unit) {
    val rw = state.draft.runways[index]
    var name by remember { mutableStateOf(rw.name) }
    var grass by remember { mutableStateOf(rw.surface == "grass") }
    var refA by remember { mutableStateOf(rw.ends[0].ref) }
    var refB by remember { mutableStateOf(rw.ends[1].ref) }
    val refOk = { r: String -> Regex("^\\d{2}[LCR]?$").matches(r) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Piste") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(30) }, label = { Text("Nom (par ex. herbe, dur, nord)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(if (grass) "✓ Herbe" else "Herbe", accent = grass) { grass = true }
                    Chip(if (!grass) "✓ Revêtue" else "Revêtue", accent = !grass) { grass = false }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(refA, { refA = it.uppercase().take(3) }, label = { Text("Numéro") }, singleLine = true, isError = !refOk(refA), modifier = Modifier.weight(1f))
                    OutlinedTextField(refB, { refB = it.uppercase().take(3) }, label = { Text("Autre bout") }, singleLine = true, isError = !refOk(refB), modifier = Modifier.weight(1f))
                }
                Text(
                    String.format(Locale.FRANCE, "Le seuil « %s » est là où commence la piste %s. Numéros sur deux chiffres, avec L, C ou R pour les pistes parallèles.", refA, refA),
                    fontSize = 13.sp,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && refOk(refA) && refOk(refB) && refA != refB, onClick = {
                state.edit(state.draft.renameRunway(index, name.trim()).setRunwaySurface(index, grass).setRunwayRefs(index, refA, refB))
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun AddDialog(onDismiss: () -> Unit, onAdd: (NewShape, atGps: Boolean) -> Unit) {
    var atGps by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(if (!atGps) "✓ Centre de la carte" else "Centre de la carte", accent = !atGps) { atGps = false }
                    Chip(if (atGps) "✓ Ma position" else "Ma position", accent = atGps) { atGps = true }
                }
                NewShape.entries.forEach { kind ->
                    Text(
                        kind.label, fontSize = 17.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onAdd(kind, atGps) }.padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun Confirm(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
