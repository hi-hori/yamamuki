package io.github.shohei0205.yamamuki.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OverpassParserTest {
    @Test
    fun parsesNamedNodes() {
        val body = """
            {"version":0.6,"elements":[
              {"type":"node","id":1,"lat":35.3606,"lon":138.7274,"tags":{"natural":"volcano","name":"富士山","ele":"3776"}},
              {"type":"node","id":2,"lat":35.0,"lon":138.0,"tags":{"natural":"peak","name":"Mt. X","name:ja":"エックス山","ele":"1,234 m"}},
              {"type":"node","id":3,"lat":35.1,"lon":138.1,"tags":{"natural":"peak"}},
              {"type":"node","id":4,"lat":35.2,"lon":138.2,"tags":{"natural":"peak","name":"無標高山"}},
              {"type":"way","id":5,"tags":{"name":"way"}}
            ]}
        """.trimIndent()

        val result = OverpassParser.parse(body)

        assertEquals(
            listOf(
                Mountain(1, "富士山", 35.3606, 138.7274, 3776.0),
                Mountain(2, "エックス山", 35.0, 138.0, 1234.0),
                Mountain(4, "無標高山", 35.2, 138.2, null),
            ),
            result,
        )
    }

    @Test
    fun parsesElevationVariants() {
        assertEquals(3776.0, OverpassParser.parseElevation("3776"))
        assertEquals(3776.5, OverpassParser.parseElevation("3776.5 m"))
        assertEquals(3776.0, OverpassParser.parseElevation("3776;3775"))
        assertEquals(304.8, OverpassParser.parseElevation("1000 ft")!!, 0.001)
        assertNull(OverpassParser.parseElevation("unknown"))
        assertNull(OverpassParser.parseElevation(null))
    }

    @Test
    fun queryUsesBboxOrder() {
        val q = OverpassQuery.peaks(BoundingBox(35.0, 138.0, 36.0, 139.0))
        kotlin.test.assertContains(q, """node["natural"="peak"]["name"](35.00000,138.00000,36.00000,139.00000);""")
        kotlin.test.assertContains(q, "[out:json]")
    }
}
