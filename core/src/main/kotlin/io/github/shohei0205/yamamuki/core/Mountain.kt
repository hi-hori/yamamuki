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

/** 方位盤で山アイコンの色と形を分ける標高の区分。 */
enum class ElevationClass {
    /** 1000m 未満。標高不明もここに含める。 */
    LOW,

    /** 1000m 以上 2000m 未満。 */
    MIDDLE,

    /** 2000m 以上。 */
    HIGH,
}

fun Mountain.elevationClass(): ElevationClass {
    val ele = elevationM ?: return ElevationClass.LOW
    return when {
        ele < 1000.0 -> ElevationClass.LOW
        ele < 2000.0 -> ElevationClass.MIDDLE
        else -> ElevationClass.HIGH
    }
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
