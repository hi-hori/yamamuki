package io.github.shohei0205.yamamuki.core

/** 画面上の矩形(px)。 */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun intersects(other: Box): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/**
 * 山の表示が重ならないよう間引く。items は優先度の高い順(例: 標高の高い順)に並べて渡す。
 * 先に置いたものと重なる項目は捨て、最大 [limit] 件を返す。
 */
fun <T> declutter(items: List<T>, limit: Int = Int.MAX_VALUE, boxOf: (T) -> Box): List<T> {
    val placed = mutableListOf<Box>()
    val result = mutableListOf<T>()
    for (item in items) {
        if (result.size >= limit) break
        val box = boxOf(item)
        if (placed.none { it.intersects(box) }) {
            placed += box
            result += item
        }
    }
    return result
}

/** 表示の優先順: 標高の高い順、標高不明は後ろ、同じなら近い順。 */
val displayPriority: Comparator<NearbyMountain> =
    compareByDescending<NearbyMountain> { it.mountain.elevationM ?: Double.NEGATIVE_INFINITY }
        .thenBy { it.distanceKm }
