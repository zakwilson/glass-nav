package dev.glass.phone.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import dev.glass.phone.render.MapDataSource
import dev.glass.phone.routing.LatLng
import dev.glass.phone.ui.search.GeocodingClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * Orchestrator: detects which Mapsforge `.map` and BRouter `.rd5` files are missing for a
 * route, requests `MANAGE_EXTERNAL_STORAGE` if needed, then streams each file with progress.
 *
 * BRouter segments must land at `/sdcard/brouter/segments4/` because that's the path BRouter
 * itself reads from. Mapsforge `.map` files land in our app's private maps dir.
 */
class RouteDataPrefetcher(
    private val context: Context,
    okHttp: OkHttpClient,
    private val geocoder: GeocodingClient = GeocodingClient(),
) {

    private val downloader = DataDownloader(okHttp)
    private val regionResolver = MapsforgeRegionResolver(geocoder)

    sealed class Result {
        object Ok : Result()
        object NeedsAllFilesAccess : Result()
        object Cancelled : Result()
        data class Failed(val message: String) : Result()
    }

    data class Status(
        val label: String,
        val bytesDone: Long,
        val bytesTotal: Long,
        val fileIndex: Int,
        val fileCount: Int,
    )

    suspend fun ensure(
        origin: LatLng,
        dest: LatLng,
        onUpdate: (Status) -> Unit,
    ): Result {
        val plan = withContext(Dispatchers.IO) { plan(origin, dest) }

        if (plan.isEmpty()) return Result.Ok

        // BRouter writes need all-files-access on API 30+.
        val needsAllFiles = plan.any { it.kind == FileKind.BROUTER }
        if (needsAllFiles && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) return Result.NeedsAllFilesAccess
        }

        plan.forEachIndexed { index, item ->
            try {
                onUpdate(Status(item.label, 0L, -1L, index + 1, plan.size))
                downloader.download(item.url, item.dest) { read, total ->
                    onUpdate(Status(item.label, read, total, index + 1, plan.size))
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "download failed for ${item.url}", t)
                return Result.Failed("${item.label}: ${t.message ?: "download failed"}")
            }
        }
        return Result.Ok
    }

    private fun plan(origin: LatLng, dest: LatLng): List<Item> {
        val items = mutableListOf<Item>()

        // BRouter tiles
        val segmentsDir = File("/sdcard/brouter/segments4")
        for (tile in BRouterTiles.tilesForRoute(origin, dest)) {
            val f = File(segmentsDir, "$tile.rd5")
            if (!f.exists() || f.length() == 0L) {
                items += Item(
                    kind = FileKind.BROUTER,
                    label = "$tile.rd5",
                    url = BRouterTiles.urlFor(tile),
                    dest = f,
                )
            }
        }

        // Mapsforge region
        val maps = MapDataSource(context)
        if (maps.resolve() == null) {
            val region = regionResolver.resolve(origin)
            if (region != null) {
                val dir = maps.ensureMapsDir()
                val f = File(dir, region.localName)
                if (!f.exists() || f.length() == 0L) {
                    items += Item(
                        kind = FileKind.MAPSFORGE,
                        label = region.localName,
                        url = region.url,
                        dest = f,
                    )
                }
            } else {
                Log.i(TAG, "no Mapsforge region known for $origin — skipping preview map download")
            }
        }
        return items
    }

    private enum class FileKind { BROUTER, MAPSFORGE }
    private data class Item(val kind: FileKind, val label: String, val url: String, val dest: File)

    companion object {
        private const val TAG = "RouteDataPrefetcher"
    }
}
