package dev.glass.phone.ride

import dev.glass.phone.routing.LatLng
import dev.glass.phone.routing.Turn
import dev.glass.phone.routing.cumulativeMeters
import dev.glass.phone.routing.haversineMeters
import dev.glass.protocol.TurnKind
import kotlin.math.max

/**
 * Projects the rider's current GPS fix onto a planned route polyline + turn list, returning the
 * upcoming turn index, the distance to that turn (in meters), and an off-route flag.
 *
 * Off-route: a rolling window of the last [WINDOW_SIZE] fixes is kept; when at least
 * [OFF_ROUTE_TRIGGER] of them have perpendicular distance > [OFF_ROUTE_M], we report off-route.
 * The window tolerates GPS jitter and parallel-road flicker that would otherwise reset a
 * strict consecutive counter and stall the reroute.
 */
class RouteMatcher(
    private val track: List<LatLng>,
    private val turns: List<Turn>,
    private val cumulativeM: DoubleArray = cumulativeMeters(track),
) {

    private val offRouteWindow = ArrayDeque<Boolean>(WINDOW_SIZE)

    data class Match(
        val nextTurnIndex: Int,
        val distanceToTurnM: Int,
        val distanceFromStartM: Int,
        val offRoute: Boolean,
        val perpendicularDistanceM: Double,
    )

    fun match(fix: LatLng): Match {
        require(track.size >= 2 && turns.isNotEmpty()) { "track and turns required" }
        var bestSeg = 0
        var bestFracDist = Double.POSITIVE_INFINITY
        var bestPerp = Double.POSITIVE_INFINITY
        var bestProjAlong = 0.0
        for (i in 0 until track.lastIndex) {
            val a = track[i]
            val b = track[i + 1]
            val (perpM, alongFrac) = projectOnSegment(a, b, fix)
            if (perpM < bestPerp) {
                bestPerp = perpM
                bestSeg = i
                bestFracDist = perpM
                bestProjAlong = alongFrac
            }
        }
        val segStart = cumulativeM[bestSeg]
        val segEnd = cumulativeM[bestSeg + 1]
        val distFromStart = (segStart + (segEnd - segStart) * bestProjAlong).toInt()
        // Skip the synthetic START turn — riders care about the next *maneuver*, not the route's
        // origin. Falls through to ARRIVE on a degenerate start+end-only route.
        val nextTurnIdx = turns.indexOfFirst {
            it.kind != TurnKind.START && it.distanceFromStartM >= distFromStart
        }.let { if (it == -1) turns.lastIndex else it }
        val distToTurn = max(0, turns[nextTurnIdx].distanceFromStartM - distFromStart)
        val isOff = bestPerp > OFF_ROUTE_M
        offRouteWindow.addLast(isOff)
        while (offRouteWindow.size > WINDOW_SIZE) offRouteWindow.removeFirst()
        val reportOff = offRouteWindow.count { it } >= OFF_ROUTE_TRIGGER
        return Match(
            nextTurnIndex = nextTurnIdx,
            distanceToTurnM = distToTurn,
            distanceFromStartM = distFromStart,
            offRoute = reportOff,
            perpendicularDistanceM = bestPerp,
        )
    }

    /** Returns (perpendicular meters, along-segment fraction in [0,1]). */
    private fun projectOnSegment(a: LatLng, b: LatLng, p: LatLng): Pair<Double, Double> {
        val ax = a.lon
        val ay = a.lat
        val bx = b.lon
        val by = b.lat
        val px = p.lon
        val py = p.lat
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return haversineMeters(a, p) to 0.0
        var t = ((px - ax) * dx + (py - ay) * dy) / len2
        t = t.coerceIn(0.0, 1.0)
        val proj = LatLng(ay + t * dy, ax + t * dx)
        return haversineMeters(proj, p) to t
    }

    companion object {
        const val OFF_ROUTE_M = 50.0
        /** Number of recent fixes considered when deciding off-route. ~10s at 1 Hz GPS. */
        const val WINDOW_SIZE = 10
        /** Off-route fixes within the window required to trigger a reroute. */
        const val OFF_ROUTE_TRIGGER = 7
    }
}
