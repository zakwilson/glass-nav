package dev.glass.phone.ui.preview

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.glass.phone.R
import dev.glass.phone.data.RouteDataPrefetcher
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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
    private var prefetchJob: Job? = null
    private var downloadDialog: DataDownloadDialogFragment? = null
    private val sharedHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
    private val storageSettingsLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // On return, retry the pending route if it's still queued.
            val state = viewModel.route.value
            if (state is RideViewModel.RouteState.NeedsStoragePermission) {
                computeRoute(state.destination, state.origin)
            }
        }

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
                            dismissDownloadDialog()
                        }
                        is RideViewModel.RouteState.Downloading -> {
                            status.text = "Downloading ${state.label}…"
                            startBtn.isEnabled = false
                            showOrUpdateDownloadDialog(state)
                        }
                        is RideViewModel.RouteState.NeedsStoragePermission -> {
                            status.text = "Storage access required"
                            startBtn.isEnabled = false
                            dismissDownloadDialog()
                            showStoragePermissionDialog()
                        }
                        is RideViewModel.RouteState.Ready -> {
                            status.text = "Route ready: ${state.turns.size} turns"
                            startBtn.isEnabled = true
                            dismissDownloadDialog()
                            drawRoute(state.track)
                        }
                        is RideViewModel.RouteState.Failed -> {
                            status.text = state.message
                            startBtn.isEnabled = false
                            dismissDownloadDialog()
                        }
                        else -> { dismissDownloadDialog() }
                    }
                }
            }
        }
    }

    private fun computeRoute(destination: dev.glass.phone.ui.search.Place, origin: LatLng?) {
        viewModel.onComputing()
        prefetchJob?.cancel()
        prefetchJob = viewLifecycleOwner.lifecycleScope.launch {
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

            // Ensure routing + map data are present before invoking BRouter.
            val prefetcher = RouteDataPrefetcher(requireContext().applicationContext, sharedHttp)
            val result = prefetcher.ensure(from, destination.location) { s ->
                viewModel.onDownloading(
                    RideViewModel.RouteState.Downloading(
                        s.label, s.bytesDone, s.bytesTotal, s.fileIndex, s.fileCount,
                    ),
                )
            }
            when (result) {
                is RouteDataPrefetcher.Result.NeedsAllFilesAccess -> {
                    viewModel.onNeedsStoragePermission(destination, from)
                    return@launch
                }
                is RouteDataPrefetcher.Result.Failed -> {
                    viewModel.onFailed("Data download failed: ${result.message}")
                    return@launch
                }
                is RouteDataPrefetcher.Result.Cancelled -> {
                    viewModel.onFailed("Download cancelled.")
                    return@launch
                }
                RouteDataPrefetcher.Result.Ok -> { /* fall through */ }
            }
            viewModel.onComputing()
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

    private fun showOrUpdateDownloadDialog(state: RideViewModel.RouteState.Downloading) {
        val existing = (childFragmentManager.findFragmentByTag(DataDownloadDialogFragment.TAG)
            as? DataDownloadDialogFragment) ?: downloadDialog
        if (existing != null && existing.isAdded) {
            existing.update(state)
            return
        }
        val dlg = DataDownloadDialogFragment().apply {
            onCancel = {
                prefetchJob?.cancel()
                viewModel.onFailed("Download cancelled.")
            }
            update(state)
        }
        downloadDialog = dlg
        dlg.show(childFragmentManager, DataDownloadDialogFragment.TAG)
    }

    private fun dismissDownloadDialog() {
        val dlg = (childFragmentManager.findFragmentByTag(DataDownloadDialogFragment.TAG)
            as? DataDownloadDialogFragment) ?: downloadDialog
        dlg?.dismissAllowingStateLoss()
        downloadDialog = null
    }

    private fun showStoragePermissionDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.data_permission_title)
            .setMessage(R.string.data_permission_message)
            .setPositiveButton(R.string.data_permission_open_settings) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${requireContext().packageName}")
                    }
                    storageSettingsLauncher.launch(intent)
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                viewModel.onFailed("Storage access not granted.")
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        prefetchJob?.cancel()
        prefetchJob = null
        downloadDialog = null
        mapView?.destroyAll()
        rendererLayer = null
        mapView = null
    }
}
