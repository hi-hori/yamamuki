package io.github.shohei0205.yamamuki.core

import kotlin.math.*

data class TerrainKey(val zoom: Int, val x: Int, val y: Int) {
    val entry: String get() = "terrain/$zoom/$x/$y.webp"
}

object TerrainGeometry {
    fun key(latitude: Double, longitude: Double, zoom: Int): TerrainKey {
        val n = (1 shl zoom).toDouble()
        val x = floor((longitude + 180) / 360 * n).toInt().coerceIn(0, n.toInt() - 1)
        val y = floor((1 - asinh(tan(Math.toRadians(latitude.coerceIn(-85.0, 85.0)))) / PI) / 2 * n)
            .toInt().coerceIn(0, n.toInt() - 1)
        return TerrainKey(zoom, x, y)
    }

    fun covering(box: BoundingBox, zoom: Int): List<TerrainKey> {
        val nw = key(box.north, box.west, zoom)
        val se = key(box.south, box.east, zoom)
        return (nw.y..se.y).flatMap { y -> (nw.x..se.x).map { x -> TerrainKey(zoom, x, y) } }
    }

    /** Bitmap mesh の北向き平面座標。山アイコンと同じ大円距離・方位に投影する。 */
    fun mesh(key: TerrainKey, latitude: Double, longitude: Double, divisions: Int = 4): List<PlanOffset> {
        require(divisions > 0)
        val n = (1 shl key.zoom).toDouble()
        return (0..divisions).flatMap { j -> (0..divisions).map { i ->
            val lon = (key.x + i.toDouble() / divisions) / n * 360 - 180
            val lat = Math.toDegrees(atan(sinh(PI * (1 - 2 * (key.y + j.toDouble() / divisions) / n))))
            DialGeometry.project(GeoMath.distanceKm(latitude, longitude, lat, lon),
                GeoMath.bearingDeg(latitude, longitude, lat, lon), 0.0)
        } }
    }
}

fun terrainZoom(rangeKm: Double): Int = when {
    rangeKm <= 16 -> 12
    rangeKm <= 50 -> 11
    else -> 10
}
