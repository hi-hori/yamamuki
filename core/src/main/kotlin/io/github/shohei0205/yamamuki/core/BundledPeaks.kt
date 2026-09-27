package io.github.shohei0205.yamamuki.core

/** 内蔵・再配布で共通のUTF-8 CSV。引用符、カンマ、改行を含む山名も復元する。 */
object BundledPeaks {
    fun parse(csv: String): List<Mountain> {
        val records = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        fun endField() { row.add(field.toString()); field.setLength(0) }
        while (i < csv.length) {
            val c = csv[i++]
            when {
                c == '"' && quoted && i < csv.length && csv[i] == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> endField()
                c == '\n' && !quoted -> { endField(); records.add(row); row = mutableListOf() }
                c == '\r' && !quoted -> Unit
                else -> field.append(c)
            }
        }
        require(!quoted) { "Unterminated CSV quote" }
        if (field.isNotEmpty() || row.isNotEmpty()) { endField(); records.add(row) }
        require(records.firstOrNull() == listOf("osm_id", "latitude", "longitude", "name", "elevation_m"))
        return records.drop(1).map { cells ->
            require(cells.size == 5 && cells[3].isNotBlank())
            val lat = cells[1].toDouble()
            val lon = cells[2].toDouble()
            require(lat in -90.0..90.0 && lon in -180.0..180.0)
            val ele = cells[4].takeIf { it.isNotEmpty() }?.toDouble()
            require(ele == null || ele.isFinite())
            Mountain(cells[0].toLong(), cells[3], lat, lon, ele)
        }.also { mountains -> require(mountains.map { it.osmId }.distinct().size == mountains.size) }
    }
}
