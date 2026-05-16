package dev.glass.phone.data

import dev.glass.phone.routing.LatLng
import dev.glass.phone.ui.search.GeocodingClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class MapsforgeRegionResolverTest {

    private lateinit var server: MockWebServer
    private lateinit var resolver: MapsforgeRegionResolver

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val client = GeocodingClient(
            client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
            endpoint = server.url("/").toString().trimEnd('/'),
        )
        resolver = MapsforgeRegionResolver(client)
    }

    @After fun tearDown() { server.shutdown() }

    private fun enqueueReverse(countryCode: String, state: String? = null) {
        val stateField = state?.let { ""","state":"$it"""" } ?: ""
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"type":"FeatureCollection","features":[
              {"properties":{"countrycode":"$countryCode"$stateField}}
            ]}
        """.trimIndent()))
    }

    @Test fun `resolves germany to europe germany map`() {
        enqueueReverse("DE")
        val r = resolver.resolve(LatLng(52.52, 13.41))
        assertThat(r).isNotNull
        assertThat(r!!.localName).isEqualTo("germany.map")
        assertThat(r.url).isEqualTo("https://download.mapsforge.org/maps/v5/europe/germany.map")
    }

    @Test fun `unknown country returns null`() {
        enqueueReverse("ZZ")
        assertThat(resolver.resolve(LatLng(0.0, 0.0))).isNull()
    }

    @Test fun `no features returns null`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"type":"FeatureCollection","features":[]}
        """.trimIndent()))
        assertThat(resolver.resolve(LatLng(0.0, 0.0))).isNull()
    }

    @Test fun `network error returns null instead of throwing`() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThat(resolver.resolve(LatLng(0.0, 0.0))).isNull()
    }

    @Test fun `resolves US California to state file`() {
        enqueueReverse("US", state = "California")
        val r = resolver.resolve(LatLng(37.77, -122.42))
        assertThat(r).isNotNull
        assertThat(r!!.localName).isEqualTo("california.map")
        assertThat(r.url).isEqualTo("https://download.mapsforge.org/maps/v5/north-america/us/california.map")
    }

    @Test fun `resolves US New York with multi-word slug`() {
        enqueueReverse("US", state = "New York")
        val r = resolver.resolve(LatLng(40.71, -74.0))
        assertThat(r!!.localName).isEqualTo("new-york.map")
    }

    @Test fun `resolves DC as district-of-columbia`() {
        enqueueReverse("US", state = "District of Columbia")
        val r = resolver.resolve(LatLng(38.9, -77.03))
        assertThat(r!!.localName).isEqualTo("district-of-columbia.map")
    }

    @Test fun `Alaska mainland picks alaska-1`() {
        enqueueReverse("US", state = "Alaska")
        // Anchorage, lon ~-149.9, east of -160 → alaska-1
        val r = resolver.resolve(LatLng(61.22, -149.9))
        assertThat(r!!.localName).isEqualTo("alaska-1.map")
    }

    @Test fun `Alaska Aleutians picks alaska-2`() {
        enqueueReverse("US", state = "Alaska")
        // Dutch Harbor, lon ~-166.5, west of -160 → alaska-2
        val r = resolver.resolve(LatLng(53.89, -166.54))
        assertThat(r!!.localName).isEqualTo("alaska-2.map")
    }

    @Test fun `US without state returns null`() {
        enqueueReverse("US")
        assertThat(resolver.resolve(LatLng(40.0, -100.0))).isNull()
    }

    @Test fun `US unknown state returns null`() {
        enqueueReverse("US", state = "Atlantis")
        assertThat(resolver.resolve(LatLng(40.0, -100.0))).isNull()
    }
}
