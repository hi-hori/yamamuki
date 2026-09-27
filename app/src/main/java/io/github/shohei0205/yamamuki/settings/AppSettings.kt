package io.github.shohei0205.yamamuki.settings

import android.content.Context
import io.github.shohei0205.yamamuki.core.DialGeometry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val minElevationM: Int = 0,
    val keepScreenOn: Boolean = false,
    val maxPeaks: Int = 40,
    val textScale: Float = 1f,
    val initialRangeKm: Int = DialGeometry.DEFAULT_RANGE_KM.toInt(),
) {
    companion object {
        val TEXT_SCALES = listOf(0.85f, 1f, 1.2f, 1.4f)
        val INITIAL_RANGES_KM = listOf(5, 10, 15, 20, 30, 50)
        val MAX_PEAKS_RANGE = 10..100
    }
}

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(Settings(
        minElevationM = prefs.getInt("min_elevation_m", 0),
        keepScreenOn = prefs.getBoolean("keep_screen_on", false),
        maxPeaks = prefs.getInt("max_peaks", 40),
        textScale = prefs.getFloat("text_scale", 1f),
        initialRangeKm = prefs.getInt("initial_range_km", DialGeometry.DEFAULT_RANGE_KM.toInt()),
    ))
    val settings = state.asStateFlow()
    fun update(transform: (Settings) -> Settings) {
        val next = transform(state.value)
        state.value = next
        prefs.edit().putInt("min_elevation_m", next.minElevationM)
            .putBoolean("keep_screen_on", next.keepScreenOn)
            .putInt("max_peaks", next.maxPeaks).putFloat("text_scale", next.textScale)
            .putInt("initial_range_km", next.initialRangeKm).apply()
    }
}
