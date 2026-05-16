package dev.glass.phone.ride

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.glass.phone.R
import dev.glass.phone.gps.GpsSource
import dev.glass.phone.gps.MockGpsSource
import dev.glass.phone.gps.RealGpsSource
import dev.glass.phone.render.MapDataSource
import dev.glass.phone.render.SnippetRenderer
import dev.glass.phone.routing.BRouterClient
import dev.glass.phone.routing.GpxTurnExtractor
import dev.glass.phone.routing.LatLng
import dev.glass.phone.routing.RoutingException
import dev.glass.phone.routing.Turn
import dev.glass.phone.routing.approachBearingDeg
import dev.glass.phone.routing.glyph
import dev.glass.phone.transport.TransportFactory
import dev.glass.phone.ui.OrientationPrefs
import dev.glass.phone.ui.RideViewModel
import dev.glass.protocol.Packet
import dev.glass.protocol.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service. Owns: route state, transport, snippet renderer, GPS source.
 *
 * Pipeline: gpsSource → routeMatcher → progressEmitter → transport.send.
 * On startup: pre-renders all turn snippets, sends ROUTE_START + N×TURN_BUNDLE,
 * then begins streaming PROGRESS at 1 Hz while moving.
 */
class RideService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var transport: Transport? = null
    private var renderer: SnippetRenderer? = null
    private var pipelineJob: Job? = null
    @Volatile private var currentRoute: RideViewModel.RouteState.Ready? = null
    @Volatile private var currentRouteId: Long = 0L
    @Volatile private var connected: Boolean = false

    interface UiObserver {
        fun onConnectionStateChange(connected: Boolean, status: String)
        fun onTurnUpdate(text: String, distanceM: Int)
        fun onLocationUpdate(location: LatLng, bearingDeg: Float?) {}
        /** Non-null message while reroute is in flight or just completed; null clears it. */
        fun onRerouteStateChange(message: String?) {}
        /** Called after a successful reroute so the phone UI can swap its on-screen polyline. */
        fun onRouteReplaced(route: RideViewModel.RouteState.Ready) {}
    }

    private sealed class StreamOutcome {
        object Arrived : StreamOutcome()
        object SourceEnded : StreamOutcome()
        data class OffRoute(val from: LatLng) : StreamOutcome()
    }

    private class StopCollection(val outcome: StreamOutcome) : RuntimeException()

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundCompat(buildNotification())
        when (val r = TransportFactory.create(applicationContext)) {
            is TransportFactory.CreateResult.Failed -> {
                Log.w(TAG, "transport unavailable: ${r.reason}")
                uiObserver?.onConnectionStateChange(false, r.reason)
            }
            is TransportFactory.CreateResult.Ok -> {
                Log.i(TAG, "transport: ${r.description}")
                uiObserver?.onConnectionStateChange(false, "Connecting via ${r.description}…")
                val t = r.transport
                transport = t
                t.setListener(object : Transport.Listener {
                    override fun onConnected() {
                        Log.i(TAG, "transport connected")
                        connected = true
                        uiObserver?.onConnectionStateChange(true, "Connected: ${r.description}")
                        val route = pendingRoute
                        if (route != null) startPipeline(t, route)
                    }
                    override fun onPacket(p: Packet) {
                        // Phone is the receiver; Glass currently sends no packets. Ping/Pong is
                        // handled inside the Transport (see Keepalive) and is not surfaced here.
                    }
                    override fun onDisconnected(cause: Throwable?) {
                        val msg = cause?.message ?: "clean EOF"
                        Log.w(TAG, "transport disconnected: $msg")
                        connected = false
                        uiObserver?.onConnectionStateChange(false, "Disconnected: $msg")
                        pipelineJob?.cancel()
                        pipelineJob = null
                    }
                })
                try { t.start() } catch (e: Exception) {
                    Log.w(TAG, "transport.start failed", e)
                    uiObserver?.onConnectionStateChange(false, "Start failed: ${e.message ?: "?"}")
                }
            }
        }
    }

    private fun startPipeline(t: Transport, initialRoute: RideViewModel.RouteState.Ready) {
        pipelineJob?.cancel()
        currentRoute = initialRoute
        pipelineJob = scope.launch {
            var route = initialRoute
            var routeId = freshRouteId().also { currentRouteId = it }
            try {
                pushRoute(t, routeId, route)
                while (true) {
                    when (val outcome = streamProgress(t, routeId, route)) {
                        is StreamOutcome.Arrived,
                        is StreamOutcome.SourceEnded -> {
                            t.send(Packet.RouteEnd(routeId, Packet.RouteEnd.Reason.ARRIVED))
                            return@launch
                        }
                        is StreamOutcome.OffRoute -> {
                            uiObserver?.onRerouteStateChange("Off route — recomputing…")
                            val newRoute = try {
                                computeReroute(route, outcome.from)
                            } catch (e: RoutingException) {
                                Log.w(TAG, "reroute failed: ${e.message}")
                                null
                            } catch (e: Exception) {
                                Log.w(TAG, "reroute failed", e)
                                null
                            }
                            if (newRoute == null) {
                                uiObserver?.onRerouteStateChange("Reroute failed — retrying in ${REROUTE_COOLDOWN_MS / 1000}s")
                                delay(REROUTE_COOLDOWN_MS)
                                uiObserver?.onRerouteStateChange(null)
                                // Re-enter streamProgress with the same (stale) route; a fresh
                                // RouteMatcher is constructed inside, so the off-route counter
                                // restarts and we won't immediately re-fire on the same fix.
                                continue
                            }
                            t.send(Packet.RouteEnd(routeId, Packet.RouteEnd.Reason.OFFROUTE))
                            route = newRoute
                            routeId = freshRouteId().also { currentRouteId = it }
                            currentRoute = route
                            pushRoute(t, routeId, route)
                            uiObserver?.onRouteReplaced(route)
                            uiObserver?.onRerouteStateChange(null)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "pipeline failed", e)
            }
        }
    }

    private fun freshRouteId(): Long = System.currentTimeMillis() and 0xffffffffL

    private suspend fun computeReroute(
        current: RideViewModel.RouteState.Ready,
        from: LatLng,
    ): RideViewModel.RouteState.Ready {
        val gpx = withContext(Dispatchers.IO) {
            BRouterClient(applicationContext).route(from, current.destination.location, current.mode)
        }
        val parsed = withContext(Dispatchers.Default) { GpxTurnExtractor().parse(gpx) }
        require(parsed.track.size >= 2 && parsed.turns.isNotEmpty()) {
            "reroute returned degenerate route (track=${parsed.track.size}, turns=${parsed.turns.size})"
        }
        Log.i(TAG, "rerouted: ${parsed.track.size} pts, ${parsed.turns.size} turns")
        return current.copy(origin = from, track = parsed.track, turns = parsed.turns)
    }

    private suspend fun pushRoute(t: Transport, routeId: Long, route: RideViewModel.RouteState.Ready) {
        t.send(Packet.RouteStart(routeId, route.turns.size, route.destination.displayName))
        Log.i(TAG, "sent ROUTE_START id=$routeId turns=${route.turns.size}")
        val r = ensureRenderer() ?: run {
            Log.w(TAG, "no .map file; sending TURN_BUNDLEs without snippets")
            null
        }
        val travelUp = OrientationPrefs.get(applicationContext) == OrientationPrefs.Mode.TRAVEL_UP
        for ((idx, turn) in route.turns.withIndex()) {
            val png = if (r != null) {
                try {
                    val turnLatLng = LatLng(turn.lat, turn.lon)
                    val approach = approachBearingDeg(route.track, turnLatLng)?.toFloat() ?: 0f
                    val mapRotation = if (travelUp) approach else 0f
                    // Arrow points along the direction of travel. When the map itself is
                    // rotated travel-up, the arrow's screen-space rotation is 0 (it's drawn
                    // after the canvas un-rotates).
                    val arrowRotation = if (travelUp) 0f else approach
                    withContext(Dispatchers.Default) {
                        r.render(
                            turnLatLng,
                            track = route.track,
                            mapRotationDeg = mapRotation,
                            arrowRotationDeg = arrowRotation,
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "render failed for turn $idx", e)
                    EMPTY_BYTES
                }
            } else EMPTY_BYTES
            t.send(
                Packet.TurnBundle(
                    routeId, idx, turn.kind,
                    turn.distanceFromStartM,
                    turn.instruction,
                    png,
                ),
            )
            Log.d(TAG, "sent TURN_BUNDLE #$idx (${turn.kind}, ${png.size}B)")
        }
    }

    private suspend fun streamProgress(
        t: Transport,
        routeId: Long,
        route: RideViewModel.RouteState.Ready,
    ): StreamOutcome {
        val source = currentGpsSource(route)
        val matcher = RouteMatcher(route.track, route.turns)
        var lastTurnIdx = -1
        try {
            source.fixes().collect { fix ->
                val match = matcher.match(fix.location)
                uiObserver?.onLocationUpdate(fix.location, fix.bearingDeg)
                if (match.offRoute) {
                    Log.i(TAG, "off-route by ${match.perpendicularDistanceM.toInt()}m — reroute")
                    throw StopCollection(StreamOutcome.OffRoute(fix.location))
                }
                val speedKmh = ((fix.speedMps ?: 0f) * 3.6f).toInt().coerceIn(0, 0xffff)
                t.send(
                    Packet.Progress(
                        routeId,
                        match.nextTurnIndex,
                        match.distanceToTurnM.coerceAtMost(0xffff),
                        bearingDelta(fix, route.turns.getOrNull(match.nextTurnIndex)),
                        speedKmh,
                    ),
                )
                if (match.nextTurnIndex != lastTurnIdx) lastTurnIdx = match.nextTurnIndex
                val turn = route.turns[match.nextTurnIndex]
                uiObserver?.onTurnUpdate(turn.kind.glyph(), match.distanceToTurnM)
                if (match.distanceToTurnM == 0 && match.nextTurnIndex == route.turns.lastIndex) {
                    Log.i(TAG, "arrived")
                    throw StopCollection(StreamOutcome.Arrived)
                }
            }
        } catch (stop: StopCollection) {
            return stop.outcome
        }
        return StreamOutcome.SourceEnded
    }

    private fun currentGpsSource(route: RideViewModel.RouteState.Ready): GpsSource {
        // Prefer MockGpsSource for the demo / emulator (real GPS requires permission grant + a real signal).
        // Override by installing a mock as MOCK_OVERRIDE before starting.
        return MOCK_OVERRIDE ?: try {
            RealGpsSource(applicationContext)
        } catch (t: Throwable) {
            MockGpsSource(route.track)
        }
    }

    private fun ensureRenderer(): SnippetRenderer? {
        if (renderer != null) return renderer
        val mapFile = MapDataSource(applicationContext).resolve()
            ?: return null
        renderer = SnippetRenderer(application, mapFile)
        return renderer
    }

    private fun bearingDelta(fix: GpsSource.Fix, nextTurn: Turn?): Short {
        if (nextTurn == null || fix.bearingDeg == null) return 0
        val to = bearingFrom(LatLng(fix.location.lat, fix.location.lon), LatLng(nextTurn.lat, nextTurn.lon))
        var diff = to - fix.bearingDeg
        while (diff > 180) diff -= 360
        while (diff < -180) diff += 360
        return (diff * 100).toInt().coerceIn(-32_000, 32_000).toShort()
    }

    private fun bearingFrom(a: LatLng, b: LatLng): Float {
        val φ1 = Math.toRadians(a.lat)
        val φ2 = Math.toRadians(b.lat)
        val Δλ = Math.toRadians(b.lon - a.lon)
        val y = Math.sin(Δλ) * Math.cos(φ2)
        val x = Math.cos(φ1) * Math.sin(φ2) - Math.sin(φ1) * Math.cos(φ2) * Math.cos(Δλ)
        var θ = Math.toDegrees(Math.atan2(y, x))
        if (θ < 0) θ += 360.0
        return θ.toFloat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        instance = null
        scope.cancel()
        try { transport?.stop() } catch (_: Throwable) {}
        try { renderer?.close() } catch (_: Throwable) {}
        transport = null
        renderer = null
        pendingRoute = null
        currentRoute = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.ride_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(ch)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.ride_notification_title))
            .setContentText(getString(R.string.ride_notification_text))
        return builder.build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val TAG = "RideService"
        private const val NOTIFICATION_ID = 0xCAFE
        private const val CHANNEL_ID = "ride"
        private const val REROUTE_COOLDOWN_MS = 30_000L
        private val EMPTY_BYTES = ByteArray(0)

        @Volatile var pendingRoute: RideViewModel.RouteState.Ready? = null
        @Volatile var uiObserver: UiObserver? = null
        @Volatile private var instance: RideService? = null

        /** Test-only override to inject a synthetic GpsSource without setting up Real or Mock from scratch. */
        @Volatile var MOCK_OVERRIDE: GpsSource? = null

        /**
         * Re-render and re-send turn snippets to Glass using the current route and orientation pref.
         * No-op if no ride is active. Used by the UI when the orientation toggle flips mid-ride.
         */
        fun requestSnippetRefresh() {
            val svc = instance ?: return
            val t = svc.transport ?: return
            val route = svc.currentRoute ?: return
            svc.startPipeline(t, route)
        }

        /**
         * Start (or replace) the active route using the already-running service + transport.
         * Returns true if a service instance was present and the request was handled; false if
         * the caller should fall back to startService(). Lets the UI swap routes without paying
         * the cost of a Bluetooth reconnect between rides.
         */
        fun startRoute(route: RideViewModel.RouteState.Ready): Boolean {
            val svc = instance ?: return false
            pendingRoute = route
            val t = svc.transport
            if (t != null && svc.connected) svc.startPipeline(t, route)
            return true
        }

        /**
         * End the active route but keep the service + transport alive so the next route can be
         * pushed immediately without a Bluetooth reconnect.
         */
        fun stopRide() {
            val svc = instance ?: return
            svc.pipelineJob?.cancel()
            svc.pipelineJob = null
            val t = svc.transport
            val route = svc.currentRoute
            if (t != null && svc.connected && route != null && svc.currentRouteId != 0L) {
                try {
                    t.send(Packet.RouteEnd(svc.currentRouteId, Packet.RouteEnd.Reason.CANCELLED))
                } catch (e: Exception) {
                    Log.w(TAG, "stopRide: send RouteEnd failed", e)
                }
            }
            svc.currentRoute = null
            svc.currentRouteId = 0L
            pendingRoute = null
        }
    }
}
