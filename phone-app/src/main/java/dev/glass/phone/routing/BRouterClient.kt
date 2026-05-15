package dev.glass.phone.routing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import btools.routingapp.IBRouterService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin client around the BRouter `IBRouterService` AIDL.
 *
 * The BRouter Android app must be installed on the device (sideload from F-Droid). The user must
 * also have downloaded the rd5 segment for the routing area at least once via the BRouter UI.
 *
 * Usage: {@code val gpx = BRouterClient(context).route(start, end)}.
 */
class BRouterClient(private val context: Context) {

    /**
     * Bind to the BRouter service, run a route request, return the GPX string.
     *
     * @throws RoutingException if BRouter is not installed, the bind fails, the call returns an
     *   error, or the routing exceeds {@code timeoutMs}.
     */
    @Throws(RoutingException::class)
    suspend fun route(
        start: LatLng,
        end: LatLng,
        mode: NavigationMode = NavigationMode.CYCLING,
        timeoutMs: Long = 30_000,
    ): String {
        val service = bind() ?: throw RoutingException(
            "BRouter service unavailable. Install BRouter from F-Droid (btools.routingapp).")
        try {
            val params = Bundle().apply {
                putString("lonlats", "${start.lon},${start.lat}|${end.lon},${end.lat}")
                putString("profile", mode.profile)
                putString("v", mode.vehicle)
                putString("trackFormat", "gpx")
                putString("turnInstructionFormat", "osmand")
                putString("timode", "3") // osmand-style
                putString("maxRunningTime", "${timeoutMs / 1000}")
            }
            val result = withTimeout(timeoutMs + 5_000) {
                service.iface.getTrackFromParams(params)
            }
            if (result.isNullOrBlank()) {
                throw RoutingException("BRouter returned empty result")
            }
            if (!result.trimStart().startsWith("<")) {
                throw RoutingException("BRouter error: $result")
            }
            return result
        } finally {
            try { context.unbindService(service.connection) } catch (_: Throwable) {}
        }
    }

    private suspend fun bind(): BoundService? = suspendCancellableCoroutine { cont ->
        val intent = Intent().apply {
            setClassName("btools.routingapp", "btools.routingapp.BRouterService")
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (cont.isActive) {
                    cont.resume(BoundService(IBRouterService.Stub.asInterface(binder), this))
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        try {
            val ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!ok) {
                if (cont.isActive) cont.resume(null)
            }
        } catch (t: Throwable) {
            if (cont.isActive) cont.resumeWithException(t)
        }
        cont.invokeOnCancellation {
            try { context.unbindService(connection) } catch (_: Throwable) {}
        }
    }

    private class BoundService(val iface: IBRouterService, val connection: ServiceConnection)
}

class RoutingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
