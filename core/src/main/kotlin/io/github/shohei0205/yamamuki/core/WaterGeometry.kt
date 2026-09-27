package io.github.shohei0205.yamamuki.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WaterPolygon(val river: Boolean, val rings: List<List<PlanOffset>>)

object WaterGeometry {
    /** WAT1 keeps the outer ring and all island holes together. */
    fun decode(bytes: ByteArray): List<WaterPolygon> {
        require(bytes.size >= 8 && bytes.take(4) == listOf<Byte>(87,65,84,49))
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        input.position(4)
        val count = input.int
        require(count in 0..input.remaining()/24)
        val result = List(count) {
            require(input.remaining() >= 8)
            val kind = input.int; val rings = input.int
            require(kind in 0..1 && rings in 1..input.remaining()/16)
            WaterPolygon(kind == 1, List(rings) {
                require(input.remaining() >= 4)
                val points = input.int
                require(points in 3..input.remaining()/4)
                List(points) {
                    val x = input.short.toInt() and 0xffff
                    val y = input.short.toInt() and 0xffff
                    require(x <= 4096 && y <= 4096)
                    PlanOffset(x / 4096.0, y / 4096.0)
                }
            })
        }
        require(!input.hasRemaining())
        return result
    }
}
