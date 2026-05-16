package dev.glass.phone.data

import dev.glass.phone.routing.LatLng
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * BRouter splits the world into 5°×5° `.rd5` segments named by their SW corner. A coordinate at
 * (lat=52.5, lon=13.4) lives in tile `E10_N50`; (lat=-1.0, lon=-1.0) lives in `W5_S5`.
 *
 * Download URL: `https://brouter.de/brouter/segments4/<name>.rd5`.
 */
object BRouterTiles {

    private const val STEP = 5

    fun tileName(lat: Double, lon: Double): String {
        val lonSw = floorTo(lon, STEP)
        val latSw = floorTo(lat, STEP)
        val lonStr = if (lonSw >= 0) "E$lonSw" else "W${-lonSw}"
        val latStr = if (latSw >= 0) "N$latSw" else "S${-latSw}"
        return "${lonStr}_${latStr}"
    }

    /**
     * Every tile whose 5°×5° square intersects the (expanded) bbox of `origin` and `dest`.
     * `marginDeg` widens the bbox to account for the route swinging slightly outside the
     * straight-line bounds.
     */
    fun tilesForRoute(origin: LatLng, dest: LatLng, marginDeg: Double = 0.25): Set<String> {
        val minLat = min(origin.lat, dest.lat) - marginDeg
        val maxLat = max(origin.lat, dest.lat) + marginDeg
        val minLon = min(origin.lon, dest.lon) - marginDeg
        val maxLon = max(origin.lon, dest.lon) + marginDeg

        val latStart = floorTo(minLat, STEP)
        val latEnd = floorTo(maxLat, STEP)
        val lonStart = floorTo(minLon, STEP)
        val lonEnd = floorTo(maxLon, STEP)

        val out = LinkedHashSet<String>()
        var lat = latStart
        while (lat <= latEnd) {
            var lon = lonStart
            while (lon <= lonEnd) {
                // Reuse tileName via a representative point inside the tile.
                out += tileName(lat.toDouble() + 0.5, lon.toDouble() + 0.5)
                lon += STEP
            }
            lat += STEP
        }
        return out
    }

    fun urlFor(name: String): String = "https://brouter.de/brouter/segments4/$name.rd5"

    private fun floorTo(value: Double, step: Int): Int = floor(value / step).toInt() * step
}
