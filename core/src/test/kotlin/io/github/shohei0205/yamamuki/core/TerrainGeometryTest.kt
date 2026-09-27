package io.github.shohei0205.yamamuki.core
import kotlin.math.*
import kotlin.test.*
class TerrainGeometryTest {
    @Test fun zoomRefinesBeforeLargeMagnification() {
        assertEquals(12, terrainZoom(16.0))
        assertEquals(11, terrainZoom(16.01))
        assertEquals(11, terrainZoom(50.0))
        assertEquals(10, terrainZoom(50.01))
    }
    @Test fun terrainAndPeaksUseTheSameProjectionAtEveryMeshVertex() {
        val key = TerrainGeometry.key(35.696, 139.814, 11)
        val mesh = TerrainGeometry.mesh(key, 35.696, 139.814)
        assertEquals(25, mesh.size)
        for (j in 0..4) for (i in 0..4) {
            val lon = (key.x + i / 4.0) / 2048 * 360 - 180
            val lat = Math.toDegrees(atan(sinh(PI * (1 - 2 * (key.y + j / 4.0) / 2048))))
            val distance = GeoMath.distanceKm(35.696, 139.814, lat, lon)
            val bearing = GeoMath.bearingDeg(35.696, 139.814, lat, lon)
            val p = mesh[j * 5 + i]
            for (heading in listOf(0.0, 90.0, 270.0, 359.0)) {
                val a = Math.toRadians(heading)
                val expected = DialGeometry.project(distance, bearing, heading)
                assertEquals(expected.x, p.x * cos(a) - p.y * sin(a), 1e-8)
                assertEquals(expected.y, p.x * sin(a) + p.y * cos(a), 1e-8)
            }
        }
    }

    @Test fun adjacentTerrainMeshesShareTheirEdges() {
        val key = TerrainGeometry.key(35.7, 139.8, 11)
        val a = TerrainGeometry.mesh(key, 35.7, 139.8)
        val b = TerrainGeometry.mesh(key.copy(x = key.x + 1), 35.7, 139.8)
        for (row in 0..4) assertEquals(a[row * 5 + 4], b[row * 5])
        assertTrue(TerrainGeometry.covering(BoundingBox.around(35.7, 139.8, 60.0), 11).contains(key))
    }
}
