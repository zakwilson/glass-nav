package dev.glass.phone.ui

import android.content.Context
import android.content.SharedPreferences
import dev.glass.protocol.Packet

/**
 * Per-slot configuration for what's rendered in each text field of the ride UI. Two slots on the
 * phone (`turn_text`, `distance_text`) and two on Glass (`instruction`, `distance`). Each can show
 * any one of the [Packet.DisplayConfig.Field] values. Defaults match the pre-feature behavior:
 * turn instruction on top, distance-to-turn on bottom.
 */
object DisplayPrefs {
    private const val FILE = "display"
    private const val KEY_PHONE_TOP = "phone_top"
    private const val KEY_PHONE_BOTTOM = "phone_bottom"
    private const val KEY_GLASS_TOP = "glass_top"
    private const val KEY_GLASS_BOTTOM = "glass_bottom"

    data class Slots(
        val phoneTop: Packet.DisplayConfig.Field,
        val phoneBottom: Packet.DisplayConfig.Field,
        val glassTop: Packet.DisplayConfig.Field,
        val glassBottom: Packet.DisplayConfig.Field,
    )

    private val DEFAULTS = Slots(
        phoneTop = Packet.DisplayConfig.Field.TURN_INSTRUCTION,
        phoneBottom = Packet.DisplayConfig.Field.DISTANCE_TO_TURN,
        glassTop = Packet.DisplayConfig.Field.TURN_INSTRUCTION,
        glassBottom = Packet.DisplayConfig.Field.DISTANCE_TO_TURN,
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(ctx: Context): Slots {
        val p = prefs(ctx)
        return Slots(
            phoneTop = readField(p, KEY_PHONE_TOP, DEFAULTS.phoneTop),
            phoneBottom = readField(p, KEY_PHONE_BOTTOM, DEFAULTS.phoneBottom),
            glassTop = readField(p, KEY_GLASS_TOP, DEFAULTS.glassTop),
            glassBottom = readField(p, KEY_GLASS_BOTTOM, DEFAULTS.glassBottom),
        )
    }

    fun set(ctx: Context, slots: Slots) {
        prefs(ctx).edit()
            .putString(KEY_PHONE_TOP, slots.phoneTop.name)
            .putString(KEY_PHONE_BOTTOM, slots.phoneBottom.name)
            .putString(KEY_GLASS_TOP, slots.glassTop.name)
            .putString(KEY_GLASS_BOTTOM, slots.glassBottom.name)
            .apply()
    }

    private fun readField(
        p: SharedPreferences,
        key: String,
        default: Packet.DisplayConfig.Field,
    ): Packet.DisplayConfig.Field {
        val raw = p.getString(key, null) ?: return default
        return try {
            Packet.DisplayConfig.Field.valueOf(raw)
        } catch (_: IllegalArgumentException) {
            default
        }
    }

    /** Human-readable label for each field, used in the settings dropdown. */
    fun label(field: Packet.DisplayConfig.Field): String = when (field) {
        Packet.DisplayConfig.Field.TURN_INSTRUCTION -> "Turn instruction"
        Packet.DisplayConfig.Field.DISTANCE_TO_TURN -> "Distance to next turn"
        Packet.DisplayConfig.Field.REMAINING_DISTANCE -> "Remaining distance"
        Packet.DisplayConfig.Field.ETA -> "Time remaining"
        Packet.DisplayConfig.Field.SPEED -> "Current speed"
    }
}
