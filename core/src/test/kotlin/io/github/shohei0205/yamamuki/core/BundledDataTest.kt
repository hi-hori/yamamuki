package io.github.shohei0205.yamamuki.core

import kotlin.test.*

class BundledDataTest {
    private val header = "osm_id,latitude,longitude,name,elevation_m\n"

    @Test fun csvPreservesQuotedJapaneseNamesAndMissingElevation() {
        val peaks = BundledPeaks.parse(header + "1,35.5,139.5,\"山,\"\"峰\"\"\n北\",123.4\n2,36,140,無名峰,\n")
        assertEquals("山,\"峰\"\n北", peaks[0].name)
        assertEquals(123.4, peaks[0].elevationM)
        assertNull(peaks[1].elevationM)
    }

    @Test fun csvRejectsCorruptOrDuplicateData() {
        for (body in listOf("1,NaN,139,山,1", "1,91,139,山,1", "1,35,139,山,Infinity",
            "1,35,139,\"山,1", "1,35,139,山,1\n1,35,139,山,1")) {
            assertFailsWith<IllegalArgumentException> { BundledPeaks.parse(header + body) }
        }
    }

}
