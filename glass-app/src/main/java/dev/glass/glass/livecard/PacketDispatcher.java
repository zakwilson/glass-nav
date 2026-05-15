package dev.glass.glass.livecard;

import android.util.Log;

import dev.glass.protocol.Packet;
import dev.glass.protocol.transport.Transport;

import java.util.HashMap;
import java.util.Map;

/**
 * Receives packets from the {@code Transport} and updates the LiveCard via
 * {@link NavLiveCardService#updateRemoteViews(byte[], String, String)}.
 *
 * Caches {@link Packet.TurnBundle}s by (routeId, turnIndex). When a {@link Packet.Progress}
 * arrives, looks up the corresponding cached bundle and pushes its snippet + instruction +
 * formatted distance to the LiveCard.
 */
public final class PacketDispatcher implements Transport.Listener {
    private static final String TAG = "PacketDispatcher";

    /** Distance at which we consider the rider to be "approaching" a turn (see PLAN.md §MVP-flow). */
    private static final int APPROACH_THRESHOLD_M = 150;

    private final NavLiveCardService service;
    private final Map<Long, Packet.TurnBundle> cache = new HashMap<>();
    private long currentRouteId = -1;
    private int activeTurnIndex = -1;

    public PacketDispatcher(NavLiveCardService service) {
        this.service = service;
    }

    @Override public void onConnected() {
        Log.i(TAG, "connected");
        service.updateRemoteViews(null, null, "");
    }

    @Override public void onPacket(Packet p) {
        if (p instanceof Packet.RouteStart) {
            Packet.RouteStart rs = (Packet.RouteStart) p;
            Log.i(TAG, "ROUTE_START id=" + rs.routeId + " turns=" + rs.totalTurns);
            currentRouteId = rs.routeId;
            cache.clear();
        } else if (p instanceof Packet.TurnBundle) {
            Packet.TurnBundle tb = (Packet.TurnBundle) p;
            Log.d(TAG, "TURN_BUNDLE #" + tb.turnIndex + " " + tb.kind + " (" + tb.pngBytes.length + "B)");
            cache.put(key(tb.routeId, tb.turnIndex), tb);
        } else if (p instanceof Packet.Progress) {
            Packet.Progress pr = (Packet.Progress) p;
            Packet.TurnBundle tb = cache.get(key(pr.routeId, pr.turnIndex));
            if (tb == null) {
                Log.d(TAG, "PROGRESS without cached TURN_BUNDLE for #" + pr.turnIndex);
                return;
            }
            // If the active turn index advanced, the previous turn has been passed — release the
            // display before deciding whether to wake for the new turn.
            if (activeTurnIndex != -1 && pr.turnIndex != activeTurnIndex) {
                service.onTurnPassed();
                activeTurnIndex = -1;
            }
            if (pr.distanceToTurnM <= APPROACH_THRESHOLD_M) {
                service.onApproachingTurn(pr.turnIndex);
                activeTurnIndex = pr.turnIndex;
            }
            String distance = formatDistance(pr.distanceToTurnM);
            String instruction = tb.instructionText;
            Log.i(TAG, "PROGRESS #" + pr.turnIndex + " " + distance);
            service.updateRemoteViews(tb.pngBytes, instruction, distance);
        } else if (p instanceof Packet.RouteEnd) {
            Packet.RouteEnd re = (Packet.RouteEnd) p;
            Log.i(TAG, "ROUTE_END id=" + re.routeId + " " + re.reason);
            service.onTurnPassed();
            activeTurnIndex = -1;
            String message = (re.reason == Packet.RouteEnd.Reason.OFFROUTE) ? "Rerouting…" : "Done";
            service.updateRemoteViews(null, message, "");
            cache.clear();
            currentRouteId = -1;
        }
    }

    @Override public void onDisconnected(Throwable cause) {
        Log.w(TAG, "disconnected: " + (cause == null ? "clean EOF" : cause.getMessage()));
        service.onTurnPassed();
        activeTurnIndex = -1;
        service.updateRemoteViews(null, "Phone disconnected", "");
    }

    private static long key(long routeId, int turnIndex) {
        return (routeId << 32) | (turnIndex & 0xffffffffL);
    }

    private static String formatDistance(int meters) {
        if (meters >= 1000) return (meters / 1000) + "." + ((meters % 1000) / 100) + " km";
        return meters + " m";
    }
}
