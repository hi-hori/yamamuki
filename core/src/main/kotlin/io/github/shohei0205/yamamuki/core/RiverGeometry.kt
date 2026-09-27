package io.github.shohei0205.yamamuki.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Bounds in the cached path's east/right, south/down kilometre coordinates. */
data class RiverBounds(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    fun visible(originX: Double, originY: Double, scale: Double, heading: Double,
        width: Double, chartTop: Double, chartBottom: Double, padding: Double): Boolean {
        val angle = Math.toRadians(heading)
        val c = cos(angle); val s = sin(angle)
        val x = (left + right) / 2; val y = (top + bottom) / 2
        val dx = (right - left) / 2; val dy = (bottom - top) / 2
        val cx = originX + (c*x + s*y)*scale
        val cy = originY + (-s*x + c*y)*scale
        val rx = (abs(c)*dx + abs(s)*dy)*scale + padding
        val ry = (abs(s)*dx + abs(c)*dy)*scale + padding
        return cx + rx >= 0 && cx - rx <= width && cy + ry >= chartTop && cy - ry <= chartBottom
    }
}

/** Compact, clipped vector tile. Coordinates are fractions of a Mercator tile. */
object RiverGeometry {
    /** Spatial batches avoid submitting one enormous path, including invisible rivers. */
    fun bucket(line: List<PlanOffset>, divisions: Int): Int {
        val a = line.first(); val b = line.last()
        val x = (((a.x + b.x) / 2) * divisions).toInt().coerceIn(0, divisions - 1)
        val y = (((a.y + b.y) / 2) * divisions).toInt().coerceIn(0, divisions - 1)
        return y * divisions + x
    }

    fun decode(bytes: ByteArray): List<List<PlanOffset>> {
        require(bytes.size >= 8 && bytes.take(4) == listOf<Byte>(82, 73, 86, 49))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(4)
        val count = buffer.int
        require(count in 0..(buffer.remaining() / 12))
        val lines = List(count) {
            require(buffer.remaining() >= 4)
            val points = buffer.int
            require(points in 2..(buffer.remaining() / 4))
            List(points) {
                val x = buffer.short.toInt() and 0xffff
                val y = buffer.short.toInt() and 0xffff
                require(x <= 4096 && y <= 4096)
                PlanOffset(x / 4096.0, y / 4096.0)
            }
        }
        require(!buffer.hasRemaining())
        return lines
    }

    fun project(key: TerrainKey, point: PlanOffset, latitude: Double, longitude: Double): PlanOffset {
        val n = (1 shl key.zoom).toDouble()
        val lon = (key.x + point.x) / n * 360 - 180
        val lat = Math.toDegrees(atan(sinh(PI * (1 - 2 * (key.y + point.y) / n))))
        return DialGeometry.project(GeoMath.distanceKm(latitude, longitude, lat, lon),
            GeoMath.bearingDeg(latitude, longitude, lat, lon), 0.0)
    }
}
