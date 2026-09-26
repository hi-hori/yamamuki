package io.github.shohei0205.yamamuki.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.shohei0205.yamamuki.YamamukiApp
import io.github.shohei0205.yamamuki.core.DialGeometry
import io.github.shohei0205.yamamuki.core.GeoMath
import io.github.shohei0205.yamamuki.core.Mountain
import io.github.shohei0205.yamamuki.core.NearbyMountain
import io.github.shohei0205.yamamuki.core.displayPriority
import io.github.shohei0205.yamamuki.core.meetsMinElevation
import io.github.shohei0205.yamamuki.data.CacheInfo
import io.github.shohei0205.yamamuki.settings.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    /** GPS の高さ(楕円体高)。磁気偏角の計算に使う。 */
    val altitudeM: Double = 0.0,
    /** 標高(海抜)。求められないときは null。 */
    val mslAltitudeM: Double? = null,
)

data class DialUiState(
    val location: GeoPoint? = null,
    /**
     * 現在地から見た山。表示の優先順(標高の高い順)。現在地が変わるたびに距離と方位を計算し直す。
     * 設定の「表示する最低標高」で絞り込んだ後のもの。
     */
    val mountains: List<NearbyMountain> = emptyList(),
    /** 現在地から画面上端までの距離。 */
    val rangeKm: Double = DialGeometry.DEFAULT_RANGE_KM,
    val loading: Boolean = false,
    /** 通信に失敗し、キャッシュだけで表示している。 */
    val offline: Boolean = false,
    /** 範囲内に一度も取得できていない地域がある。 */
    val incomplete: Boolean = false,
    /** 手動取得モードのため、未取得または古い地域があっても通信しなかった。 */
    val networkSkipped: Boolean = false,
    val settings: Settings = Settings(),
    /** 設定画面に出すキャッシュの状況。読み込むまでは null。 */
    val cacheInfo: CacheInfo? = null,
)

/** 現在地と表示範囲に応じて山データを取得し、方位盤に出す山の一覧を保つ。 */
class DialViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as YamamukiApp
    private val repository = app.mountainRepository
    private val appSettings = app.settings
    private val cacheManager = app.cacheManager

    private val _state = MutableStateFlow(
        appSettings.settings.value.let { DialUiState(settings = it, rangeKm = it.initialRangeKm.toDouble()) },
    )
    val state: StateFlow<DialUiState> = _state.asStateFlow()

    private var peaks: List<Mountain> = emptyList()
    private var fetchedCenter: GeoPoint? = null
    private var fetchedRadiusKm = 0.0
    private var fetchJob: Job? = null

    fun onLocation(newPoint: GeoPoint) {
        // GPS とネットワーク位置が交互に届くと、高さを持たない位置で標高の表示が消えたり出たりする。
        // 高さが無い位置では直前の標高を引き継ぐ。
        val point = if (newPoint.mslAltitudeM == null) {
            newPoint.copy(mslAltitudeM = _state.value.location?.mslAltitudeM)
        } else {
            newPoint
        }
        _state.update { it.copy(location = point, mountains = relativeTo(point)) }
        val center = fetchedCenter
        if (center == null ||
            GeoMath.distanceKm(center.latitude, center.longitude, point.latitude, point.longitude) > REFETCH_DISTANCE_KM
        ) {
            fetch()
        }
    }

    fun onZoom(zoom: Float) {
        _state.update { it.copy(rangeKm = DialGeometry.zoomedRange(it.rangeKm, zoom)) }
        if (DialGeometry.fetchRadiusKm(_state.value.rangeKm) > fetchedRadiusKm) fetch()
    }

    fun retry() = fetch(forceRefresh = true)

    /** 左下の更新ボタン(山データを取得)。今の表示範囲のうち、未取得または古い地域を取得する。 */
    fun fetchManually() = fetch(manual = true)

    fun updateSettings(transform: (Settings) -> Settings) {
        val before = _state.value.settings
        appSettings.update(transform)
        val after = appSettings.settings.value
        _state.update { it.copy(settings = after) }

        if (after.minElevationM != before.minElevationM) {
            _state.value.location?.let { here -> _state.update { it.copy(mountains = relativeTo(here)) } }
        }
        // 起動時の範囲を変えたら、試しやすいよう今の表示にもすぐ反映する。
        if (after.initialRangeKm != before.initialRangeKm) {
            _state.update { it.copy(rangeKm = after.initialRangeKm.toDouble()) }
            if (DialGeometry.fetchRadiusKm(after.initialRangeKm.toDouble()) > fetchedRadiusKm) fetch()
        }
        // 手動取得をやめたら、控えていた分をすぐ取得する。
        if (before.manualFetch && !after.manualFetch && _state.value.networkSkipped) fetch()
    }

    fun refreshCacheInfo() {
        viewModelScope.launch {
            val info = cacheManager.info()
            _state.update { it.copy(cacheInfo = info) }
        }
    }

    /** キャッシュを消して、現在地周辺を取り直す。 */
    fun clearCache() {
        fetchJob?.cancel()
        viewModelScope.launch {
            cacheManager.clear()
            peaks = emptyList()
            _state.update { it.copy(mountains = emptyList(), cacheInfo = cacheManager.info()) }
            fetch()
        }
    }

    private fun fetch(forceRefresh: Boolean = false, manual: Boolean = false) {
        val here = _state.value.location ?: return
        val radius = DialGeometry.fetchRadiusKm(_state.value.rangeKm)
        val settings = _state.value.settings
        val allowNetwork = manual || !settings.manualFetch
        fetchedCenter = here
        fetchedRadiusKm = radius
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val result = repository.mountainsAround(
                here.latitude,
                here.longitude,
                radius,
                forceRefresh = forceRefresh,
                allowNetwork = allowNetwork,
                maxAgeMillis = settings.cacheMaxAgeMillis,
            )
            // Log.w(tag, msg, tr) は UnknownHostException を含むと何も出さないので文字列にして渡す。
            result.error?.let { Log.w(TAG, "山データの取得に失敗\n${it.stackTraceToString()}") }
            peaks = result.mountains.map { it.mountain }
            _state.update {
                it.copy(
                    mountains = relativeTo(it.location ?: here),
                    loading = false,
                    offline = result.error != null,
                    incomplete = result.incomplete,
                    networkSkipped = result.networkSkipped,
                )
            }
        }
    }

    private fun relativeTo(p: GeoPoint): List<NearbyMountain> {
        val minElevation = _state.value.settings.minElevationM
        return peaks.filter { it.meetsMinElevation(minElevation) }.map {
            NearbyMountain(
                mountain = it,
                distanceKm = GeoMath.distanceKm(p.latitude, p.longitude, it.latitude, it.longitude),
                bearingDeg = GeoMath.bearingDeg(p.latitude, p.longitude, it.latitude, it.longitude),
            )
        }.sortedWith(displayPriority)
    }

    private companion object {
        /** これ以上移動したら取り直す。取得済みの地域ならキャッシュから読むだけで通信しない。 */
        const val REFETCH_DISTANCE_KM = 1.0

        const val TAG = "DialViewModel"
    }
}
