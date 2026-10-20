package fr.cvvm.picpiste.ui.editor

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.cvvm.picpiste.R
import fr.cvvm.picpiste.core.airfield.NewShape
import fr.cvvm.picpiste.core.airfield.Shape
import fr.cvvm.picpiste.core.airfield.moveRunwayEnd
import fr.cvvm.picpiste.core.airfield.RunwayEnd
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
import fr.cvvm.picpiste.core.i18n.Texts
import fr.cvvm.picpiste.data.MapBackground
import fr.cvvm.picpiste.data.PhoneFix
import fr.cvvm.picpiste.data.TrafficRepository
import fr.cvvm.picpiste.ui.IconKind
import fr.cvvm.picpiste.ui.LineIcon
import fr.cvvm.picpiste.render.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun c(argb: Int) = Color(argb)

/** A GPS fix good enough to record a point: recent and within this accuracy. */
private const val SURVEY_MAX_ACCURACY_M = 15.0
private const val SURVEY_MAX_AGE_MS = 10_000L

/**
 * Airfield screen: correct the airfield in use by dragging points or by driving to them
 * ("Ici" records the phone's GPS position), set its altitude, save it, share it, delete it.
 * Airfields are added from the settings (code or file), or by opening a shared file.
 *
 * @param incoming a file opened with PicPiste from another app, to import.
 */
@Composable
fun AirfieldEditor(
    repo: TrafficRepository,
    incoming: Uri?,
    onIncomingHandled: () -> Unit,
    /** Things to check, shown under the airfield name (after an OpenStreetMap import). */
    initialWarnings: List<String> = emptyList(),
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by repo.airfields.current.collectAsStateWithLifecycle()
    val fix by repo.phoneFix.collectAsStateWithLifecycle()
    val background by repo.prefs.mapBackground.collectAsStateWithLifecycle()
    val state = remember { EditorState(current.config).apply { warnings = initialWarnings } }

    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }
    val airfields by repo.airfields.all.collectAsStateWithLifecycle()

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    fun importUri(uri: Uri) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AirfieldFiles.read(context, uri) } }
            result.onSuccess { state.load(it, listOf(context.getString(R.string.imported_from_file, it.icao))) }
                .onFailure { toast(it.message ?: context.getString(R.string.unreadable_file)) }
        }
    }

    LaunchedEffect(incoming) {
        incoming?.let { importUri(it); onIncomingHandled() }
    }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val config = state.draft.normalized()
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { AirfieldFiles.write(context, uri, config) } }
                .onSuccess { toast(context.getString(R.string.file_saved)) }
                .onFailure { toast(it.message ?: context.getString(R.string.cannot_write_file)) }
        }
    }

    val problems = state.draft.problems()

    fun save() {
        val config = state.draft.normalized()
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { repo.airfields.save(config) } }
                .onSuccess {
                    state.markSaved(config)
                    toast(context.getString(R.string.airfield_saved, config.icao))
                }
                .onFailure { toast(it.message ?: context.getString(R.string.save_failed)) }
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
                RoundButton(IconKind.BACK) { if (state.changed) dialog = EditorDialog.Discard else onClose() }
                Text(stringResource(R.string.airfield), color = c(Palette.TEXT), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                RoundButton(IconKind.UNDO, enabled = state.canUndo) { state.undo() }
                Box {
                    RoundButton(IconKind.MORE) { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_share)) },
                            enabled = problems.isEmpty(),
                            onClick = { menu = false; runCatching { AirfieldFiles.share(context, state.draft.normalized()) }.onFailure { toast(it.message ?: context.getString(R.string.share_failed)) } },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_save_file)) },
                            enabled = problems.isEmpty(),
                            onClick = { menu = false; saveFile.launch(AirfieldFiles.fileName(state.draft)) },
                        )
                        // Only an airfield saved on the phone, and never the last one.
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_delete)) },
                            enabled = airfields.size > 1 && airfields.any { it.icao == state.saved.icao },
                            onClick = { menu = false; dialog = EditorDialog.Delete },
                        )
                    }
                }
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(c(if (state.changed && problems.isEmpty()) Palette.OK else Palette.SURFACE_2))
                        .clickable(enabled = state.changed && problems.isEmpty()) { save() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        stringResource(if (state.changed) R.string.save else R.string.saved),
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
                    stringResource(R.string.airfield_summary, state.draft.elevationM.toInt(), state.draft.runways.size),
                    color = c(Palette.MUTED), fontSize = 13.sp,
                )
                (problems.map { it to Palette.RED_TEXT } + state.warnings.map { it to Palette.CIRCUIT }).forEach { (msg, color) ->
                    Text("• $msg", color = c(color), fontSize = 13.sp)
                }
            }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                EditorMap(state, fix, background, Modifier.fillMaxSize())
                MapBackgroundButton(background, Modifier.align(Alignment.TopEnd)) { repo.prefs.setMapBackground(background.next()) }
                // Points north; tap: north up again. Turn the map with two fingers.
                Compass(state.upDeg, Modifier.align(Alignment.TopStart)) { state.northUp() }
            }

            SelectionPanel(state, fix, onRunway = { dialog = EditorDialog.Runway(it) }, onAdd = { dialog = EditorDialog.Add })
        }

    }

    when (val d = dialog) {
        null -> Unit
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
            title = stringResource(R.string.discard_title),
            text = stringResource(R.string.discard_text),
            confirm = stringResource(R.string.discard),
            onDismiss = { dialog = null },
        ) { dialog = null; onClose() }
        EditorDialog.Delete -> Confirm(
            title = stringResource(R.string.delete_title, state.saved.icao),
            text = stringResource(R.string.delete_text),
            confirm = stringResource(R.string.delete),
            onDismiss = { dialog = null },
        ) {
            dialog = null
            val icao = state.saved.icao
            scope.launch {
                withContext(Dispatchers.IO) { repo.airfields.delete(icao) }
                onClose()
            }
        }
    }
}

private sealed interface EditorDialog {
    data object Info : EditorDialog
    data class Runway(val index: Int) : EditorDialog
    data object Add : EditorDialog
    data object Discard : EditorDialog
    data object Delete : EditorDialog
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
                stringResource(R.string.editor_hint),
                color = c(Palette.MUTED), fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.add_button), onClick = onAdd)
            }
            return@Column
        }

        val runway = (s as? Shape.RunwayOutline)?.index ?: (s as? Shape.Threshold)?.index
        val pts = state.draft.points(s)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.draft.label(s), color = c(Palette.TEXT), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                val sub = when {
                    s is Shape.RunwayOutline -> stringResource(R.string.threshold_ref, state.draft.runways[s.index].ends[state.vertex.coerceIn(0, 1)].ref)
                    pts.size > 1 -> stringResource(R.string.point_n_of, state.vertex + 1, pts.size)
                    else -> null
                }
                sub?.let { Text(it, color = c(Palette.MUTED), fontSize = 13.sp) }
            }
            RoundButton(IconKind.CLOSE) { state.select(null) }
        }

        // Survey: drive to the point, then record the phone's position.
        val age = fix?.let { now - it.timeMs }
        val acc = fix?.accuracyM
        val usable = fix != null && age!! < SURVEY_MAX_AGE_MS && (acc == null || acc <= SURVEY_MAX_ACCURACY_M)
        val gpsLabel = when {
            fix == null || age!! >= SURVEY_MAX_AGE_MS -> stringResource(R.string.gps_none)
            acc != null && acc > SURVEY_MAX_ACCURACY_M -> stringResource(R.string.gps_imprecise, acc.toInt())
            acc != null -> stringResource(R.string.place_here_accuracy, acc.toInt())
            else -> stringResource(R.string.place_here)
        }
        Chip(gpsLabel, enabled = usable, accent = true, modifier = Modifier.fillMaxWidth()) {
            if (s is Shape.RunwayOutline) {
                // Runway: place this threshold, then the other one.
                val end = state.vertex.coerceIn(0, 1)
                state.edit(state.draft.moveRunwayEnd(s.index, RunwayEnd.entries[end], fix!!.geo))
                state.vertex = 1 - end
            } else {
                state.edit(state.draft.movePoint(s, state.vertex, fix!!.geo))
                // Next point, so a survey is: drive, tap, drive, tap…
                if (pts.size > 1) state.vertex = (state.vertex + 1) % pts.size
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (runway != null) Chip(stringResource(R.string.properties)) { onRunway(runway) }
            if (s.kind != Shape.Kind.POINT && s !is Shape.RunwayOutline) {
                Chip(stringResource(R.string.delete_point), enabled = state.draft.canDeletePoint(s)) { state.edit(state.draft.deletePoint(s, state.vertex)) }
            }
            Chip(stringResource(R.string.delete), enabled = state.draft.canDelete(s), danger = true) {
                state.edit(state.draft.delete(s))
                state.select(null)
            }
        }
        Text(
            stringResource(
                when {
                    s is Shape.RunwayOutline -> R.string.hint_runway
                    s.kind == Shape.Kind.POINT -> R.string.hint_point
                    else -> R.string.hint_shape
                },
            ),
            color = c(Palette.FAINT), fontSize = 12.sp,
        )
    }
}

@Composable
private fun RoundButton(icon: IconKind, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(c(Palette.SURFACE_2)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { LineIcon(icon, if (enabled) c(Palette.TEXT) else c(Palette.FAINT)) }
}

/** "✓ Grass" when selected, "Grass" when not. */
@Composable
private fun ticked(on: Boolean, label: String) = if (on) stringResource(R.string.checked, label) else label

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
        modifier.height(48.dp).clip(RoundedCornerShape(12.dp)).background(c(bg)).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = c(fg), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun InfoDialog(state: EditorState, onDismiss: () -> Unit) {
    val d = state.draft
    var ele by remember { mutableStateOf(d.elevationM.toInt().toString()) }
    val eleValue = ele.toDoubleOrNull()?.takeIf { it in -500.0..5_000.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${d.icao} · ${d.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    ele, { ele = it.filter { ch -> ch.isDigit() || ch == '-' }.take(5) },
                    label = { Text(stringResource(R.string.elevation_label)) }, singleLine = true, isError = eleValue == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(stringResource(R.string.elevation_hint), fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = eleValue != null, onClick = {
                state.edit(d.copy(elevationM = eleValue!!))
                state.warnings = state.warnings.filterNot { it.startsWith(Texts.current.elevationWarningPrefix) }
                onDismiss()
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
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
        title = { Text(stringResource(R.string.runway)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(30) }, label = { Text(stringResource(R.string.runway_name_label)) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(ticked(grass, stringResource(R.string.grass)), accent = grass) { grass = true }
                    Chip(ticked(!grass, stringResource(R.string.paved)), accent = !grass) { grass = false }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(refA, { refA = it.uppercase().take(3) }, label = { Text(stringResource(R.string.runway_number)) }, singleLine = true, isError = !refOk(refA), modifier = Modifier.weight(1f))
                    OutlinedTextField(refB, { refB = it.uppercase().take(3) }, label = { Text(stringResource(R.string.runway_other_end)) }, singleLine = true, isError = !refOk(refB), modifier = Modifier.weight(1f))
                }
                Text(
                    stringResource(R.string.threshold_hint, refA),
                    fontSize = 13.sp,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && refOk(refA) && refOk(refB) && refA != refB, onClick = {
                state.edit(state.draft.renameRunway(index, name.trim()).setRunwaySurface(index, grass).setRunwayRefs(index, refA, refB))
                onDismiss()
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AddDialog(onDismiss: () -> Unit, onAdd: (NewShape, atGps: Boolean) -> Unit) {
    var atGps by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // One above the other: side by side, the dialog is too narrow and the second was cut to "Ma".
                Text(stringResource(R.string.where_to_place), color = c(Palette.MUTED), fontSize = 14.sp)
                Chip(ticked(!atGps, stringResource(R.string.at_map_centre)), Modifier.fillMaxWidth(), accent = !atGps) { atGps = false }
                Chip(ticked(atGps, stringResource(R.string.at_my_position)), Modifier.fillMaxWidth(), accent = atGps) { atGps = true }
                Spacer(Modifier.height(4.dp))
                NewShape.entries.forEach { kind ->
                    Text(
                        kind.label, fontSize = 17.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onAdd(kind, atGps) }.padding(vertical = 14.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Confirm(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Map background switch, over the map: photo → streets → none. */
@Composable
fun MapBackgroundButton(background: MapBackground, modifier: Modifier = Modifier, onClick: () -> Unit) =
    MapPill(stringResource(background.labelRes), modifier, onClick)

/** A small rounded button over a map. */
@Composable
fun MapPill(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.padding(8.dp)
            .clip(RoundedCornerShape(50))
            .background(c(Palette.SURFACE_2).copy(alpha = 0.92f))
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = c(Palette.TEXT), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Compass over a map turned so that [upDeg] (compass direction) is at the top: the red needle
 * points north. Tapping it turns the map back to north up.
 */
@Composable
fun Compass(upDeg: Double, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val red = c(Palette.RED)
    val ink = c(Palette.MUTED)
    Box(
        modifier.padding(8.dp).size(48.dp).clip(CircleShape)
            .background(c(Palette.SURFACE_2).copy(alpha = 0.92f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(30.dp)) {
            rotate(-upDeg.toFloat()) {
                val cx = size.width / 2
                val cy = size.height / 2
                val w = size.width * 0.17f
                val north = Path().apply { moveTo(cx, 0f); lineTo(cx + w, cy); lineTo(cx - w, cy); close() }
                val south = Path().apply { moveTo(cx, size.height); lineTo(cx + w, cy); lineTo(cx - w, cy); close() }
                drawPath(north, red)
                drawPath(south, ink)
            }
        }
    }
}
