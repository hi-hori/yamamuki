package io.github.shohei0205.yamamuki.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.shohei0205.yamamuki.core.Heading
import io.github.shohei0205.yamamuki.core.PlanOffset
import io.github.shohei0205.yamamuki.core.HeadingFilter
import io.github.shohei0205.yamamuki.core.NearbyMountain
import io.github.shohei0205.yamamuki.core.coordinateText
import io.github.shohei0205.yamamuki.core.distanceText
import io.github.shohei0205.yamamuki.core.elevationText
import io.github.shohei0205.yamamuki.sensor.locationUpdates
import io.github.shohei0205.yamamuki.sensor.magneticHeadingUpdates
import io.github.shohei0205.yamamuki.sensor.mslAltitudeM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/** 方位盤の画面。位置情報の権限、現在地、方位センサーをつないで [DialCanvas] に渡す。 */
@Composable
fun DialScreen(viewModel: DialViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    var hasPermission by remember {
        mutableStateOf(
            LOCATION_PERMISSIONS.any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> hasPermission = granted.values.any { it } }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(LOCATION_PERMISSIONS)
    }
    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            locationUpdates(context).collect {
                val msl = withContext(Dispatchers.IO) { mslAltitudeM(context, it) }
                viewModel.onLocation(GeoPoint(it.latitude, it.longitude, it.altitude, msl))
            }
        }
    }

    // センサーは磁北基準なので、現在地の偏角(日本ではおよそ西へ 7〜10°)を足して真北基準にする。
    val magneticHeading by remember(context) {
        val filter = HeadingFilter()
        magneticHeadingUpdates(context).map { filter.update(it) }
    }.collectAsStateWithLifecycle<Double?>(initialValue = null)
    val location = state.gpsLocation
    val declination = remember(location) {
        location?.let {
            GeomagneticField(
                it.latitude.toFloat(), it.longitude.toFloat(), it.altitudeM.toFloat(), System.currentTimeMillis(),
            ).declination.toDouble()
        } ?: 0.0
    }
    val compassHeading = magneticHeading?.let { Heading.normalize(it + declination) }
    val heading = state.lockedHeading ?: compassHeading
    val currentHeading by rememberUpdatedState(heading ?: 0.0)

    // 選んだ山は ID で持ち、表示中の一覧から引く。歩いて現在地が変わると距離も更新される。
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val selected = state.mountains.firstOrNull { it.mountain.osmId == selectedId }
        ?: state.summit?.takeIf { it.mountain.osmId == selectedId }
    // 取り直しで一覧から消えたら選択も解く。残しておくと、その山が一覧に戻ったときにダイアログが勝手に開く。
    val selectionLost = selectedId != null && selected == null
    LaunchedEffect(selectionLost) {
        if (selectionLost) selectedId = null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(DialBeige)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .pointerInput(showSettings) {
                if (showSettings) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val headingGesture = down.position.y < 76.dp.toPx()
                    var multiTouch = false
                    var dragging = false
                    var pendingPan = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val count = event.changes.count { it.pressed }
                        if (count == 1 && !multiTouch) {
                            val pan = event.calculatePan()
                            pendingPan += pan
                            val distance = if (headingGesture) kotlin.math.abs(pendingPan.x) else pendingPan.getDistance()
                            val started = !dragging && distance > viewConfiguration.touchSlop
                            if (started) dragging = true
                            if (dragging) {
                                val delta = if (started) pendingPan else pan
                                if (headingGesture) viewModel.onHeadingSwipe(delta.x, size.width.toFloat(), currentHeading)
                                else viewModel.onPan(delta.x, delta.y, size.height - 128.dp.toPx(), currentHeading)
                                event.changes.forEach { it.consume() }
                            }
                        } else if (count >= 2) {
                            multiTouch = true
                            val zoom = event.calculateZoom()
                            if (headingGesture) {
                                // A gesture starting on the tape never turns into a map transform.
                            } else if (count == 2 && event.changes.count { it.pressed && it.previousPressed } == 2) {
                                val origin = Offset(size.width / 2f, size.height - 52.dp.toPx())
                                val previous = event.calculateCentroid(useCurrent = false) - origin
                                val current = event.calculateCentroid(useCurrent = true) - origin
                                viewModel.onTransform(zoom, event.calculateRotation(),
                                    PlanOffset(previous.x.toDouble(), previous.y.toDouble()),
                                    PlanOffset(current.x.toDouble(), current.y.toDouble()), size.height - 128.dp.toPx())
                            } else if (zoom != 1f) viewModel.onZoom(zoom)
                            event.changes.forEach { it.consume() }
                        } else if (multiTouch || dragging) {
                            // Finish a pinch without turning the remaining finger into a drag/tap.
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        DialCanvas(
            headingDeg = heading ?: 0.0,
            compassHeadingDeg = compassHeading ?: heading ?: 0.0,
            mountains = state.mountains,
            rangeKm = state.rangeKm,
            modifier = Modifier.fillMaxSize(),
            onMountainTap = { selectedId = it.mountain.osmId },
            summit = state.summit,
            altitudeM = state.observerLocation?.mslAltitudeM,
            maxPeaks = state.settings.maxPeaks,
            textScale = state.settings.textScale,
            latitude = state.observerLocation?.latitude,
            longitude = state.observerLocation?.longitude,
            viewportLatitude = state.location?.latitude,
            viewportLongitude = state.location?.longitude,
        )
        Text(
            "© OpenStreetMap contributors",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomEnd).clickable { showSettings = true }.padding(8.dp),
        )

        if (!hasPermission) {
            PermissionRequest(
                onRequest = { permissionLauncher.launch(LOCATION_PERMISSIONS) },
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Column(Modifier.align(Alignment.TopCenter).padding(top = 76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (state.exploring) {
                    Row(Modifier.background(DialBeige).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("手動移動・2本指で地図を回転", style = MaterialTheme.typography.labelMedium)
                            state.location?.let { center ->
                                Text(String.format(java.util.Locale.US, "%.4f, %.4f", center.latitude, center.longitude),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        TextButton(onClick = viewModel::resetCenter) { Text("現在地に戻る") }
                    }
                }
                StatusLine(
                    message = statusMessage(state, headingAvailable = compassHeading != null),
                    actionLabel = if (state.error != null && !state.loading) "再読込" else null,
                    onAction = viewModel::retry,
                )
            }
        }

        // 屋外で押しやすい大きさの設定ボタン。
        Row(
            Modifier.align(Alignment.BottomStart).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalIconButton(onClick = { showSettings = true }, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = "設定", Modifier.size(28.dp))
            }
        }

        if (showSettings) {
            SettingsScreen(
                settings = state.settings,
                dataInfo = state.dataInfo,
                onSettingsChange = viewModel::updateSettings,
                exportMessage = state.exportMessage,
                onExport = viewModel::exportPeaks,
                onClose = { showSettings = false },
            )
        }
    }

    // 屋外で山を見比べている間に画面が消えないようにする(設定で選んだときだけ)。
    val view = LocalView.current
    val keepScreenOn = state.settings.keepScreenOn
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    if (selected != null && !showSettings) {
        MountainDetailDialog(selected, fromCenter = state.exploring, onDismiss = { selectedId = null })
    }
}

/** タップした山の詳細。 */
@Composable
private fun MountainDetailDialog(nearby: NearbyMountain, fromCenter: Boolean, onDismiss: () -> Unit) {
    val m = nearby.mountain
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        title = { Text(m.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("標高", m.elevationText())
                DetailRow("緯度経度", m.coordinateText())
                DetailRow(if (fromCenter) "双眼鏡の位置からの距離" else "現在地からの距離", distanceText(nearby.distanceKm))
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun statusMessage(state: DialUiState, headingAvailable: Boolean): String? = when {
    state.error != null -> state.error
    state.location == null -> "現在地を取得しています…"
    !headingAvailable -> "方位センサーの値を待っています…"
    state.loading -> "内蔵データを読み込み中…"
    else -> null
}
@Composable
private fun StatusLine(
    message: String?,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message == null) return
    Row(
        modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall)
        if (actionLabel != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "周辺の山を表示するには、位置情報の許可が必要です。\n" +
                "許可の画面が出ないときは、端末の設定アプリから許可してください。",
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest) { Text("許可する") }
    }
}
