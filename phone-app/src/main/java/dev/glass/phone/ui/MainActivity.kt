package dev.glass.phone.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.glass.phone.R
import dev.glass.phone.ui.preview.RoutePreviewFragment
import dev.glass.phone.ui.ride.RideControlsFragment
import dev.glass.phone.ui.search.SearchFragment
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: RideViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* result ignored — UI surfaces missing-permission state via LocationProvider returning null */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsets()
        requestRuntimePermissions()
        if (savedInstanceState == null) {
            showSearch()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.route.collect { state ->
                    when (state) {
                        is RideViewModel.RouteState.Idle -> showSearch()
                        is RideViewModel.RouteState.Selected,
                        is RideViewModel.RouteState.Computing,
                        is RideViewModel.RouteState.Downloading,
                        is RideViewModel.RouteState.NeedsStoragePermission,
                        is RideViewModel.RouteState.Ready,
                        is RideViewModel.RouteState.Failed -> showPreview()
                        is RideViewModel.RouteState.Active -> showRide()
                    }
                }
            }
        }
    }

    private fun showSearch() = swap(SearchFragment::class.java)
    private fun showPreview() = swap(RoutePreviewFragment::class.java)
    private fun showRide() = swap(RideControlsFragment::class.java)

    private fun swap(target: Class<out androidx.fragment.app.Fragment>) {
        val current = supportFragmentManager.findFragmentById(R.id.nav_host)
        if (current != null && current.javaClass == target) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, target.getDeclaredConstructor().newInstance())
            .commit()
    }

    private fun applySystemBarInsets() {
        val container = findViewById<android.view.View>(R.id.nav_host)
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        for (perm in listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needed += perm
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (perm in listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )) {
                if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                    needed += perm
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }
}
