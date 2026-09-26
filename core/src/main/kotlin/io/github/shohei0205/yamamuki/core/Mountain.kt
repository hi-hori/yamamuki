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

/** 方位盤に出す表示名。「山名 (1,212m)」、標高不明なら山名のみ。 */
fun Mountain.displayLabel(): String {
    val ele = elevationM ?: return name
    return String.format(java.util.Locale.US, "%s (%,dm)", name, Math.round(ele))
}

/** 詳細表示の標高。「1,212 m」、不明なら「不明」。 */
fun Mountain.elevationText(): String {
    val ele = elevationM ?: return "不明"
    return String.format(java.util.Locale.US, "%,d m", Math.round(ele))
}

/** 詳細表示の緯度経度。狭い画面で途中で折り返さないよう、緯度と経度を改行で分ける。 */
fun Mountain.coordinateText(): String {
    val lat = String.format(java.util.Locale.US, "%.5f°", kotlin.math.abs(latitude))
    val lon = String.format(java.util.Locale.US, "%.5f°", kotlin.math.abs(longitude))
    return "${if (latitude >= 0) "北緯" else "南緯"} $lat\n${if (longitude >= 0) "東経" else "西経"} $lon"
}

/** 詳細表示の距離。1km 未満は「850 m」、以上は「12.3 km」。 */
fun distanceText(distanceKm: Double): String =
    if (distanceKm < 1.0) {
        "${Math.round(distanceKm * 1000)} m"
    } else {
        String.format(java.util.Locale.US, "%,.1f km", distanceKm)
    }
