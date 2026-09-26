package io.github.shohei0205.yamamuki.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.shohei0205.yamamuki.YamamukiApp
import io.github.shohei0205.yamamuki.core.DialGeometry
import io.github.shohei0205.yamamuki.core.GeoMath
import io.github.shohei0205.yamamuki.core.Mountain
import io.github.shohei0205.yamamuki.core.NearbyMountain
import io.github.shohei0205.yamamuki.core.displayPriority
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GeoPoint(val latitude: Double, val longitude: Double, val altitudeM: Double = 0.0)

data class DialUiState(
    val location: GeoPoint? = null,
    /** 現在地から見た山。表示の優先順(標高の高い順)。現在地が変わるたびに距離と方位を計算し直す。 */
    val mountains: List<NearbyMountain> = emptyList(),
    /** 現在地から画面上端までの距離。 */
    val rangeKm: Double = DialGeometry.DEFAULT_RANGE_KM,
    val loading: Boolean = false,
    /** 通信に失敗し、キャッシュだけで表示している。 */
    val offline: Boolean = false,
    /** 範囲内に一度も取得できていない地域がある。 */
    val incomplete: Boolean = false,
)

/** 現在地と表示範囲に応じて山データを取得し、方位盤に出す山の一覧を保つ。 */
class DialViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as YamamukiApp).mountainRepository

    private val _state = MutableStateFlow(DialUiState())
    val state: StateFlow<DialUiState> = _state.asStateFlow()

    private var peaks: List<Mountain> = emptyList()
    private var fetchedCenter: GeoPoint? = null
    private var fetchedRadiusKm = 0.0
    private var fetchJob: Job? = null

    fun onLocation(point: GeoPoint) {
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

    private fun fetch(forceRefresh: Boolean = false) {
        val here = _state.value.location ?: return
        val radius = DialGeometry.fetchRadiusKm(_state.value.rangeKm)
        fetchedCenter = here
        fetchedRadiusKm = radius
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val result = repository.mountainsAround(here.latitude, here.longitude, radius, forceRefresh)
            peaks = result.mountains.map { it.mountain }
            _state.update {
                it.copy(
                    mountains = relativeTo(it.location ?: here),
                    loading = false,
                    offline = result.error != null,
                    incomplete = result.incomplete,
                )
            }
        }
    }

    private fun relativeTo(p: GeoPoint): List<NearbyMountain> = peaks.map {
        NearbyMountain(
            mountain = it,
            distanceKm = GeoMath.distanceKm(p.latitude, p.longitude, it.latitude, it.longitude),
            bearingDeg = GeoMath.bearingDeg(p.latitude, p.longitude, it.latitude, it.longitude),
        )
    }.sortedWith(displayPriority)

    private companion object {
        /** これ以上移動したら取り直す。取得済みの地域ならキャッシュから読むだけで通信しない。 */
        const val REFETCH_DISTANCE_KM = 1.0
    }
}
