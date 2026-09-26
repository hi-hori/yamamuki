package io.github.shohei0205.yamamuki.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MountainTextTest {
    private val fuji = Mountain(1L, "富士山", 35.3605556, 138.7273889, 3776.24)

    @Test
    fun elevation() {
        assertEquals("3,776 m", fuji.elevationText())
        assertEquals("不明", fuji.copy(elevationM = null).elevationText())
    }

    @Test
    fun coordinate() {
        assertEquals("北緯 35.36056°\n東経 138.72739°", fuji.coordinateText())
        assertEquals("南緯 33.86880°\n西経 151.20930°", fuji.copy(latitude = -33.8688, longitude = -151.2093).coordinateText())
    }

    @Test
    fun distance() {
        assertEquals("850 m", distanceText(0.8504))
        assertEquals("12.3 km", distanceText(12.34))
        assertEquals("1,234.6 km", distanceText(1234.56))
    }
}
