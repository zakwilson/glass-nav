package dev.glass.phone.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import dev.glass.phone.routing.LatLng
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.layer.cache.InMemoryTileCache
import org.mapsforge.map.layer.renderer.DatabaseRenderer
import org.mapsforge.map.layer.renderer.RendererJob
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.floor

/**
 * Off-screen Mapsforge renderer producing PNG snippets sized for the Glass prism (640×360).
 *
 * Lifecycle: construct once with the Application + .map file. Subsequent {@link #render} calls are
 * thread-safe but not concurrent (synchronized). Recommended pattern: create on a background
 * coroutine in RideService.onCreate, dispatch render() calls from the same dispatcher.
 *
 * Threading: {@link AndroidGraphicFactory#createInstance(Application)} must be called before any
 * Mapsforge object is created. The {@link RenderThemeFuture} must run on a background thread.
 */
class SnippetRenderer(
    application: Application,
    mapFile: File,
    private val width: Int = WIDTH,
    private val height: Int = HEIGHT,
) {

    private val mapDataStore = MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_FIRST).also {
        it.addMapDataStore(MapFile(mapFile), false, false)
    }
    private val displayModel = DisplayModel().apply {
        setFixedTileSize(TILE_SIZE)
        setUserScaleFactor(1f)
    }
    private val tileCache = InMemoryTileCache(TILE_CACHE_CAPACITY)
    private val themeFuture: RenderThemeFuture
    private val renderer: DatabaseRenderer

    init {
        if (!ANDROID_FACTORY_INITIALIZED) {
            AndroidGraphicFactory.createInstance(application)
            ANDROID_FACTORY_INITIALIZED = true
        }
        themeFuture = RenderThemeFuture(
            AndroidGraphicFactory.INSTANCE,
            MapsforgeThemes.OSMARENDER,
            displayModel,
        )
        Thread(themeFuture, "SnippetRenderer-theme").also { it.isDaemon = true }.start()
        renderer = DatabaseRenderer(
            mapDataStore,
            AndroidGraphicFactory.INSTANCE,
            tileCache,
            null,
            true,
            true,
            null,
        )
    }

    /**
     * Render a {@code WIDTH x HEIGHT} PNG centered on {@code center} at the given zoom, with an
     * optional arrow overlay rotated by {@code arrowRotationDeg} (degrees clockwise from north).
     *
     * @return PNG bytes; never empty (always at least the bitmap header).
     */
    @Synchronized
    @Throws(IOException::class)
    fun render(
        center: LatLng,
        zoom: Byte = DEFAULT_ZOOM,
        arrowRotationDeg: Float? = null,
        track: List<LatLng> = emptyList(),
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.parseColor("#1A1A1A"))
            paintTilesInto(canvas, center, zoom)
            if (track.isNotEmpty()) {
                drawTrack(canvas, center, zoom, track)
            }
            if (arrowRotationDeg != null) {
                drawArrow(canvas, arrowRotationDeg)
            } else {
                drawCrosshair(canvas)
            }
            ByteArrayOutputStream(64 * 1024).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IOException("bitmap.compress returned false")
                }
                return out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun paintTilesInto(canvas: Canvas, center: LatLng, zoom: Byte) {
        val tileSize = displayModel.tileSize
        val mapSize = MercatorProjection.getMapSize(zoom, tileSize)
        val cx = MercatorProjection.longitudeToPixelX(center.lon, mapSize)
        val cy = MercatorProjection.latitudeToPixelY(center.lat, mapSize)
        val originX = cx - width / 2.0
        val originY = cy - height / 2.0

        val txMin = floor(originX / tileSize).toInt().coerceAtLeast(0)
        val tyMin = floor(originY / tileSize).toInt().coerceAtLeast(0)
        val txMax = floor((originX + width - 1) / tileSize).toInt()
        val tyMax = floor((originY + height - 1) / tileSize).toInt()

        for (tx in txMin..txMax) {
            for (ty in tyMin..tyMax) {
                val tile = Tile(tx, ty, zoom, tileSize)
                val job = RendererJob(
                    tile, mapDataStore, themeFuture, displayModel,
                    /* textScale = */ 1f, /* hasAlpha = */ false, /* labelsOnly = */ false,
                )
                val tileBitmap = renderer.executeJob(job)
                if (tileBitmap != null) {
                    val drawX = (tx.toDouble() * tileSize - originX).toFloat()
                    val drawY = (ty.toDouble() * tileSize - originY).toFloat()
                    val androidBitmap = AndroidGraphicFactory.getBitmap(tileBitmap)
                    if (androidBitmap != null) {
                        canvas.drawBitmap(androidBitmap, drawX, drawY, null)
                    }
                    tileBitmap.decrementRefCount()
                }
            }
        }
    }

    private fun drawTrack(canvas: Canvas, center: LatLng, zoom: Byte, track: List<LatLng>) {
        val tileSize = displayModel.tileSize
        val mapSize = MercatorProjection.getMapSize(zoom, tileSize)
        val cx = MercatorProjection.longitudeToPixelX(center.lon, mapSize)
        val cy = MercatorProjection.latitudeToPixelY(center.lat, mapSize)
        val originX = cx - width / 2.0
        val originY = cy - height / 2.0

        val path = Path()
        var moved = false
        for (p in track) {
            val px = (MercatorProjection.longitudeToPixelX(p.lon, mapSize) - originX).toFloat()
            val py = (MercatorProjection.latitudeToPixelY(p.lat, mapSize) - originY).toFloat()
            if (!moved) {
                path.moveTo(px, py)
                moved = true
            } else {
                path.lineTo(px, py)
            }
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.parseColor("#80000000")
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.parseColor("#3B82F6")
        }
        canvas.drawPath(path, outline)
        canvas.drawPath(path, stroke)
    }

    private fun drawArrow(canvas: Canvas, rotationDeg: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val r = 32f
        val path = Path().apply {
            moveTo(cx, cy - r)
            lineTo(cx + r * 0.7f, cy + r * 0.6f)
            lineTo(cx, cy + r * 0.2f)
            lineTo(cx - r * 0.7f, cy + r * 0.6f)
            close()
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.BLACK
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#FFA94D")
        }
        canvas.save()
        canvas.rotate(rotationDeg, cx, cy)
        canvas.drawPath(path, outline)
        canvas.drawPath(path, fill)
        canvas.restore()
    }

    private fun drawCrosshair(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFA94D")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(cx, cy, 14f, paint)
        canvas.drawLine(cx - 24f, cy, cx + 24f, cy, paint)
        canvas.drawLine(cx, cy - 24f, cx, cy + 24f, paint)
    }

    /** Release rendering resources. Safe to call multiple times. */
    fun close() {
        try { themeFuture.decrementRefCount() } catch (_: Throwable) {}
        try { tileCache.destroy() } catch (_: Throwable) {}
        try { mapDataStore.close() } catch (_: Throwable) {}
    }

    companion object {
        const val WIDTH = 640
        const val HEIGHT = 360
        const val DEFAULT_ZOOM: Byte = 18
        private const val TILE_SIZE = 256
        private const val TILE_CACHE_CAPACITY = 32

        @Volatile private var ANDROID_FACTORY_INITIALIZED = false
    }
}
