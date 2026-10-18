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
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import fr.cvvm.picpiste.core.Snapshot
import fr.cvvm.picpiste.data.Prefs
import fr.cvvm.picpiste.render.CarOverlay
import fr.cvvm.picpiste.render.RadarRenderer
import fr.cvvm.picpiste.repository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Main car screen. The template only carries the action strip (zoom, posés);
 * everything else is drawn on the map surface, as in the design.
 */
class RadarScreen(carContext: CarContext) : Screen(carContext) {
    private val repo = carContext.repository
    private val renderer = RadarRenderer(repo.airfield)
    private val overlay = CarOverlay()

    private var surface: SurfaceContainer? = null
    private var visible: Rect? = null
    private var last: Snapshot = repo.snapshot.value
    private var job: Job? = null

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
                    combine(repo.snapshot, repo.prefs.zoom) { s, _ -> s }.collect {
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
        val radius = Prefs.ZOOM_RADII_M[repo.prefs.zoom.value]
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(Prefs.zoomLabel(radius))
                    .setOnClickListener {
                        repo.prefs.cycleZoom()
                        invalidate()
                    }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setTitle("Posés")
                    .setOnClickListener { screenManager.push(LandedScreen(carContext)) }
                    .build(),
            )
            .build()
        return NavigationTemplate.Builder().setActionStrip(strip).build()
    }

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
            canvas.drawColor(fr.cvvm.picpiste.render.Palette.BG)
            val panelW = (area.width() * 0.31f).coerceIn(260 * dp, 420 * dp)
            overlay.drawPanel(canvas, area.left.toFloat(), area.top.toFloat(), panelW, area.height().toFloat(), last, dp)
            val mapLeft = area.left + panelW
            val mapW = area.right - mapLeft
            val radius = Prefs.ZOOM_RADII_M[repo.prefs.zoom.value]
            renderer.draw(canvas, mapLeft, area.top.toFloat(), mapW, area.height().toFloat(), last, radius, dp)
            overlay.drawBanner(canvas, mapLeft, area.top.toFloat(), mapW, last, dp)
            overlay.drawRunwayBorder(canvas, w, h, last, dp)
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
    }
}
