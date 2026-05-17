package dev.glass.phone.ui.ride

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import dev.glass.phone.R
import dev.glass.phone.ride.RideService
import dev.glass.phone.ui.DisplayPrefs
import dev.glass.protocol.Packet

/**
 * Modal that lets the user pick what each text slot on phone and Glass shows. Persists to
 * [DisplayPrefs] and asks [RideService] to push the Glass-side config over the live transport
 * so the change is visible without restarting the ride.
 */
class DisplaySettingsDialogFragment : DialogFragment() {

    private val fields = Packet.DisplayConfig.Field.values().toList()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_display_settings, null)

        val spinnerPhoneTop = view.findViewById<Spinner>(R.id.spinner_phone_top)
        val spinnerPhoneBottom = view.findViewById<Spinner>(R.id.spinner_phone_bottom)
        val spinnerGlassTop = view.findViewById<Spinner>(R.id.spinner_glass_top)
        val spinnerGlassBottom = view.findViewById<Spinner>(R.id.spinner_glass_bottom)

        val labels = fields.map { DisplayPrefs.label(it) }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerPhoneTop.adapter = adapter
        spinnerPhoneBottom.adapter = adapter
        spinnerGlassTop.adapter = adapter
        spinnerGlassBottom.adapter = adapter

        val current = DisplayPrefs.get(requireContext())
        spinnerPhoneTop.setSelection(fields.indexOf(current.phoneTop))
        spinnerPhoneBottom.setSelection(fields.indexOf(current.phoneBottom))
        spinnerGlassTop.setSelection(fields.indexOf(current.glassTop))
        spinnerGlassBottom.setSelection(fields.indexOf(current.glassBottom))

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.display_settings_title)
            .setView(view)
            .setPositiveButton(R.string.display_save) { _, _ ->
                val updated = DisplayPrefs.Slots(
                    phoneTop = fields[spinnerPhoneTop.selectedItemPosition],
                    phoneBottom = fields[spinnerPhoneBottom.selectedItemPosition],
                    glassTop = fields[spinnerGlassTop.selectedItemPosition],
                    glassBottom = fields[spinnerGlassBottom.selectedItemPosition],
                )
                DisplayPrefs.set(requireContext(), updated)
                RideService.pushDisplayConfig()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }

    companion object {
        const val TAG = "DisplaySettingsDialog"
    }
}
