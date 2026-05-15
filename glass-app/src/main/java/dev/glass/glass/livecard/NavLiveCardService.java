package dev.glass.glass.livecard;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.widget.RemoteViews;

import com.google.android.glass.timeline.LiveCard;

import dev.glass.glass.R;
import dev.glass.glass.transport.TransportFactory;
import dev.glass.protocol.transport.Transport;

import java.io.IOException;

/**
 * The Glass LiveCard host. On real Glass this publishes to the timeline and the user sees the
 * snippet + turn instruction in the prism. On the API 19 emulator there is no Glass system UI, so
 * {@code LiveCard.publish()} is a no-op (or throws); we wrap it and keep going so packets still
 * flow through the dispatch path and we can verify the byte-level round trip via logcat.
 */
public class NavLiveCardService extends Service {
    private static final String TAG = "NavLiveCardService";
    private static final String LIVECARD_TAG = "dev.glass.glass.nav";

    private LiveCard liveCard;
    private RemoteViews views;
    private Transport transport;
    private PowerManager.WakeLock screenWake;
    private int approachingTurnIndex = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate");
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            // SCREEN_BRIGHT_WAKE_LOCK is deprecated on modern Android but is the standard
            // mechanism on Glass XE (API 19) to bring the display out of dim/off from a
            // non-Activity component. ACQUIRE_CAUSES_WAKEUP forces an immediate wake.
            screenWake = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                TAG + ":turnApproach");
            screenWake.setReferenceCounted(false);
        }
        views = new RemoteViews(getPackageName(), R.layout.livecard_nav);
        try {
            liveCard = new LiveCard(this, LIVECARD_TAG);
            liveCard.setViews(views);
            Intent menu = new Intent(this, MenuActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            // FLAG_IMMUTABLE was added in API 23; we target API 19 so just use FLAG_UPDATE_CURRENT.
            liveCard.setAction(PendingIntent.getActivity(this, 0, menu,
                PendingIntent.FLAG_UPDATE_CURRENT));
            liveCard.publish(LiveCard.PublishMode.REVEAL);
            Log.i(TAG, "LiveCard published");
        } catch (Throwable t) {
            // Expected on the API 19 emulator (no Glass system UI). Log and keep going so the
            // packet pipeline still runs.
            Log.w(TAG, "LiveCard publish failed (likely emulator): " + t.getMessage());
            liveCard = null;
        }
        // Start the transport (TCP server in debug, RFCOMM in release).
        transport = TransportFactory.create();
        transport.setListener(new PacketDispatcher(this));
        try {
            transport.start();
            Log.i(TAG, "transport started");
        } catch (IOException e) {
            Log.w(TAG, "transport.start failed: " + e.getMessage());
        }
    }

    /** Called by {@code PacketDispatcher} when a new snippet/text/distance update arrives. */
    public void updateRemoteViews(byte[] pngBytes, String instruction, String distance) {
        if (views == null) return;
        if (pngBytes != null && pngBytes.length > 0) {
            android.graphics.Bitmap bm = android.graphics.BitmapFactory
                .decodeByteArray(pngBytes, 0, pngBytes.length);
            if (bm != null) views.setImageViewBitmap(R.id.snippet, bm);
        }
        if (instruction != null) views.setTextViewText(R.id.instruction, instruction);
        if (distance != null) views.setTextViewText(R.id.distance, distance);
        if (liveCard != null) {
            try {
                liveCard.setViews(views);
            } catch (Throwable t) {
                Log.w(TAG, "setViews failed: " + t.getMessage());
            }
        }
    }

    /**
     * Called by {@code PacketDispatcher} when the rider has entered the approach radius for a
     * turn. Wakes the display and brings the LiveCard to the front of the timeline. Idempotent
     * for the same {@code turnIndex} so it can be invoked on every PROGRESS packet.
     */
    public void onApproachingTurn(int turnIndex) {
        if (approachingTurnIndex == turnIndex) return;
        approachingTurnIndex = turnIndex;
        Log.i(TAG, "approaching turn #" + turnIndex + " — waking display");
        if (liveCard != null) {
            try {
                liveCard.navigate();
            } catch (Throwable t) {
                Log.w(TAG, "liveCard.navigate failed: " + t.getMessage());
            }
        }
        if (screenWake != null && !screenWake.isHeld()) {
            try {
                screenWake.acquire();
            } catch (Throwable t) {
                Log.w(TAG, "wakeLock.acquire failed: " + t.getMessage());
            }
        }
    }

    /**
     * Called by {@code PacketDispatcher} when the active turn has been passed (turn index
     * advanced) or the route has ended. Releases the wake lock so the screen can dim on its
     * normal timeout. Idempotent.
     */
    public void onTurnPassed() {
        if (approachingTurnIndex == -1) return;
        Log.i(TAG, "turn #" + approachingTurnIndex + " passed — releasing display");
        approachingTurnIndex = -1;
        if (screenWake != null && screenWake.isHeld()) {
            try {
                screenWake.release();
            } catch (Throwable t) {
                Log.w(TAG, "wakeLock.release failed: " + t.getMessage());
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        if (screenWake != null) {
            try { if (screenWake.isHeld()) screenWake.release(); } catch (Throwable ignored) {}
            screenWake = null;
        }
        approachingTurnIndex = -1;
        if (transport != null) {
            try { transport.stop(); } catch (Throwable ignored) {}
            transport = null;
        }
        if (liveCard != null) {
            try {
                if (liveCard.isPublished()) liveCard.unpublish();
            } catch (Throwable ignored) {}
            liveCard = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
