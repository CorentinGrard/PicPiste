package fr.cvvm.picpiste.car

import android.graphics.Rect
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import fr.cvvm.picpiste.R
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import fr.cvvm.picpiste.core.Snapshot
import fr.cvvm.picpiste.render.CarOverlay
import fr.cvvm.picpiste.render.Colors
import fr.cvvm.picpiste.render.RadarRenderer
import fr.cvvm.picpiste.repository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Main car screen. The template only carries the buttons (posés, zoom);
 * everything else is drawn on the map surface, as in the design.
 */
class RadarScreen(carContext: CarContext) : Screen(carContext) {
    private val repo = carContext.repository
    private val renderer = RadarRenderer()
    private val overlay = CarOverlay()

    private var surface: SurfaceContainer? = null
    private var visible: Rect? = null
    private var last: Snapshot = repo.snapshot.value
    private var job: Job? = null

    /** Glider picked in the "Posés" list, highlighted on the radar for a while. */
    private var selected: String? = null
    private var selectedAtMs = 0L

    private val callback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            surface = container
            draw()
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            visible = visibleArea
            draw()
        }

        override fun onStableAreaChanged(stableArea: Rect) = Unit

        /** Pinch on the car screen (hosts that support it); the map stays centred on the field. */
        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
            if (scaleFactor > 0f) repo.prefs.zoomBy(scaleFactor.toDouble())
        }

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            surface = null
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(callback)
            }

            override fun onStart(owner: LifecycleOwner) {
                job = lifecycleScope.launch {
                    combine(repo.snapshot, repo.prefs.zoomRadiusM) { s, _ -> s }.collect {
                        last = it
                        draw()
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                job?.cancel()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Posés")
                    .setOnClickListener { screenManager.push(LandedScreen(carContext, ::showOnRadar)) }
                    .build(),
            )
            .build()
        // Map buttons: zoom in and out; PAN lets hosts send pinch gestures.
        val mapStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_zoom_in))
                    .setOnClickListener { repo.prefs.zoomBy(1.5) }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_zoom_out))
                    .setOnClickListener { repo.prefs.zoomBy(1 / 1.5) }
                    .build(),
            )
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setMapActionStrip(mapStrip)
            .build()
    }

    private fun showOnRadar(address: String) {
        selected = address
        selectedAtMs = System.currentTimeMillis()
        // Zoom out if the glider would be off screen.
        val a = last.aircraft.firstOrNull { it.address == address } ?: return
        if (a.distanceM > repo.prefs.zoomRadiusM.value * 0.85) repo.prefs.setZoomRadius(a.distanceM * 1.25)
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    /** The car switched between day and night: redraw now, not at the next traffic update. */
    fun onDarkModeChanged() = draw()

    private fun draw() {
        val container = surface ?: return
        val s = container.surface ?: return
        if (!s.isValid) return
        val canvas = try {
            s.lockHardwareCanvas()
        } catch (e: Exception) {
            Log.w("RadarScreen", "Cannot draw", e)
            return
        }
        try {
            val w = container.width.toFloat()
            val h = container.height.toFloat()
            val area = visible ?: Rect(0, 0, container.width, container.height)
            // Sizes are designed for a 1280×720 head unit.
            val dp = (minOf(w / 1280f, h / 720f) * 1.0f).coerceAtLeast(0.6f)
            // The car decides day or night (headlights, time of day), not the phone's theme.
            val colors = if (carContext.isDarkMode) Colors.DARK else Colors.LIGHT
            renderer.colors = colors
            overlay.colors = colors
            canvas.drawColor(colors.BG)
            val panelW = (area.width() * 0.31f).coerceIn(260 * dp, 420 * dp)
            overlay.drawPanel(canvas, area.left.toFloat(), area.top.toFloat(), panelW, area.height().toFloat(), last, dp)
            val mapLeft = area.left + panelW
            val mapW = area.right - mapLeft
            val radius = repo.prefs.zoomRadiusM.value
            if (selected != null && System.currentTimeMillis() - selectedAtMs > SELECTION_MS) selected = null
            renderer.draw(canvas, mapLeft, area.top.toFloat(), mapW, area.height().toFloat(), last, radius, dp, selected)
            overlay.drawBanner(canvas, mapLeft, area.top.toFloat(), mapW, last, dp)
            overlay.drawRunwayBorder(canvas, w, h, last, dp)
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
    }

    private companion object {
        const val SELECTION_MS = 60_000L
    }
}
