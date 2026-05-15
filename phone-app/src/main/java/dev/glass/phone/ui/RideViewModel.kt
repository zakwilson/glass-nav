package dev.glass.phone.ui

import androidx.lifecycle.ViewModel
import dev.glass.phone.routing.LatLng
import dev.glass.phone.routing.NavigationMode
import dev.glass.phone.routing.Turn
import dev.glass.phone.ui.search.Place
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cross-fragment state holder for the search → preview → ride flow.
 */
class RideViewModel : ViewModel() {

    sealed class RouteState {
        object Idle : RouteState()
        data class Selected(val destination: Place, val origin: LatLng?) : RouteState()
        object Computing : RouteState()
        data class Ready(
            val origin: LatLng,
            val destination: Place,
            val track: List<LatLng>,
            val turns: List<Turn>,
            val mode: NavigationMode,
        ) : RouteState()
        data class Failed(val message: String) : RouteState()
        data class Active(val ready: Ready) : RouteState()
    }

    private val _route = MutableStateFlow<RouteState>(RouteState.Idle)
    val route: StateFlow<RouteState> = _route.asStateFlow()

    private val _mode = MutableStateFlow(NavigationMode.CYCLING)
    val mode: StateFlow<NavigationMode> = _mode.asStateFlow()

    fun pickDestination(place: Place, origin: LatLng?) {
        _route.value = RouteState.Selected(place, origin)
    }

    fun setMode(mode: NavigationMode) { _mode.value = mode }

    fun onComputing() { _route.value = RouteState.Computing }
    fun onReady(r: RouteState.Ready) { _route.value = r }
    fun onFailed(message: String) { _route.value = RouteState.Failed(message) }
    fun onRideStarted() { (_route.value as? RouteState.Ready)?.let { _route.value = RouteState.Active(it) } }
    fun onRideStopped() { _route.value = RouteState.Idle }
    fun onRouteReplaced(r: RouteState.Ready) { _route.value = RouteState.Active(r) }
}
