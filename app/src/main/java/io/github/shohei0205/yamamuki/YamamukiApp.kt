package io.github.shohei0205.yamamuki

import io.github.shohei0205.yamamuki.data.TerrainData
import android.app.Application
import io.github.shohei0205.yamamuki.data.BundledData
import io.github.shohei0205.yamamuki.settings.AppSettings

class YamamukiApp : Application() {
    val terrainData: TerrainData by lazy { TerrainData(this) }
    val settings: AppSettings by lazy { AppSettings(this) }
    val bundledData: BundledData by lazy { BundledData(this) }
}
