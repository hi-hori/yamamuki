package io.github.shohei0205.yamamuki.core

import kotlin.test.*

class RiverGeometryTest {
    @Test fun visibilityRespectsRotationAndStrokeAtScreenEdge() {
        val bounds = RiverBounds(-.1,-10.0,.1,-9.0)
        fun visible(heading: Double) = bounds.visible(50.0,100.0,10.0,heading,100.0,0.0,100.0,0.0)
        assertTrue(visible(0.0))
        assertFalse(visible(90.0))
        assertFalse(visible(180.0))
        val edge = RiverBounds(-5.2,-1.0,-5.1,0.0)
        assertFalse(edge.visible(50.0,50.0,10.0,0.0,100.0,0.0,100.0,0.0))
        assertTrue(edge.visible(50.0,50.0,10.0,0.0,100.0,0.0,100.0,2.0))
    }

    @Test fun visibleBatchesNeverRejectVisibleTransformedCorners() {
        val bounds = RiverBounds(-2.0,-5.0,3.0,1.0)
        for (heading in 0..359 step 7) for (ox in -100..200 step 25) for (oy in -100..200 step 25) {
            val a = Math.toRadians(heading.toDouble())
            val anyCornerVisible = listOf(-2.0,3.0).any { x -> listOf(-5.0,1.0).any { y ->
                val sx = ox + (kotlin.math.cos(a)*x + kotlin.math.sin(a)*y)*10
                val sy = oy + (-kotlin.math.sin(a)*x + kotlin.math.cos(a)*y)*10
                sx in 0.0..100.0 && sy in 20.0..100.0
            } }
            if (anyCornerVisible) assertTrue(bounds.visible(ox.toDouble(),oy.toDouble(),10.0,
                heading.toDouble(),100.0,20.0,100.0,0.0))
        }
    }

    private val tile = byteArrayOf(82,73,86,49,1,0,0,0,2,0,0,0,0,0,0,0,0,16,0,16)

    @Test fun readsBuilderFormatAndProjectsToTerrainCorners() {
        val line = RiverGeometry.decode(tile).single()
        assertEquals(listOf(PlanOffset(0.0,0.0), PlanOffset(1.0,1.0)), line)
        val key = TerrainGeometry.key(35.696,139.814,12)
        val mesh = TerrainGeometry.mesh(key,35.696,139.814)
        assertEquals(mesh.first(),RiverGeometry.project(key,line.first(),35.696,139.814))
        assertEquals(mesh.last(),RiverGeometry.project(key,line.last(),35.696,139.814))
    }

    @Test fun rejectsTruncatedOutOfBoundsAndTrailingData() {
        for (bytes in listOf(tile.copyOf(19), tile + byteArrayOf(0), tile.copyOf().apply { this[19] = 32 },
            tile.copyOf().apply { this[4] = 127 }, tile.copyOf().apply { this[0] = 0 })) {
            assertFailsWith<IllegalArgumentException> { RiverGeometry.decode(bytes) }
        }
    }
}
