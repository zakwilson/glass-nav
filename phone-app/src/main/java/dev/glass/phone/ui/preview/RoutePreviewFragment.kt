package dev.glass.phone.ui.preview

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import dev.glass.phone.R
import dev.glass.phone.gps.LocationProvider
import dev.glass.phone.render.MapDataSource
import dev.glass.phone.ride.RideService
import dev.glass.phone.routing.BRouterClient
import dev.glass.phone.routing.GpxTurnExtractor
import dev.glass.phone.routing.LatLng
import dev.glass.phone.routing.NavigationMode
import dev.glass.phone.routing.RoutingException
import dev.glass.phone.ui.RideViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mapsforge.core.graphics.Color as MfColor
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes

class RoutePreviewFragment : Fragment(R.layout.fragment_route_preview) {

    private val viewModel: RideViewModel by activityViewModels()
    private var mapView: MapView? = null
    private var rendererLayer: TileRendererLayer? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        AndroidGraphicFactory.createInstance(requireActivity().application)

        val destinationLabel = view.findViewById<TextView>(R.id.destination)
        val container = view.findViewById<FrameLayout>(R.id.map_container)
        val status = view.findViewById<TextView>(R.id.status)
        val backBtn = view.findViewById<MaterialButton>(R.id.back_button)
        val startBtn = view.findViewById<MaterialButton>(R.id.start_button)
        val modeToggle = view.findViewById<MaterialButtonToggleGroup>(R.id.mode_toggle)

        val initialModeId = when (viewModel.mode.value) {
            NavigationMode.CYCLING -> R.id.mode_cycle
            NavigationMode.WALKING -> R.id.mode_walk
            NavigationMode.DRIVING -> R.id.mode_drive
        }
        modeToggle.check(initialModeId)
        modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newMode = when (checkedId) {
                R.id.mode_walk -> NavigationMode.WALKING
                R.id.mode_drive -> NavigationMode.DRIVING
                else -> NavigationMode.CYCLING
            }
            if (newMode == viewModel.mode.value) return@addOnButtonCheckedListener
            viewModel.setMode(newMode)
            // Recompute the current route under the new mode (if we already had a destination).
            val current = viewModel.route.value
            val destination = when (current) {
                is RideViewModel.RouteState.Ready -> current.destination
                is RideViewModel.RouteState.Selected -> current.destination
                else -> null
            }
            val origin = when (current) {
                is RideViewModel.RouteState.Ready -> current.origin
                is RideViewModel.RouteState.Selected -> current.origin
                else -> null
            }
            if (destination != null) computeRoute(destination, origin)
        }

        backBtn.setOnClickListener { viewModel.onRideStopped() }
        startBtn.setOnClickListener {
            val state = viewModel.route.value
            if (state is RideViewModel.RouteState.Ready) {
                RideService.pendingRoute = state
                requireActivity().startService(Intent(requireContext(), RideService::class.java))
                viewModel.onRideStarted()
            }
        }

        // Initial: try to mount the map view if a .map file is present.
        val mapFile = MapDataSource(requireContext()).resolve()
        if (mapFile != null) {
            val mv = MapView(requireContext())
            mv.isClickable = true
            mv.mapScaleBar.isVisible = false
            mv.setBuiltInZoomControls(false)
            container.addView(
                mv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            mapView = mv

            val tileCache = AndroidUtil.createTileCache(
                requireContext(), "route-preview", mv.model.displayModel.tileSize, 1f,
                mv.model.frameBufferModel.overdrawFactor,
            )
            val store: MapDataStore = MapFile(mapFile)
            val layer = TileRendererLayer(
                tileCache, store, mv.model.mapViewPosition,
                AndroidGraphicFactory.INSTANCE,
            )
            layer.setXmlRenderTheme(MapsforgeThemes.OSMARENDER)
            mv.layerManager.layers.add(layer)
            rendererLayer = layer
        } else {
            status.text = "No .map present at ${requireContext().filesDir}/maps/. Pushed Berlin .map?"
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.route.collect { state ->
                    when (state) {
                        is RideViewModel.RouteState.Selected -> {
                            destinationLabel.text = state.destination.displayName
                            startBtn.isEnabled = false
                            status.text = "Tap a result, then 'Compute route'…"
                            // For simplicity: kick off compute immediately.
                            computeRoute(state.destination, state.origin)
                        }
                        is RideViewModel.RouteState.Computing -> {
                            status.text = "Computing route…"
                            startBtn.isEnabled = false
                        }
                        is RideViewModel.RouteState.Ready -> {
                            status.text = "Route ready: ${state.turns.size} turns"
                            startBtn.isEnabled = true
                            drawRoute(state.track)
                        }
                        is RideViewModel.RouteState.Failed -> {
                            status.text = state.message
                            startBtn.isEnabled = false
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun computeRoute(destination: dev.glass.phone.ui.search.Place, origin: LatLng?) {
        viewModel.onComputing()
        viewLifecycleOwner.lifecycleScope.launch {
            val from = origin ?: run {
                val located = withContext(Dispatchers.IO) {
                    LocationProvider(requireContext().applicationContext).getCurrentLocation()
                }
                if (located == null) {
                    viewModel.onFailed(
                        "No GPS fix. Grant location permission, enable GPS, and step outside (or near a window).",
                    )
                    return@launch
                }
                located
            }
            try {
                val mode = viewModel.mode.value
                val gpx = withContext(Dispatchers.IO) {
                    BRouterClient(requireContext().applicationContext).route(from, destination.location, mode)
                }
                val parsed = withContext(Dispatchers.Default) { GpxTurnExtractor().parse(gpx) }
                viewModel.onReady(
                    RideViewModel.RouteState.Ready(
                        origin = from,
                        destination = destination,
                        track = parsed.track,
                        turns = parsed.turns,
                        mode = mode,
                    ),
                )
            } catch (t: RoutingException) {
                Log.w("RoutePreview", "BRouter failed", t)
                viewModel.onFailed(getString(R.string.route_failed, t.message ?: "?"))
            } catch (t: Throwable) {
                Log.w("RoutePreview", "Compute failed", t)
                viewModel.onFailed(getString(R.string.route_failed, t.message ?: "?"))
            }
        }
    }

    private fun drawRoute(track: List<LatLng>) {
        val mv = mapView ?: return
        val paint = AndroidGraphicFactory.INSTANCE.createPaint()
        paint.setColor(Color.RED)
        paint.setStyle(Style.STROKE)
        paint.setStrokeWidth(8f)
        val poly = Polyline(paint, AndroidGraphicFactory.INSTANCE)
        poly.latLongs.addAll(track.map { LatLong(it.lat, it.lon) })
        mv.layerManager.layers.add(poly)
        if (track.isNotEmpty()) {
            val mid = track[track.size / 2]
            mv.setCenter(LatLong(mid.lat, mid.lon))
            mv.setZoomLevel(14)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mapView?.destroyAll()
        rendererLayer = null
        mapView = null
    }
}
