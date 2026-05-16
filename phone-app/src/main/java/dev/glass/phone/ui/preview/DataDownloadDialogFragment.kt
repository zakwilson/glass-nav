package dev.glass.phone.ui.preview

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.glass.phone.R
import dev.glass.phone.ui.RideViewModel

/**
 * Shows download progress while [RouteDataPrefetcher][dev.glass.phone.data.RouteDataPrefetcher]
 * runs. The host (RoutePreviewFragment) is responsible for cancelling the prefetcher job when
 * [onCancel] fires.
 *
 * Drives its content from a [RideViewModel.RouteState.Downloading] passed in via [update].
 */
class DataDownloadDialogFragment : DialogFragment() {

    var onCancel: () -> Unit = {}

    private var progressView: ProgressBar? = null
    private var textView: TextView? = null
    private var pending: RideViewModel.RouteState.Downloading? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val inflater = LayoutInflater.from(requireContext())
        val view = inflater.inflate(R.layout.dialog_data_download, null, false)
        progressView = view.findViewById(R.id.download_progress)
        textView = view.findViewById(R.id.download_message)
        pending?.let { renderTo(it) }
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.data_download_title)
            .setView(view)
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .create()
    }

    override fun onDestroyView() {
        progressView = null
        textView = null
        super.onDestroyView()
    }

    fun update(state: RideViewModel.RouteState.Downloading) {
        pending = state
        if (progressView != null && textView != null) renderTo(state)
    }

    private fun renderTo(state: RideViewModel.RouteState.Downloading) {
        val pb = progressView ?: return
        val tv = textView ?: return
        if (state.bytesTotal > 0) {
            pb.isIndeterminate = false
            pb.max = 1000
            pb.progress = ((state.bytesDone.toDouble() / state.bytesTotal) * 1000).toInt().coerceIn(0, 1000)
            tv.text = getString(
                R.string.data_download_progress,
                state.label,
                state.bytesDone / 1_000_000,
                state.bytesTotal / 1_000_000,
                state.fileIndex,
                state.fileCount,
            )
        } else {
            pb.isIndeterminate = true
            tv.text = getString(
                R.string.data_download_progress_unknown,
                state.label,
                state.bytesDone / 1_000_000,
                state.fileIndex,
                state.fileCount,
            )
        }
    }

    companion object {
        const val TAG = "DataDownloadDialog"
    }
}
