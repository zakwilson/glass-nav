package dev.glass.glass.livecard;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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

    /** Grace period before surfacing a "DISCONNECTED" alert — covers brief BT hiccups during
     *  which the phone-side reconnect with exponential backoff usually recovers. */
    private static final long DISCONNECT_ALERT_DELAY_MS = 10_000L;
    /** Maximum time to hold the screen on after the disconnect alert fires. The LiveCard still
     *  displays "DISCONNECTED" once the screen dims; this just bounds battery drain. */
    private static final long DISCONNECT_WAKE_MS = 60_000L;

    private LiveCard liveCard;
    private RemoteViews views;
    private Transport transport;
    private TtsSpeaker speaker;
    private PowerManager.WakeLock screenWake;
    private PowerManager.WakeLock disconnectWake;
    private int approachingTurnIndex = -1;
    private boolean hasNavContent = false;
    private BroadcastReceiver screenOnReceiver;
    private Handler mainHandler;
    private final Runnable disconnectAlertRunnable = this::showDisconnectAlert;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate");
        mainHandler = new Handler(Looper.getMainLooper());
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            // SCREEN_BRIGHT_WAKE_LOCK is deprecated on modern Android but is the standard
            // mechanism on Glass XE (API 19) to bring the display out of dim/off from a
            // non-Activity component. ACQUIRE_CAUSES_WAKEUP forces an immediate wake.
            screenWake = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                TAG + ":turnApproach");
            screenWake.setReferenceCounted(false);
            disconnectWake = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                TAG + ":disconnected");
            disconnectWake.setReferenceCounted(false);
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
        // Glass's head-wake gesture (and a touchpad tap) turn the screen on, which fires
        // ACTION_SCREEN_ON. When that happens during an active route, surface the map LiveCard
        // so a glance-up brings the user straight to nav instead of the clock/timeline home.
        // ACTION_SCREEN_ON is only delivered to runtime-registered receivers, not manifest ones.
        screenOnReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!hasNavContent || liveCard == null) return;
                try {
                    liveCard.navigate();
                } catch (Throwable t) {
                    Log.w(TAG, "liveCard.navigate on screen-on failed: " + t.getMessage());
                }
            }
        };
        registerReceiver(screenOnReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));

        speaker = new TtsSpeaker(this);

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

    /** Called by {@code PacketDispatcher} to surface a spoken cue through bone conduction. */
    public void speak(String utterance, String utteranceId) {
        if (speaker != null) speaker.speak(utterance, utteranceId);
    }

    /** Called by {@code PacketDispatcher} when a new snippet/text/distance update arrives. */
    public void updateRemoteViews(byte[] pngBytes, String instruction, String distance) {
        if (views == null) return;
        // Reset any oversized "DISCONNECTED" text back to the normal instruction size — see
        // showDisconnectAlert().
        views.setFloat(R.id.instruction, "setTextSize", 22f);
        if (pngBytes != null && pngBytes.length > 0) {
            android.graphics.Bitmap bm = android.graphics.BitmapFactory
                .decodeByteArray(pngBytes, 0, pngBytes.length);
            if (bm != null) views.setImageViewBitmap(R.id.snippet, bm);
            hasNavContent = true;
        }
        if (instruction != null) {
            views.setTextViewText(R.id.instruction, instruction);
            hasNavContent = true;
        }
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
     * Called by {@code PacketDispatcher} when the transport reports a disconnect. We don't fire
     * the alert immediately — the phone reconnects with exponential backoff and a brief BT hiccup
     * is the common case. After a {@value #DISCONNECT_ALERT_DELAY_MS}ms grace period we wake the
     * screen and surface a fullscreen "DISCONNECTED" message until {@link #onTransportConnected}
     * is invoked.
     */
    public void onTransportDisconnected() {
        if (mainHandler == null) return;
        mainHandler.removeCallbacks(disconnectAlertRunnable);
        mainHandler.postDelayed(disconnectAlertRunnable, DISCONNECT_ALERT_DELAY_MS);
    }

    /** Cancel any pending disconnect alert, release the disconnect wake lock, and clear any
     *  "DISCONNECTED" text left over from a previous alert so the user sees an empty card while
     *  fresh nav packets are en route. */
    public void onTransportConnected() {
        if (mainHandler != null) mainHandler.removeCallbacks(disconnectAlertRunnable);
        if (disconnectWake != null && disconnectWake.isHeld()) {
            try { disconnectWake.release(); } catch (Throwable t) {
                Log.w(TAG, "disconnectWake.release failed: " + t.getMessage());
            }
        }
        if (views != null) {
            views.setFloat(R.id.instruction, "setTextSize", 22f);
            views.setTextViewText(R.id.instruction, "");
        }
    }

    private void showDisconnectAlert() {
        Log.w(TAG, "phone still disconnected after grace period — alerting user");
        if (views != null) {
            // Oversize the instruction TextView so DISCONNECTED reads at a glance from the riding
            // position; updateRemoteViews resets it back to 22sp on the next nav update.
            views.setFloat(R.id.instruction, "setTextSize", 48f);
            views.setTextViewText(R.id.instruction, "DISCONNECTED");
            views.setTextViewText(R.id.distance, "");
            views.setImageViewResource(R.id.snippet, android.R.color.black);
            if (liveCard != null) {
                try { liveCard.setViews(views); } catch (Throwable t) {
                    Log.w(TAG, "setViews failed: " + t.getMessage());
                }
                try { liveCard.navigate(); } catch (Throwable t) {
                    Log.w(TAG, "liveCard.navigate failed: " + t.getMessage());
                }
            }
        }
        if (disconnectWake != null && !disconnectWake.isHeld()) {
            try { disconnectWake.acquire(DISCONNECT_WAKE_MS); } catch (Throwable t) {
                Log.w(TAG, "disconnectWake.acquire failed: " + t.getMessage());
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
        if (screenOnReceiver != null) {
            try { unregisterReceiver(screenOnReceiver); } catch (Throwable ignored) {}
            screenOnReceiver = null;
        }
        if (screenWake != null) {
            try { if (screenWake.isHeld()) screenWake.release(); } catch (Throwable ignored) {}
            screenWake = null;
        }
        if (disconnectWake != null) {
            try { if (disconnectWake.isHeld()) disconnectWake.release(); } catch (Throwable ignored) {}
            disconnectWake = null;
        }
        if (mainHandler != null) {
            mainHandler.removeCallbacks(disconnectAlertRunnable);
            mainHandler = null;
        }
        approachingTurnIndex = -1;
        hasNavContent = false;
        if (transport != null) {
            try { transport.stop(); } catch (Throwable ignored) {}
            transport = null;
        }
        if (speaker != null) {
            try { speaker.shutdown(); } catch (Throwable ignored) {}
            speaker = null;
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
