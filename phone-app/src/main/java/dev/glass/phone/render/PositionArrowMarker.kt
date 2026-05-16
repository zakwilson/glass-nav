package dev.glass.phone.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.mapsforge.core.graphics.Canvas as MfCanvas
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.Layer

/**
 * Position marker drawn as an upward-pointing triangle rotated by the current bearing.
 *
 * Why a custom Layer (not Marker + bitmap): we want the arrow to rotate smoothly with each
 * bearing update without paying to re-rasterize a bitmap on every fix. The MapView itself is
 * rotated as a View for travel-up mode, so we always draw the arrow at `bearing` in canvas
 * coords — the View rotation then carries the arrow to the correct on-screen orientation.
 * When no bearing is known we draw a directionless dot so the marker still shows position.
 */
class PositionArrowMarker(context: Context) : Layer() {

    private var latLong: LatLong? = null
    private var bearingDeg: Float? = null

    private val density = context.resources.displayMetrics.density
    private val radiusPx = 14f * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3B82F6")
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
    }

    @Synchronized
    fun setLatLong(value: LatLong) {
        latLong = value
    }

    @Synchronized
    fun setBearing(value: Float?) {
        bearingDeg = value
    }

    @Synchronized
    override fun getPosition(): LatLong? = latLong

    @Synchronized
    override fun draw(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
        canvas: MfCanvas,
        topLeftPoint: Point,
        rotation: Rotation,
    ) {
        val pos = latLong ?: return
        val tileSize = displayModel.tileSize
        val mapSize = MercatorProjection.getMapSize(zoomLevel, tileSize)
        val px = (MercatorProjection.longitudeToPixelX(pos.longitude, mapSize) - topLeftPoint.x).toFloat()
        val py = (MercatorProjection.latitudeToPixelY(pos.latitude, mapSize) - topLeftPoint.y).toFloat()

        val androidCanvas: Canvas = AndroidGraphicFactory.getCanvas(canvas) ?: return
        val bearing = bearingDeg

        if (bearing == null) {
            androidCanvas.drawCircle(px, py, radiusPx * 0.6f, fillPaint)
            androidCanvas.drawCircle(px, py, radiusPx * 0.6f, outlinePaint)
            return
        }

        val r = radiusPx
        val path = Path().apply {
            moveTo(px, py - r)
            lineTo(px + r * 0.75f, py + r * 0.65f)
            lineTo(px, py + r * 0.25f)
            lineTo(px - r * 0.75f, py + r * 0.65f)
            close()
        }
        androidCanvas.save()
        androidCanvas.rotate(bearing, px, py)
        androidCanvas.drawPath(path, fillPaint)
        androidCanvas.drawPath(path, outlinePaint)
        androidCanvas.restore()
    }
}
