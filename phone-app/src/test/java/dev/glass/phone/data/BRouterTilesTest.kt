package dev.glass.phone.data

import dev.glass.phone.routing.LatLng
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class BRouterTilesTest {

    @Test fun `tile name for Berlin`() {
        assertThat(BRouterTiles.tileName(52.5, 13.4)).isEqualTo("E10_N50")
    }

    @Test fun `tile name for origin`() {
        assertThat(BRouterTiles.tileName(0.0, 0.0)).isEqualTo("E0_N0")
    }

    @Test fun `tile name for southwest quadrant`() {
        assertThat(BRouterTiles.tileName(-1.0, -1.0)).isEqualTo("W5_S5")
    }

    @Test fun `tile name on tile boundary uses the lower tile`() {
        assertThat(BRouterTiles.tileName(50.0, 10.0)).isEqualTo("E10_N50")
        assertThat(BRouterTiles.tileName(-5.0, -5.0)).isEqualTo("W5_S5")
    }

    @Test fun `route within one tile yields one tile`() {
        // Two points in Berlin
        val tiles = BRouterTiles.tilesForRoute(
            LatLng(52.52, 13.41), LatLng(52.5, 13.44), marginDeg = 0.0,
        )
        assertThat(tiles).containsExactly("E10_N50")
    }

    @Test fun `route Berlin to Hamburg spans two tiles`() {
        val tiles = BRouterTiles.tilesForRoute(
            LatLng(52.52, 13.41),  // Berlin
            LatLng(53.55, 9.99),   // Hamburg — note lat crosses 55? no, both in N50 row; lon both in E5/E10
            marginDeg = 0.0,
        )
        // Berlin lat=52.5 lon=13.4 → E10_N50; Hamburg lat=53.55 lon=9.99 → E5_N50
        assertThat(tiles).containsExactlyInAnyOrder("E5_N50", "E10_N50")
    }

    @Test fun `urlFor builds the brouter segments4 URL`() {
        assertThat(BRouterTiles.urlFor("E10_N50"))
            .isEqualTo("https://brouter.de/brouter/segments4/E10_N50.rd5")
    }
}
