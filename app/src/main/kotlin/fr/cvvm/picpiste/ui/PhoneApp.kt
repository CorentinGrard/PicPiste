package fr.cvvm.picpiste.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.cvvm.picpiste.BuildConfig
import fr.cvvm.picpiste.R
import fr.cvvm.picpiste.core.Snapshot
import fr.cvvm.picpiste.core.airfield.Airfield
import fr.cvvm.picpiste.core.cart.AlertLevel
import fr.cvvm.picpiste.core.cart.formatEta
import fr.cvvm.picpiste.core.cart.formatKm
import fr.cvvm.picpiste.core.geo.Sun
import fr.cvvm.picpiste.core.i18n.Texts
import fr.cvvm.picpiste.core.traffic.AircraftState
import fr.cvvm.picpiste.core.traffic.FlightStatus
import fr.cvvm.picpiste.data.AltitudeRef
import fr.cvvm.picpiste.data.Prefs
import fr.cvvm.picpiste.data.Theme
import fr.cvvm.picpiste.data.TrafficRepository
import fr.cvvm.picpiste.render.Colors
import fr.cvvm.picpiste.render.Palette
import fr.cvvm.picpiste.render.RadarRenderer
import fr.cvvm.picpiste.ui.editor.AirfieldEditor
import kotlinx.coroutines.delay

private fun c(argb: Int) = Color(argb)

/** Status colour in the phone's colours (the car has its own, see `RadarScreen`). */
private val AircraftState.color: Int get() = Palette.current.of(this)
private val Condensed = FontFamily.SansSerif

/**
 * @param incoming an airfield file opened with PicPiste from another app: opens the airfield editor.
 */
@Composable
fun PhoneApp(repo: TrafficRepository, incoming: Uri? = null, onIncomingHandled: () -> Unit = {}) {
    val theme by repo.prefs.theme.collectAsStateWithLifecycle()
    val airfield by repo.airfields.current.collectAsStateWithLifecycle()
    // Auto theme: light while the sun is up at the airfield, checked every minute.
    val daylight by produceState(isDaylightAt(airfield), airfield) {
        while (true) {
            value = isDaylightAt(airfield)
            delay(60_000)
        }
    }
    val colors = if (theme.isDark(daylight)) Colors.DARK else Colors.LIGHT
    SideEffect { Palette.current = colors }
    val view = LocalView.current
    LaunchedEffect(colors.isDark) {
        (view.context as? Activity)?.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(colors.BG))
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !colors.isDark
                isAppearanceLightNavigationBars = !colors.isDark
            }
        }
    }
    val scheme = if (colors.isDark) {
        darkColorScheme(background = c(colors.BG), surface = c(colors.SURFACE), onBackground = c(colors.TEXT), onSurface = c(colors.TEXT))
    } else {
        lightColorScheme(background = c(colors.BG), surface = c(colors.SURFACE), onBackground = c(colors.TEXT), onSurface = c(colors.TEXT))
    }
    MaterialTheme(colorScheme = scheme) {
        var editing by rememberSaveable { mutableStateOf(false) }
        var adding by rememberSaveable { mutableStateOf(false) }
        var creating by rememberSaveable { mutableStateOf(false) }
        // After the welcome screen imported from OpenStreetMap: what to check, offered once.
        var toCheck by rememberSaveable { mutableStateOf<List<String>?>(null) }
        var editorWarnings by rememberSaveable { mutableStateOf(emptyList<String>()) }
        val configured by repo.airfields.configured.collectAsStateWithLifecycle()
        if (incoming != null) editing = true
        if (editing) {
            AirfieldEditor(repo, incoming, onIncomingHandled, editorWarnings, onClose = { editing = false; editorWarnings = emptyList() })
            return@MaterialTheme
        }
        if (creating) {
            CreateAirfield(repo, onBack = { creating = false }) { warnings ->
                creating = false
                adding = false
                editorWarnings = warnings
                editing = true
            }
            return@MaterialTheme
        }
        if (!configured || adding) {
            Welcome(repo, onBack = if (configured) ({ adding = false }) else null, onCreate = { creating = true }) { warnings ->
                adding = false
                if (warnings.isNotEmpty()) toCheck = warnings
            }
            return@MaterialTheme
        }
        toCheck?.let { warnings ->
            CheckAirfieldDialog(
                warnings,
                onCheck = { toCheck = null; editorWarnings = warnings; editing = true },
                onLater = { toCheck = null },
            )
        }
        val snap by repo.snapshot.collectAsStateWithLifecycle()
        val radius by repo.prefs.zoomRadiusM.collectAsStateWithLifecycle()
        var selected by rememberSaveable { mutableStateOf<String?>(null) }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        // Border only when something is coming: amber (circuit) or red (final, takeoff).
        val border = when (snap.alert.level) {
            AlertLevel.DANGER -> Palette.RED
            AlertLevel.CAUTION -> Palette.CAUTION_BANNER
            else -> null
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(c(Palette.BG))
                .then(if (border != null) Modifier.border(8.dp, c(border)) else Modifier),
        ) {
            val map = @Composable { modifier: Modifier ->
                Box(modifier) {
                    Radar(repo, snap, radius, selected, onZoom = { repo.prefs.zoomBy(it) }) { selected = it }
                    ZoomButton(Prefs.zoomLabel(radius), Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                        repo.prefs.cycleZoom()
                    }
                    if (snap.alert.level == AlertLevel.DANGER || snap.alert.level == AlertLevel.CAUTION) AlertBanner(snap)
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (maxWidth > maxHeight) {
                    // Landscape: as in the car, lists and menu on the left, the map full height on the right.
                    val sideW = (maxWidth * 0.42f).coerceIn(300.dp, 440.dp)
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxHeight().width(sideW)) {
                            TopBar(snap, onSettings = { showSettings = true })
                            Lists(snap, Modifier.weight(1f), landscape = true) { selected = it }
                        }
                        map(Modifier.fillMaxHeight().weight(1f))
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        TopBar(snap, onSettings = { showSettings = true })
                        map(Modifier.fillMaxWidth().weight(1.15f))
                        Lists(snap, Modifier.weight(1f)) { selected = it }
                    }
                }
            }
        }

        selected?.let { addr ->
            val a = snap.aircraft.firstOrNull { it.address == addr }
            if (a == null) selected = null else DetailSheet(a, repo.prefs.altitudeRef.collectAsStateWithLifecycle().value) { selected = null }
        }
        if (showSettings) {
            SettingsDialog(
                repo,
                onEditAirfield = { showSettings = false; editing = true },
                onAddAirfield = { showSettings = false; adding = true },
            ) { showSettings = false }
        }
    }
}

@Composable
private fun TopBar(snap: Snapshot, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("PicPiste", color = c(Palette.TEXT), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        val live = snap.connection.isLive(snap.timeMs)
        val silentMs = snap.connection.silentForMs(snap.timeMs)
        Pill(
            text = when {
                live -> stringResource(R.string.ogn)
                silentMs != null -> stringResource(R.string.ogn_no_data_for, silence(silentMs))
                else -> stringResource(R.string.offline)
            },
            dot = if (live) c(Palette.OK) else c(Palette.RED),
        )
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(c(Palette.SURFACE_2)).clickable(onClick = onSettings),
            contentAlignment = Alignment.Center,
        ) { LineIcon(IconKind.MORE, c(Palette.TEXT)) }
    }
}

@Composable
private fun Pill(text: String, dot: Color? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(c(Palette.SURFACE)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(text, color = c(Palette.MUTED), fontSize = 13.sp)
    }
}

@Composable
private fun Radar(repo: TrafficRepository, snap: Snapshot, radiusM: Double, selected: String?, onZoom: (Double) -> Unit, onSelect: (String?) -> Unit) {
    val context = LocalContext.current
    val renderer = remember { RadarRenderer(context) }
    val altitudeRef by repo.prefs.altitudeRef.collectAsStateWithLifecycle()
    val density = LocalDensity.current.density
    // While something flies, redraw every frame: aircraft glide between OGN positions.
    val moving = snap.aircraft.any { it.predicted }
    var frameMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(moving) {
        while (moving) withFrameMillis { frameMs = System.currentTimeMillis() }
    }
    Canvas(
        Modifier
            .fillMaxSize()
            // Pinch to zoom (the map stays centred on the field, it never pans).
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) onZoom(zoom.toDouble()) }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val hit = renderer.hits.minByOrNull { (it.x - pos.x) * (it.x - pos.x) + (it.y - pos.y) * (it.y - pos.y) }
                    val near = hit?.let { (it.x - pos.x) * (it.x - pos.x) + (it.y - pos.y) * (it.y - pos.y) < (36 * density) * (36 * density) } == true
                    onSelect(if (near) hit.address else null)
                }
            },
    ) {
        drawIntoCanvas { canvas ->
            renderer.colors = Palette.current
            renderer.altitudeRef = altitudeRef
            renderer.draw(
                canvas.nativeCanvas, 0f, 0f, size.width, size.height, snap, radiusM, density, selected,
                sinceSnapshotMs = (frameMs - snap.timeMs).coerceAtLeast(0),
            )
        }
    }
}

@Composable
private fun ZoomButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(52.dp).clip(CircleShape).background(c(Palette.SURFACE_2)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = c(Palette.TEXT), fontSize = 15.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun AlertBanner(snap: Snapshot) {
    val a = snap.alert
    // Red (and voice) when it is close; amber while the glider is still far in the circuit.
    val danger = a.level == AlertLevel.DANGER
    val bg = if (danger) Palette.BANNER else Palette.CAUTION_BANNER
    val fg = c(if (danger) Palette.ON_BANNER else Palette.ON_CAUTION)
    Row(
        Modifier.fillMaxWidth().background(c(bg)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(a.title, color = fg, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(a.detail, color = fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(a.eta, color = fg, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = Condensed)
    }
}

@Composable
private fun CheckAirfieldDialog(warnings: List<String>, onCheck: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.welcome_check_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                warnings.forEach { Text("• $it", fontSize = 15.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onCheck) { Text(stringResource(R.string.welcome_check_now)) } },
        dismissButton = { TextButton(onClick = onLater) { Text(stringResource(R.string.welcome_later)) } },
    )
}

/** Sun above the horizon at the airfield right now. */
fun isDaylightAt(airfield: Airfield, nowMs: Long = System.currentTimeMillis()): Boolean =
    Sun.isDaylight(airfield.reference.lat, airfield.reference.lon, nowMs)

/** "45 s", "3 min": how long the OGN feed has been silent. */
@Composable
private fun silence(ms: Long): String = (ms / 1000).let { s ->
    if (s < 120) stringResource(R.string.seconds_short, s.toInt()) else stringResource(R.string.minutes_short, (s / 60).toInt())
}

@Composable
private fun Lists(snap: Snapshot, modifier: Modifier, landscape: Boolean = false, onSelect: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(if (landscape) RoundedCornerShape(topEnd = 20.dp) else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(c(Palette.SURFACE))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c(Palette.MAP_BG)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                stringResource(R.string.tab_arrivals, snap.arrivals.size),
                stringResource(R.string.tab_landed, snap.landed.size),
            ).forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (tab == i) c(Palette.SURFACE_2) else Color.Transparent)
                        .clickable { tab = i },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = if (tab == i) c(Palette.TEXT) else c(Palette.MUTED), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        val items = if (tab == 0) snap.arrivals else snap.landed
        if (items.isEmpty()) {
            // An empty circuit only means something while OGN data is flowing.
            val live = snap.connection.isLive(snap.timeMs)
            Text(
                stringResource(
                    when {
                        tab == 1 -> R.string.empty_landed
                        live -> R.string.empty_arrivals
                        else -> R.string.no_data_list
                    },
                ),
                color = c(if (tab == 0 && !live) Palette.CIRCUIT else Palette.MUTED),
                fontSize = 15.sp, fontWeight = if (tab == 0 && !live) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(8.dp),
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(items) { a ->
                if (tab == 0) ArrivalRow(a) { onSelect(a.address) } else LandedRow(a, snap.timeMs) { onSelect(a.address) }
            }
        }
        Text(
            stringResource(R.string.disclaimer),
            color = c(Palette.FAINT), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun ArrivalRow(a: AircraftState, onClick: () -> Unit) {
    val final = a.status == FlightStatus.FINAL
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c(Palette.SURFACE_2))
            .then(if (final) Modifier.border(1.5.dp, c(Palette.RED), RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(c(a.color)))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(a.identity.label, color = c(Palette.TEXT), fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = Condensed)
                a.identity.registration?.let { Text(it, color = c(Palette.MUTED), fontSize = 14.sp) }
            }
            Text(a.statusText, color = c(if (final) Palette.RED_TEXT else a.color), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        a.etaS?.let { Text(formatEta(it), color = c(Palette.TEXT), fontSize = if (final) 26.sp else 21.sp, fontWeight = FontWeight.Bold, fontFamily = Condensed) }
    }
}

@Composable
private fun LandedRow(a: AircraftState, now: Long, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c(Palette.SURFACE_2))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(c(Palette.LANDED)))
        Text(a.identity.label, color = c(Palette.TEXT), fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Condensed)
        Text(a.landedText ?: stringResource(R.string.landed_fallback), color = c(Palette.TEXT_2), fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val min = a.landedAtMs?.let { (now - it) / 60_000 } ?: 0
        Text(if (min < 1) stringResource(R.string.just_now) else stringResource(R.string.minutes_short, min.toInt()), color = c(Palette.MUTED), fontSize = 14.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(a: AircraftState, altitudeRef: AltitudeRef, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c(Palette.BG)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(a.identity.label, color = c(Palette.TEXT), fontSize = 44.sp, fontWeight = FontWeight.Bold, fontFamily = Condensed)
                Text(
                    listOfNotNull(a.identity.registration, a.identity.name, a.identity.model, a.identity.type.label.lowercase()).joinToString(" · "),
                    color = c(Palette.MUTED), fontSize = 16.sp,
                )
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c(Palette.SURFACE))
                    .border(1.5.dp, c(a.color), RoundedCornerShape(14.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.detail_status), color = c(Palette.MUTED), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (a.signalLost) stringResource(R.string.signal_lost) else a.statusText, color = c(Palette.TEXT), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                }
                a.etaS?.takeIf { a.status.isArrival }?.let {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(stringResource(R.string.eta), color = c(Palette.MUTED), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(formatEta(it), color = c(Palette.TEXT), fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = Condensed)
                    }
                }
            }
            val f = a.fix
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val altitude = f.altitudeM.toInt()
                val height = f.aglM.coerceAtLeast(0.0).toInt()
                when (altitudeRef) {
                    AltitudeRef.ALTITUDE -> Stat(stringResource(R.string.stat_altitude), "$altitude m", stringResource(R.string.stat_qfe, height), Modifier.weight(1f))
                    AltitudeRef.HEIGHT -> Stat(stringResource(R.string.stat_height), "$height m", stringResource(R.string.stat_qnh, altitude), Modifier.weight(1f))
                }
                Stat(stringResource(R.string.stat_vario), f.climbMs?.let { String.format(Texts.current.locale, "%+.1f m/s", it) } ?: "—", "", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat(stringResource(R.string.stat_speed), "${f.groundSpeedKmh.toInt()} km/h", f.trackDeg?.let { stringResource(R.string.stat_track, it.toInt()) } ?: "", Modifier.weight(1f))
                Stat(stringResource(R.string.stat_distance), formatKm(a.distanceM), stringResource(R.string.stat_from_field), Modifier.weight(1f))
            }
            Text(
                stringResource(R.string.last_contact, (a.ageMs / 1000).toInt(), a.address),
                color = c(Palette.MUTED), fontSize = 13.sp,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, sub: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(c(Palette.SURFACE)).padding(12.dp)) {
        Text(label, color = c(Palette.MUTED), fontSize = 13.sp)
        Text(value, color = c(Palette.TEXT), fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = Condensed)
        if (sub.isNotEmpty()) Text(sub, color = c(Palette.FAINT), fontSize = 12.sp)
    }
}

/** Version, licence and data credits (GPL notice, ODbL and ODC-BY attributions). */
@Composable
private fun About() {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.about_title), color = c(Palette.TEXT), fontSize = 16.sp)
        Text(stringResource(R.string.about_text, BuildConfig.VERSION_NAME), color = c(Palette.MUTED), fontSize = 13.sp)
        Text(stringResource(R.string.credits_text), color = c(Palette.MUTED), fontSize = 13.sp)
        TextButton(onClick = { uri.openUri(SOURCE_URL) }) { Text(stringResource(R.string.about_source)) }
    }
}

private const val SOURCE_URL = "https://github.com/CorentinGrard/PicPiste"

/** "2 d ago", "5 h ago", "just now". */
@Composable
private fun formatAge(ms: Long): String = when {
    ms < 3600_000 -> stringResource(R.string.age_now)
    ms < 24 * 3600_000 -> stringResource(R.string.age_hours, (ms / 3600_000).toInt())
    else -> stringResource(R.string.age_days, (ms / (24 * 3600_000)).toInt())
}

@Composable
private fun SettingsDialog(repo: TrafficRepository, onEditAirfield: () -> Unit, onAddAirfield: () -> Unit, onDismiss: () -> Unit) {
    val airfield by repo.airfields.current.collectAsStateWithLifecycle()
    val airfields by repo.airfields.all.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sound by repo.prefs.sound.collectAsStateWithLifecycle()
    val ceiling by repo.prefs.ceilingAglM.collectAsStateWithLifecycle()
    val altitudeRef by repo.prefs.altitudeRef.collectAsStateWithLifecycle()
    val theme by repo.prefs.theme.collectAsStateWithLifecycle()
    val ddb by repo.ddbStatus.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        title = { Text(stringResource(R.string.settings)) },
        text = {
            // Scrolls: with several airfields the settings are taller than the dialog, and without
            // scrolling the last rows (the ceiling slider) were squeezed on top of each other.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Airfields on this phone: tap one to use it.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.airfields), color = c(Palette.TEXT), fontSize = 16.sp)
                    airfields.forEach { entry ->
                        val inUse = entry.icao == airfield.icao
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (inUse) c(Palette.SURFACE_2) else Color.Transparent)
                                .clickable(enabled = !inUse) { scope.launch(Dispatchers.IO) { repo.airfields.select(entry.icao) } }
                                .padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${entry.icao} · ${entry.name}",
                                color = c(if (inUse) Palette.TEXT else Palette.MUTED), fontSize = 14.sp,
                                fontWeight = if (inUse) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            if (inUse) TextButton(onClick = onEditAirfield) { Text(stringResource(R.string.edit)) }
                        }
                    }
                    TextButton(onClick = onAddAirfield) { Text(stringResource(R.string.add_airfield)) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.theme), color = c(Palette.TEXT), fontSize = 16.sp)
                    Choice(Theme.entries.map { stringResource(it.labelRes) }, theme.ordinal) { repo.prefs.setTheme(Theme.entries[it]) }
                    Text(
                        stringResource(R.string.theme_hint),
                        color = c(Palette.MUTED), fontSize = 13.sp,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.altitude_ref), color = c(Palette.TEXT), fontSize = 16.sp)
                    Choice(AltitudeRef.entries.map { stringResource(it.labelRes) }, altitudeRef.ordinal) { repo.prefs.setAltitudeRef(AltitudeRef.entries[it]) }
                    Text(stringResource(R.string.altitude_ref_hint), color = c(Palette.MUTED), fontSize = 13.sp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.ddb_title), color = c(Palette.TEXT), fontSize = 16.sp)
                    val ageMs = System.currentTimeMillis() - ddb.updatedAtMs
                    // No names, or a month without update: worth a look.
                    val warn = ddb.devices == 0 || ageMs > 30L * 24 * 3600_000
                    Text(
                        when {
                            ddb.downloading -> stringResource(R.string.ddb_downloading)
                            ddb.devices == 0 -> stringResource(R.string.ddb_none)
                            else -> stringResource(R.string.ddb_status, ddb.devices, formatAge(ageMs))
                        } + if (ddb.failed) "\n" + stringResource(R.string.ddb_failed) else "",
                        color = c(if (warn) Palette.RED_TEXT else Palette.MUTED), fontSize = 13.sp,
                    )
                    TextButton(onClick = { repo.refreshDdbNow() }, enabled = !ddb.downloading) {
                        Text(stringResource(R.string.ddb_update))
                    }
                }
                SettingSwitch(stringResource(R.string.voice_alert), stringResource(R.string.voice_alert_hint), sound) { repo.prefs.setSound(it) }
                Column {
                    Text(stringResource(R.string.ceiling, ceiling), color = c(Palette.TEXT), fontSize = 16.sp)
                    Slider(
                        value = ceiling.toFloat(),
                        onValueChange = { repo.prefs.setCeiling((it / 50).toInt() * 50) },
                        valueRange = 200f..1000f,
                    )
                }
                About()
            }
        },
    )
}

@Composable
private fun Choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c(Palette.MAP_BG)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (i == selected) c(Palette.SURFACE_2) else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (i == selected) c(Palette.TEXT) else c(Palette.MUTED), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c(Palette.TEXT), fontSize = 16.sp)
            Text(sub, color = c(Palette.MUTED), fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
