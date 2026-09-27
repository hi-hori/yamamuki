package io.github.shohei0205.yamamuki.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class WaterGeometryTest {
    private fun tile(): ByteArray {
        val b = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(87,65,84,49)).putInt(1).putInt(0).putInt(2)
        for (ring in listOf(listOf(0,0,4096,0,4096,4096,0,4096),listOf(1024,1024,3072,1024,3072,3072,1024,3072))) {
            b.putInt(4)
            for (v in ring) b.putShort(v.toShort())
        }
        return b.array()
    }
    @Test fun keepsOuterRingAndIslandHoleSeparate() {
        val polygon = WaterGeometry.decode(tile()).single()
        assertFalse(polygon.river)
        assertEquals(2,polygon.rings.size)
        assertEquals(PlanOffset(.25,.25),polygon.rings[1].first())
        assertEquals(PlanOffset(0.0,0.0),polygon.rings[0].first())
    }
    @Test fun rejectsTruncatedAndInvalidCoordinates() {
        assertFailsWith<IllegalArgumentException> { WaterGeometry.decode(tile().copyOf(55)) }
        assertFailsWith<IllegalArgumentException> { WaterGeometry.decode(tile().apply { this[21] = 32 }) }
        assertFailsWith<IllegalArgumentException> { WaterGeometry.decode(tile() + byteArrayOf(0)) }
    }
}
