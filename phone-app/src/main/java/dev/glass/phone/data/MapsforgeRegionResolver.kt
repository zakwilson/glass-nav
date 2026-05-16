package dev.glass.phone.data

import dev.glass.phone.routing.LatLng
import dev.glass.phone.ui.search.GeocodingClient

/**
 * Maps a coordinate → Mapsforge map file. Mapsforge has no public lat/lon index, so we
 * reverse-geocode via Photon to get (countrycode, state) and then look it up:
 *
 *   • Most countries → a single country file like `europe/germany.map`.
 *   • US → state-level file under `north-america/us/<slug>.map` (Mapsforge has no `usa.map`).
 *     Alaska, which is split into `alaska-1.map` (mainland east of -160°) and `alaska-2.map`
 *     (Aleutians/Bering), is resolved by longitude.
 *
 * Returns `null` for an unknown region; the caller falls back to "no preview map" while
 * BRouter routing still works as long as the `.rd5` tile is present.
 */
class MapsforgeRegionResolver(private val geocoder: GeocodingClient) {

    data class RegionFile(val localName: String, val url: String)

    fun resolve(start: LatLng): RegionFile? {
        val rev = try {
            geocoder.reverse(start.lat, start.lon)
        } catch (_: Throwable) {
            return null
        } ?: return null

        val cc = rev.countryCode ?: return null
        if (cc == "us") return resolveUs(start, rev.state)
        val path = COUNTRY_TO_MAP[cc] ?: return null
        return regionFile(path)
    }

    private fun resolveUs(start: LatLng, state: String?): RegionFile? {
        val key = state?.let(::slugify) ?: return null
        // Alaska is split. East of -160° (most of the mainland and Anchorage area) → alaska-1.
        if (key == "alaska") {
            val slug = if (start.lon >= -160.0) "alaska-1" else "alaska-2"
            return regionFile("north-america/us/$slug.map")
        }
        if (key !in US_STATE_SLUGS) return null
        return regionFile("north-america/us/$key.map")
    }

    private fun regionFile(path: String): RegionFile {
        val fileName = path.substringAfterLast('/')
        return RegionFile(localName = fileName, url = "$MAPSFORGE_BASE/$path")
    }

    companion object {
        private const val MAPSFORGE_BASE = "https://download.mapsforge.org/maps/v5"

        // Seed with common cycling/touring countries. Add entries as needed.
        private val COUNTRY_TO_MAP: Map<String, String> = mapOf(
            "de" to "europe/germany.map",
            "fr" to "europe/france.map",
            "gb" to "europe/great-britain.map",
            "ie" to "europe/ireland-and-northern-ireland.map",
            "nl" to "europe/netherlands.map",
            "be" to "europe/belgium.map",
            "lu" to "europe/luxembourg.map",
            "ch" to "europe/switzerland.map",
            "at" to "europe/austria.map",
            "it" to "europe/italy.map",
            "es" to "europe/spain.map",
            "pt" to "europe/portugal.map",
            "dk" to "europe/denmark.map",
            "se" to "europe/sweden.map",
            "no" to "europe/norway.map",
            "fi" to "europe/finland.map",
            "pl" to "europe/poland.map",
            "cz" to "europe/czech-republic.map",
            "ca" to "north-america/canada.map",
            "au" to "australia-oceania/australia.map",
            "nz" to "australia-oceania/new-zealand.map",
            "jp" to "asia/japan.map",
        )

        // Slugs as Mapsforge names them under maps/v5/north-america/us/. "alaska" is a logical
        // key; the actual file is chosen in resolveUs() by longitude.
        private val US_STATE_SLUGS: Set<String> = setOf(
            "alabama", "alaska", "arizona", "arkansas", "california", "colorado",
            "connecticut", "delaware", "district-of-columbia", "florida", "georgia",
            "hawaii", "idaho", "illinois", "indiana", "iowa", "kansas", "kentucky",
            "louisiana", "maine", "maryland", "massachusetts", "michigan", "minnesota",
            "mississippi", "missouri", "montana", "nebraska", "nevada", "new-hampshire",
            "new-jersey", "new-mexico", "new-york", "north-carolina", "north-dakota",
            "ohio", "oklahoma", "oregon", "pennsylvania", "puerto-rico", "rhode-island",
            "south-carolina", "south-dakota", "tennessee", "texas", "us-virgin-islands",
            "utah", "vermont", "virginia", "washington", "west-virginia", "wisconsin",
            "wyoming",
        )

        internal fun slugify(name: String): String =
            name.trim().lowercase().replace(Regex("\\s+"), "-")
    }
}
