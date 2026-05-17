package dev.glass.phone.ride

import dev.glass.phone.routing.LatLng
import dev.glass.phone.routing.Turn
import dev.glass.phone.routing.cumulativeMeters
import dev.glass.protocol.TurnKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class RouteMatcherTest {

    private val track = listOf(
        LatLng(52.516275, 13.377704),  // 0 m
        LatLng(52.515900, 13.379100),
        LatLng(52.515500, 13.380500),
        LatLng(52.515150, 13.381500),
        LatLng(52.514800, 13.382500),
        LatLng(52.514450, 13.383750),
        LatLng(52.514100, 13.385000),
    )
    private val cum = cumulativeMeters(track)
    private val turns = listOf(
        Turn(0, track[0].lat, track[0].lon, TurnKind.START, 0, "start"),
        Turn(1, track[2].lat, track[2].lon, TurnKind.TL, cum[2].toInt(), "Turn left"),
        Turn(2, track[4].lat, track[4].lon, TurnKind.TSLR, cum[4].toInt(), "Slight right"),
        Turn(3, track[6].lat, track[6].lon, TurnKind.ARRIVE, cum[6].toInt(), "destination"),
    )
    private val matcher = RouteMatcher(track, turns)

    @Test fun `matches a fix on the polyline`() {
        val m = matcher.match(track[2]) // exactly on a node
        assertThat(m.perpendicularDistanceM).isLessThan(1.0)
        assertThat(m.offRoute).isFalse()
    }

    @Test fun `next turn index advances as we progress`() {
        val m0 = matcher.match(track[0])
        val m1 = matcher.match(track[1])
        val m3 = matcher.match(track[3])
        assertThat(m0.nextTurnIndex).isLessThanOrEqualTo(m1.nextTurnIndex)
        assertThat(m1.nextTurnIndex).isLessThanOrEqualTo(m3.nextTurnIndex)
    }

    @Test fun `distance from start is non-decreasing along the polyline`() {
        val ds = track.map { matcher.match(it).distanceFromStartM }
        for (i in 1 until ds.size) {
            assertThat(ds[i]).isGreaterThanOrEqualTo(ds[i - 1])
        }
    }

    @Test fun `distance to next turn drops to zero at the turn point`() {
        val m = matcher.match(track[2]) // turn 1 lives here
        // distanceToTurn could be the distance to *next* turn, which is turn 1 itself or turn 2.
        // It should be small (distance to nearest forthcoming turn at this node).
        assertThat(m.distanceToTurnM).isLessThan(50)
    }

    @Test fun `off-route triggers once trigger count reached in window`() {
        val far = LatLng(52.520000, 13.400000) // ~1.5 km north-east of the route
        // Below trigger: not yet off-route.
        repeat(RouteMatcher.OFF_ROUTE_TRIGGER - 1) {
            assertThat(matcher.match(far).offRoute).isFalse()
        }
        // Crossing the trigger threshold reports off-route.
        assertThat(matcher.match(far).offRoute).isTrue()
    }

    @Test fun `off-route tolerates a brief on-route flicker`() {
        val far = LatLng(52.520000, 13.400000)
        val onRoute = track[3]
        // Two off-route fixes, one on-route flicker, then more off-route.
        matcher.match(far)
        matcher.match(far)
        assertThat(matcher.match(onRoute).offRoute).isFalse()
        repeat(RouteMatcher.OFF_ROUTE_TRIGGER - 2) { matcher.match(far) }
        // Three off + one on + (TRIGGER-2) off = TRIGGER+1 off in window of 10 — should fire.
        assertThat(matcher.match(far).offRoute).isTrue()
    }

    @Test fun `off-route clears once enough on-route fixes flush the window`() {
        val far = LatLng(52.520000, 13.400000)
        // Trigger off-route.
        repeat(RouteMatcher.OFF_ROUTE_TRIGGER) { matcher.match(far) }
        assertThat(matcher.match(far).offRoute).isTrue()
        // Flush the window with on-route fixes.
        val onRoute = track[3]
        repeat(RouteMatcher.WINDOW_SIZE) { matcher.match(onRoute) }
        assertThat(matcher.match(onRoute).offRoute).isFalse()
    }
}
