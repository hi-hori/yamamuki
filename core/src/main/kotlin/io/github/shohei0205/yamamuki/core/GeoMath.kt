package io.github.shohei0205.yamamuki.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    const val EARTH_RADIUS_KM = 6371.0088

    /** 2点間の大円距離(km)。 */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_KM * asin(min(1.0, sqrt(a)))
    }

    /** 地点1から地点2への初期方位角(真北 0°、時計回り、0〜360°)。 */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}

/** 緯度経度の矩形。日付変更線をまたぐ範囲は扱わない。 */
data class BoundingBox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    fun contains(lat: Double, lon: Double): Boolean =
        lat in south..north && lon in west..east

    companion object {
        /** 中心から半径 radiusKm の円を含む矩形。 */
        fun around(lat: Double, lon: Double, radiusKm: Double): BoundingBox {
            val dLat = Math.toDegrees(radiusKm / GeoMath.EARTH_RADIUS_KM)
            val cosLat = max(cos(Math.toRadians(lat)), 0.01)
            val dLon = min(Math.toDegrees(radiusKm / (GeoMath.EARTH_RADIUS_KM * cosLat)), 180.0)
            return BoundingBox(
                south = max(lat - dLat, -90.0),
                west = max(lon - dLon, -180.0),
                north = min(lat + dLat, 90.0),
                east = min(lon + dLon, 180.0),
            )
        }
    }
}
