package fr.cvvm.picpiste.car

import android.animation.ValueAnimator
import android.graphics.Rect
import android.util.Log
import android.view.Choreographer
import android.view.animation.DecelerateInterpolator
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import fr.cvvm.picpiste.R
import fr.cvvm.picpiste.core.Snapshot
import fr.cvvm.picpiste.render.CarOverlay
import fr.cvvm.picpiste.render.Colors
import fr.cvvm.picpiste.render.RadarRenderer
import fr.cvvm.picpiste.repository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Main car screen. The template only carries the zoom buttons;
 * everything else is drawn on the map surface, as in the design.
 */
class RadarScreen(carContext: CarContext) : Screen(carContext) {
    private val repo = carContext.repository
    private val renderer = RadarRenderer(carContext)
    private val overlay = CarOverlay(carContext)

    private var surface: SurfaceContainer? = null
    private var visible: Rect? = null
    private var last: Snapshot = repo.snapshot.value
    private var job: Job? = null

    /** Radius drawn on screen: follows the setting, gliding to it after a zoom button. */
    private var shownRadiusM = repo.prefs.zoomRadiusM.value
    private var zoomAnim: ValueAnimator? = null
    private var buttonZoom = false

    /** While something flies, redraw at the car's frame rate: aircraft glide between OGN positions. */
    private var started = false
    private var framing = false
    private var lastFrameMs = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framing = false
            if (!started || last.aircraft.none { it.predicted }) return
            val now = System.currentTimeMillis()
            if (now - lastFrameMs >= FRAME_MS) draw()
            scheduleFrame()
        }
    }

    private fun scheduleFrame() {
        if (framing) return
        framing = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

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
                started = true
                job = lifecycleScope.launch {
                    combine(repo.snapshot, repo.prefs.zoomRadiusM) { s, z -> s to z }.collect { (snap, zoom) ->
                        last = snap
                        // Pinch and other changes apply at once; a button press animates.
                        if (!buttonZoom && zoomAnim?.isRunning != true) shownRadiusM = zoom
                        draw()
                        scheduleFrame()
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                started = false
                job?.cancel()
                zoomAnim?.cancel()
            }
        })
    }

    override fun onGetTemplate(): Template {
        // The template needs one action strip: the zoom icons, small, so the map keeps its room.
        // Landings are listed in the side panel, not behind a button.
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_zoom_out))
                    .setOnClickListener { zoomSmoothly(1 / 1.5) }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_zoom_in))
                    .setOnClickListener { zoomSmoothly(1.5) }
                    .build(),
            )
            .build()
        // PAN lets hosts send pinch gestures.
        val mapStrip = ActionStrip.Builder().addAction(Action.PAN).build()
        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setMapActionStrip(mapStrip)
            .build()
    }

    /** Zoom button: changes the setting, then glides the drawn radius to it. */
    private fun zoomSmoothly(factor: Double) {
        val from = shownRadiusM
        // The new setting reaches the collector at once: tell it to leave the drawn radius alone.
        zoomAnim?.cancel()
        buttonZoom = true
        repo.prefs.zoomBy(factor)
        buttonZoom = false
        val to = repo.prefs.zoomRadiusM.value
        if (from == to) return
        zoomAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ZOOM_ANIM_MS
            interpolator = DecelerateInterpolator()
            // Geometric steps, so zooming in and out feel the same speed.
            addUpdateListener { shownRadiusM = from * Math.pow(to / from, it.animatedFraction.toDouble()); draw() }
            start()
        }
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
            renderer.altitudeRef = repo.prefs.altitudeRef.value
            overlay.colors = colors
            canvas.drawColor(colors.BG)
            if (!repo.airfields.configured.value) {
                overlay.drawSetupMessage(canvas, area.left.toFloat(), area.top.toFloat(), area.width().toFloat(), area.height().toFloat(), dp)
                return
            }
            // The host keeps a band across the whole top for its buttons, though they only sit in
            // the top-right corner. Draw under it, with the same margin as at the bottom: the
            // panel gets its full height and the radar circle grows, clear of the buttons.
            val top = (container.height - area.bottom).coerceIn(0, area.top).toFloat()
            val height = area.bottom - top
            val radius = shownRadiusM
            lastFrameMs = System.currentTimeMillis()
            val since = (lastFrameMs - last.timeMs).coerceAtLeast(0)
            if (height > area.width() * 1.2f) {
                // Portrait screen: the map on top, the lists below it across the full width.
                val mapH = minOf(area.width().toFloat(), height * 0.55f)
                renderer.draw(canvas, area.left.toFloat(), top, area.width().toFloat(), mapH, last, radius, dp, null, sinceSnapshotMs = since)
                overlay.drawPanel(canvas, area.left.toFloat(), top + mapH, area.width().toFloat(), height - mapH, last, dp)
                overlay.drawBanner(canvas, area.left.toFloat(), area.top.toFloat(), area.width().toFloat(), last, dp)
                overlay.drawRunwayBorder(canvas, w, h, last, dp)
                return
            }
            val panelW = (area.width() * 0.31f).coerceIn(260 * dp, 420 * dp)
            overlay.drawPanel(canvas, area.left.toFloat(), top, panelW, height, last, dp)
            val mapLeft = area.left + panelW
            val mapW = area.right - mapLeft
            renderer.draw(canvas, mapLeft, top, mapW, height, last, radius, dp, null, sinceSnapshotMs = since)
            // The alert banner stays below the buttons, so its time is never hidden.
            overlay.drawBanner(canvas, mapLeft, area.top.toFloat(), mapW, last, dp)
            overlay.drawRunwayBorder(canvas, w, h, last, dp)
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
    }

    private companion object {
        const val ZOOM_ANIM_MS = 300L
        /** Car screens show 30 frames a second. */
        const val FRAME_MS = 33L
    }
}
