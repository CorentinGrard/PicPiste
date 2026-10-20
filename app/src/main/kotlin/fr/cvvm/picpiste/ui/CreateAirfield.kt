package fr.cvvm.picpiste.ui

import androidx.activity.compose.BackHandler
import fr.cvvm.picpiste.core.geo.haversineM
import fr.cvvm.picpiste.core.geo.Vec2
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.cvvm.picpiste.R
import fr.cvvm.picpiste.core.airfield.AirfieldConfig
import fr.cvvm.picpiste.core.airfield.AirfieldDownloader
import fr.cvvm.picpiste.core.airfield.codeFromName
import fr.cvvm.picpiste.core.airfield.latLon
import fr.cvvm.picpiste.core.airfield.newAirfield
import fr.cvvm.picpiste.core.geo.GeoPoint
import fr.cvvm.picpiste.core.i18n.Texts
import fr.cvvm.picpiste.data.TrafficRepository
import fr.cvvm.picpiste.render.Palette
import fr.cvvm.picpiste.ui.editor.EditorMap
import fr.cvvm.picpiste.ui.editor.EditorState
import fr.cvvm.picpiste.ui.editor.MapBackgroundButton
import fr.cvvm.picpiste.ui.editor.centreGeo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Metres across the map when it shows the phone's position. */
private const val GPS_SPAN_M = 1_500.0

/**
 * An airfield without an ICAO code (or missing from OpenStreetMap), built by hand: a name, an
 * optional code, and its place: the phone's position, which the driver can adjust on the photo. It starts with one runway there, which the
 * driver then moves onto the real one in the editor.
 *
 * @param onCreated the airfield is saved and in use; [warnings] go to the editor.
 */
@Composable
fun CreateAirfield(repo: TrafficRepository, onBack: () -> Unit, onCreated: (warnings: List<String>) -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fix by repo.phoneFix.collectAsStateWithLifecycle()
    val background by repo.prefs.mapBackground.collectAsStateWithLifecycle()
    val taken = repo.airfields.all.value.map { it.icao }.toSet()

    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // The new airfield goes where the phone is: the cross starts there and follows the GPS
    // until the driver moves the map. Before any fix: the airfield in use, else all of France.
    fun placeholder(at: GeoPoint) =
        AirfieldConfig(icao = "", name = "", elevationM = 0.0, reference = latLon(at), axisBearingDeg = 0.0, runways = emptyList())
    var phone by remember { mutableStateOf(fix?.geo ?: repo.lastKnownPosition()) }
    val map = remember {
        val (start, span) = phone?.let { it to GPS_SPAN_M }
            ?: repo.airfields.current.value.takeIf { repo.airfields.configured.value }?.let { it.reference to 40_000.0 }
            ?: (GeoPoint(46.6, 2.4) to 1_000_000.0)
        EditorState(placeholder(start)).apply { emptySpanM = span }
    }
    // Where the app last put the cross; the driver moved the map if it is elsewhere now.
    var placed by remember { mutableStateOf(map.centreGeo()) }
    fun centreOn(p: GeoPoint) {
        map.emptySpanM = GPS_SPAN_M
        map.load(placeholder(p), emptyList())
        map.centre = Vec2(0.0, 0.0)
        placed = p
    }
    fun driverMoved() = haversineM(map.centreGeo(), placed) > 20
    fun gpsUpdate(p: GeoPoint) {
        val first = phone == null
        phone = p
        if (first || !driverMoved()) centreOn(p)
    }
    LaunchedEffect(Unit) { repo.currentPosition()?.let(::gpsUpdate) }
    LaunchedEffect(fix) { fix?.geo?.let(::gpsUpdate) }

    fun create() {
        val c = code.ifBlank { codeFromName(name, taken) }
        if (c in taken) {
            error = context.getString(R.string.code_taken, c)
            return
        }
        val at = map.centreGeo()
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val elevation = runCatching { AirfieldDownloader().elevationAt(at.lat, at.lon) }.getOrNull()
                    repo.airfields.save(newAirfield(c, name, at, elevation ?: 0.0))
                    listOf(
                        context.getString(R.string.create_draw_hint),
                        elevation?.let { Texts.current.elevationEstimated(it.roundToInt()) } ?: Texts.current.elevationUnknown,
                    )
                }
            }
            busy = false
            result.onSuccess(onCreated).onFailure { error = it.message ?: context.getString(R.string.save_failed) }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color(Palette.BG)).safeDrawingPadding().imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Color(Palette.SURFACE_2)).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { LineIcon(IconKind.BACK, Color(Palette.TEXT)) }
            Text(stringResource(R.string.create_title), color = Color(Palette.TEXT), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                name, { name = it.take(60); error = null },
                label = { Text(stringResource(R.string.create_name)) },
                singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                code, { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(8); error = null },
                label = { Text(stringResource(R.string.create_code)) },
                placeholder = { if (name.isNotBlank()) Text(codeFromName(name, taken)) },
                singleLine = true, enabled = !busy, isError = error != null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                error ?: stringResource(if (phone == null) R.string.create_waiting_gps else R.string.create_map_hint),
                color = Color(if (error != null) Palette.RED_TEXT else Palette.MUTED), fontSize = 13.sp,
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
            EditorMap(map, fix, background, Modifier.fillMaxSize(), pickOnly = true)
            MapBackgroundButton(background, Modifier.align(Alignment.TopEnd)) { repo.prefs.setMapBackground(background.next()) }
            phone?.let { p ->
                Box(
                    Modifier.align(Alignment.BottomStart).padding(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(Palette.SURFACE_2).copy(alpha = 0.92f))
                        .clickable { centreOn(p) }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.my_position), color = Color(Palette.TEXT), fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        Button(
            onClick = ::create,
            enabled = name.isNotBlank() && !busy,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(Palette.CART), contentColor = Color(Palette.BG),
                disabledContainerColor = if (busy) Color(Palette.CART) else Color(Palette.SURFACE_2),
                disabledContentColor = if (busy) Color(Palette.BG) else Color(Palette.MUTED),
            ),
            modifier = Modifier.fillMaxWidth().padding(12.dp).height(56.dp),
        ) {
            if (busy) CircularProgressIndicator(color = Color(Palette.BG), strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
            else Text(stringResource(R.string.create_here), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}
