package dev.glass.phone.ui.search

import dev.glass.phone.routing.LatLng
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * OSM-based geocoder. Defaults to Komoot's [Photon](https://photon.komoot.io/), which has a
 * sane public API (no API key, no contact-email policy, friendlier rate limits, typo-tolerant).
 *
 * Returns [Place]s suitable for display in the search list and routing as a destination.
 */
class GeocodingClient(
    private val client: OkHttpClient = defaultClient(),
    private val endpoint: String = "https://photon.komoot.io",
    private val userAgent: String = "glass-cycling/0.1",
) {

    private val lastRequestNs = AtomicLong(0L)

    data class ReverseResult(val countryCode: String?, val state: String?)

    /**
     * Reverse-geocode a coordinate. Returns the ISO-3166-1 alpha-2 country code (lowercase, e.g.
     * `"de"`, `"us"`) and the first-order admin region (state/province) name if Photon returned
     * one — e.g. `"California"`. Either field may be null.
     */
    @Throws(IOException::class)
    fun reverse(lat: Double, lon: Double): ReverseResult? {
        rateLimit()
        val url = "$endpoint/reverse/".toHttpUrl().newBuilder().apply {
            addQueryParameter("lat", lat.toString())
            addQueryParameter("lon", lon.toString())
            addQueryParameter("limit", "1")
        }.build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "en")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Photon ${resp.code} ${resp.message}")
            return parseReverse(resp.body?.string().orEmpty())
        }
    }

    /** Parses Photon `/reverse` response → [ReverseResult]. Public for tests. */
    internal fun parseReverse(json: String): ReverseResult? {
        if (json.isBlank()) return null
        val features = JSONObject(json).optJSONArray("features") ?: return null
        if (features.length() == 0) return null
        val props = features.optJSONObject(0)?.optJSONObject("properties") ?: return null
        val cc = props.optString("countrycode").trim().lowercase().takeIf { it.isNotEmpty() }
        val state = props.optString("state").trim().takeIf { it.isNotEmpty() }
        return ReverseResult(cc, state)
    }

    @Throws(IOException::class)
    fun search(query: String, limit: Int = 10): List<Place> {
        if (query.isBlank()) return emptyList()
        rateLimit()
        val url = "$endpoint/api/".toHttpUrl().newBuilder().apply {
            addQueryParameter("q", query)
            addQueryParameter("limit", limit.toString())
        }.build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "en")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Photon ${resp.code} ${resp.message}")
            val body = resp.body?.string().orEmpty()
            return parse(body)
        }
    }

    /** Parses Photon's GeoJSON FeatureCollection into [Place]s. Public for tests. */
    internal fun parse(json: String): List<Place> {
        if (json.isBlank()) return emptyList()
        val root = JSONObject(json)
        val features = root.optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Place>(features.length())
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val geom = f.optJSONObject("geometry") ?: continue
            val coords = geom.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val lon = coords.optDouble(0).takeUnless { it.isNaN() } ?: continue
            val lat = coords.optDouble(1).takeUnless { it.isNaN() } ?: continue
            val props = f.optJSONObject("properties") ?: JSONObject()
            val display = formatDisplayName(props)
            if (display.isBlank()) continue
            out += Place(display, LatLng(lat, lon))
        }
        return out
    }

    private fun formatDisplayName(props: JSONObject): String {
        val name = props.optString("name").trim()
        val street = props.optString("street").trim()
        val number = props.optString("housenumber").trim()
        val city = props.optString("city").trim().ifBlank { props.optString("locality").trim() }
        val country = props.optString("country").trim()

        // Build "<name>, <street housenumber>, <city>, <country>", skipping empty parts.
        val parts = mutableListOf<String>()
        if (name.isNotBlank()) parts += name
        val streetLine = if (street.isNotBlank() && number.isNotBlank()) "$street $number"
                          else if (street.isNotBlank()) street else ""
        if (streetLine.isNotBlank() && streetLine !in parts) parts += streetLine
        if (city.isNotBlank() && city !in parts) parts += city
        if (country.isNotBlank() && country !in parts) parts += country
        return parts.joinToString(", ")
    }

    private fun rateLimit() {
        val now = System.nanoTime()
        val prev = lastRequestNs.get()
        val elapsed = now - prev
        val minIntervalNs = TimeUnit.MILLISECONDS.toNanos(MIN_INTERVAL_MS)
        if (elapsed < minIntervalNs) {
            val sleepNanos = minIntervalNs - elapsed
            try {
                TimeUnit.NANOSECONDS.sleep(sleepNanos)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        lastRequestNs.set(System.nanoTime())
    }

    companion object {
        private const val MIN_INTERVAL_MS = 250L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
