package io.github.shohei0205.yamamuki.core

/** OSM の山頂ノード1件。 */
data class Mountain(
    val osmId: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    /** 標高(m)。OSM に ele タグが無い、または解釈できない場合は null。 */
    val elevationM: Double?,
)

/** 現在地から見た山。距離と方位角(真北基準、時計回り 0〜360°)を持つ。 */
data class NearbyMountain(
    val mountain: Mountain,
    val distanceKm: Double,
    val bearingDeg: Double,
)
