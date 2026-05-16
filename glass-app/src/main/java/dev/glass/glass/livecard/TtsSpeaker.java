package dev.glass.glass.livecard;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.HashMap;
import java.util.Locale;

import dev.glass.protocol.TurnKind;

/**
 * Wraps {@link TextToSpeech} so the rest of the glass-app can post utterances without thinking
 * about init state or engine availability. Real Glass XE ships the Pico engine; the API 19
 * emulator does not, so init may report ERROR — we log it and degrade to a no-op, matching the
 * defensive pattern around {@code LiveCard.publish()} in {@link NavLiveCardService}.
 */
public final class TtsSpeaker {
    private static final String TAG = "TtsSpeaker";

    public enum Cue { APPROACH, IMMINENT }

    private TextToSpeech tts;
    private volatile boolean ready;

    public TtsSpeaker(Context context) {
        try {
            tts = new TextToSpeech(context.getApplicationContext(), new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) {
                    if (status != TextToSpeech.SUCCESS) {
                        Log.w(TAG, "TTS init failed status=" + status + " — speech disabled");
                        return;
                    }
                    int langResult = tts.setLanguage(Locale.getDefault());
                    if (langResult == TextToSpeech.LANG_MISSING_DATA
                        || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        langResult = tts.setLanguage(Locale.US);
                    }
                    if (langResult == TextToSpeech.LANG_MISSING_DATA
                        || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "no usable TTS locale — speech disabled");
                        return;
                    }
                    ready = true;
                    Log.i(TAG, "TTS ready");
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "TTS construction failed: " + t.getMessage());
            tts = null;
        }
    }

    /**
     * Speak {@code utterance}. If the engine is not yet ready (cold start) or unavailable
     * (emulator), this is a no-op. {@code utteranceId} should be stable per (turn, cue) so a
     * re-fire of the same cue flushes/replaces rather than queues.
     */
    public void speak(String utterance, String utteranceId) {
        if (!ready || tts == null || utterance == null || utterance.isEmpty()) return;
        try {
            // The HashMap KEY_PARAM_UTTERANCE_ID overload is the API 19 form — the bundle-taking
            // overload landed at API 21.
            HashMap<String, String> params = new HashMap<String, String>();
            params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
            int r = tts.speak(utterance, TextToSpeech.QUEUE_FLUSH, params);
            if (r != TextToSpeech.SUCCESS) Log.w(TAG, "speak() returned " + r);
        } catch (Throwable t) {
            Log.w(TAG, "speak failed: " + t.getMessage());
        }
    }

    public void shutdown() {
        if (tts == null) return;
        try {
            tts.stop();
            tts.shutdown();
        } catch (Throwable t) {
            Log.w(TAG, "shutdown failed: " + t.getMessage());
        }
        tts = null;
        ready = false;
    }

    /** Build the utterance string for a given cue. Public-static so PacketDispatcher can call it directly. */
    public static String utteranceFor(Cue cue, TurnKind kind, String instructionText, int distanceM) {
        String phrase = phraseFor(kind, instructionText);
        if (cue == Cue.IMMINENT) {
            return phrase + " now";
        }
        return "In " + distanceM + " meters, " + phrase;
    }

    private static String phraseFor(TurnKind kind, String instructionText) {
        if (kind == null) return safe(instructionText);
        switch (kind) {
            case TL:   return "turn left";
            case TR:   return "turn right";
            case TSLL: return "slight left";
            case TSLR: return "slight right";
            case TSHL: return "sharp left";
            case TSHR: return "sharp right";
            case KL:   return "keep left";
            case KR:   return "keep right";
            case TU:   return "make a U-turn";
            case ARRIVE: return "you have arrived";
            case START:
            default:   return safe(instructionText);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
