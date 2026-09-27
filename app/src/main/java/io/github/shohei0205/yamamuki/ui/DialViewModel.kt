package io.github.shohei0205.yamamuki.ui

import android.app.Application
import io.github.shohei0205.yamamuki.data.TerrainImage
import kotlinx.coroutines.ensureActive
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.shohei0205.yamamuki.YamamukiApp
import io.github.shohei0205.yamamuki.core.*
import io.github.shohei0205.yamamuki.data.BundledInfo
import io.github.shohei0205.yamamuki.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double = 0.0,
    val mslAltitudeM: Double? = null,
)

data class DialUiState(
    val location: GeoPoint? = null,
    val gpsLocation: GeoPoint? = null,
    val exploring: Boolean = false,
    val observerLocation: GeoPoint? = null,
    val lockedHeading: Double? = null,
    val mountains: List<NearbyMountain> = emptyList(),
    val summit: NearbyMountain? = null,
    val rangeKm: Double = DialGeometry.DEFAULT_RANGE_KM,
    val terrain: List<TerrainImage> = emptyList(),
    val terrainLoading: Boolean = false,
    val terrainError: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val dataInfo: BundledInfo? = null,
    val exportMessage: String? = null,
    val settings: Settings = Settings(),
)

/** 現在地と表示範囲に応じて、APK内のデータだけを読む。 */
class DialViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as YamamukiApp
    private val data = app.bundledData
    private val mutableState = MutableStateFlow(app.settings.settings.value.let {
        DialUiState(settings = it, rangeKm = it.initialRangeKm.toDouble())
    })
    val state = mutableState.asStateFlow()
    private var terrainJob: Job? = null
    private var terrainCenter: GeoPoint? = null
    private var terrainRange = 0.0
    private var peaks: List<Mountain> = emptyList()
    private var loadJob: Job? = null
    private var loadedCenter: GeoPoint? = null
    private var loadedRange = 0.0
    private var requestedCenter: GeoPoint? = null
    private var requestedRange = 0.0

    init {
        viewModelScope.launch {
            try { mutableState.update { it.copy(dataInfo = data.info()) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(error = "内蔵データを読み込めません: ${e.message}") } }
        }
    }

    fun onLocation(newPoint: GeoPoint) {
        val point = if (newPoint.mslAltitudeM == null) newPoint.copy(mslAltitudeM = state.value.gpsLocation?.mslAltitudeM) else newPoint
        mutableState.update { if (it.exploring) it.copy(gpsLocation = point) else it.copy(gpsLocation = point, location = point, observerLocation = point).withPeaks(point) }
        if (state.value.exploring) return
        loadTerrain()
        val center = if (loadJob?.isActive == true) requestedCenter else loadedCenter
        if (center == null || GeoMath.distanceKm(center.latitude, center.longitude, point.latitude, point.longitude) > 0.2) load()
    }

    fun onZoom(zoom: Float) {
        val old = state.value.rangeKm
        val next = DialGeometry.zoomedRange(old, zoom)
        mutableState.update { it.copy(rangeKm = next) }
        loadTerrain()
        val coverage = if (loadJob?.isActive == true) requestedRange else loadedRange
        if (next > coverage * 1.2) load()
    }

    fun retry() { load(); loadTerrain(force = true) }

    fun onPan(dxPx: Float, dyPx: Float, chartHeightPx: Float, headingDeg: Double) {
        val here = state.value.location ?: return
        val next = PanGeometry.drag(MapCenter(here.latitude, here.longitude), dxPx.toDouble(), dyPx.toDouble(),
            chartHeightPx / state.value.rangeKm, state.value.lockedHeading ?: headingDeg)
        if (next.latitude == here.latitude && next.longitude == here.longitude) return
        val point = GeoPoint(next.latitude, next.longitude)
        mutableState.update { it.copy(location = point, exploring = true,
            observerLocation = it.observerLocation ?: here, lockedHeading = it.lockedHeading ?: headingDeg) }
        loadTerrain()
        val center = if (loadJob?.isActive == true) requestedCenter else loadedCenter
        if (center == null || GeoMath.distanceKm(center.latitude, center.longitude, point.latitude, point.longitude) > 0.2) load()
    }

    fun resetCenter() {
        val here = state.value.gpsLocation ?: return
        mutableState.update { it.copy(location = here, observerLocation = here, exploring = false, lockedHeading = null).withPeaks(here) }
        load()
        loadTerrain(force = true)
    }

    fun onHeadingSwipe(dxPx: Float, widthPx: Float, headingDeg: Double) {
        if (!dxPx.isFinite() || dxPx == 0f || widthPx <= 0) return
        mutableState.update {
            val here = it.location
            if (here == null) it else it.copy(exploring = true,
                observerLocation = it.observerLocation ?: here,
                lockedHeading = DialGeometry.swipedHeading(it.lockedHeading ?: headingDeg,
                    dxPx.toDouble(), widthPx.toDouble()))
        }
    }

    fun onTransform(zoom: Float, rotationDeg: Float, previousMidpoint: PlanOffset,
        midpoint: PlanOffset, chartHeightPx: Float) {
        val current = state.value
        val heading = current.lockedHeading
        if (!current.exploring || heading == null) { onZoom(zoom); return }
        val observer = current.observerLocation ?: return
        val viewport = current.location ?: return
        if (!rotationDeg.isFinite() || chartHeightPx <= 0) return
        val range = DialGeometry.zoomedRange(current.rangeKm, zoom)
        val nextHeading = Heading.normalize(heading - rotationDeg)
        val next = PanGeometry.transformViewport(MapCenter(observer.latitude, observer.longitude),
            MapCenter(viewport.latitude, viewport.longitude), previousMidpoint, midpoint,
            chartHeightPx / current.rangeKm, chartHeightPx / range, heading, nextHeading)
        val point = GeoPoint(next.latitude, next.longitude)
        mutableState.update { it.copy(location = point, rangeKm = range, lockedHeading = nextHeading) }
        loadTerrain()
        val center = if (loadJob?.isActive == true) requestedCenter else loadedCenter
        val coverage = if (loadJob?.isActive == true) requestedRange else loadedRange
        if (center == null || GeoMath.distanceKm(center.latitude, center.longitude, point.latitude, point.longitude) > 0.2 ||
            range > coverage * 1.2) load()
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        val before = state.value.settings
        app.settings.update(transform)
        val after = app.settings.settings.value
        mutableState.update {
            val updated = it.copy(settings = after, rangeKm = if (before.initialRangeKm != after.initialRangeKm) after.initialRangeKm.toDouble() else it.rangeKm)
            updated.observerLocation?.let { p -> updated.withPeaks(p) } ?: updated
        }
        if (before.initialRangeKm != after.initialRangeKm) load()
        if (before.showTerrain != after.showTerrain || before.initialRangeKm != after.initialRangeKm) loadTerrain(force = true)
    }

    fun exportPeaks(uri: Uri) {
        viewModelScope.launch {
            mutableState.update { it.copy(exportMessage = "保存中…") }
            try {
                data.exportPeaks(uri)
                mutableState.update { it.copy(exportMessage = "山頂データとライセンスを保存しました") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(exportMessage = "保存できませんでした: ${e.message}") } }
        }
    }

    private fun loadTerrain(force: Boolean = false) {
        if (!state.value.settings.showTerrain) {
            terrainJob?.cancel(); terrainCenter = null; terrainRange = 0.0
            mutableState.update { it.copy(terrain = emptyList(), terrainLoading = false, terrainError = null) }
            return
        }
        val here = state.value.location ?: return
        val range = state.value.rangeKm
        val center = terrainCenter
        if (!force && center != null &&
            GeoMath.distanceKm(center.latitude, center.longitude, here.latitude, here.longitude) <= 0.2 &&
            terrainZoom(range) == terrainZoom(terrainRange) && range <= terrainRange * 1.2) return
        val refining = terrainRange > 0 && terrainZoom(range) > terrainZoom(terrainRange)
        terrainJob?.cancel()
        terrainCenter = here; terrainRange = range
        terrainJob = viewModelScope.launch {
            mutableState.update { it.copy(terrainLoading = true, terrainError = null) }
            try {
                if (!refining) delay(80)
                val tiles = app.terrainData.terrain(here.latitude, here.longitude, range)
                coroutineContext.ensureActive()
                mutableState.update { it.copy(terrain = tiles, terrainLoading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                terrainCenter = null
                mutableState.update { it.copy(terrainLoading = false, terrainError = "地形を読み込めません: ${e.message}") }
            }
        }
    }

    fun exportRivers(uri: Uri) {
        viewModelScope.launch {
            mutableState.update { it.copy(exportMessage = "保存中…") }
            try {
                app.terrainData.exportRivers(uri)
                mutableState.update { it.copy(exportMessage = "河川データとライセンスを保存しました") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(exportMessage = "保存できませんでした: ${e.message}") } }
        }
    }

    private fun load() {
        val here = state.value.location ?: return
        val range = state.value.rangeKm
        requestedCenter = here
        requestedRange = range
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            delay(80)
            try {
                peaks = data.nearby(here.latitude, here.longitude, maxOf(DialGeometry.fetchRadiusKm(range), range * 1.4)).map { it.mountain }
                mutableState.update { it.withPeaks(it.observerLocation ?: here) }
                loadedCenter = here
                loadedRange = range
                val info = data.info()
                mutableState.update { it.copy(loading = false, dataInfo = info) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                loadedCenter = null
                mutableState.update { it.copy(loading = false, error = "内蔵データを読み込めません: ${e.message}") }
            }
        }
    }

    private fun DialUiState.withPeaks(p: GeoPoint): DialUiState {
        val all = peaks.map { it.seenFrom(p.latitude, p.longitude) }
        val summit = summitAt(all)
        return copy(summit = summit, mountains = all.filter {
            it.mountain.osmId != summit?.mountain?.osmId && it.mountain.meetsMinElevation(settings.minElevationM)
        }.sortedWith(displayPriority))
    }
}
