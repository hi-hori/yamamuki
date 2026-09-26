package io.github.shohei0205.yamamuki.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.shohei0205.yamamuki.core.Heading
import io.github.shohei0205.yamamuki.core.HeadingFilter
import io.github.shohei0205.yamamuki.core.NearbyMountain
import io.github.shohei0205.yamamuki.core.coordinateText
import io.github.shohei0205.yamamuki.core.distanceText
import io.github.shohei0205.yamamuki.core.elevationText
import io.github.shohei0205.yamamuki.sensor.locationUpdates
import io.github.shohei0205.yamamuki.sensor.magneticHeadingUpdates
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
                viewModel.onLocation(GeoPoint(it.latitude, it.longitude, it.altitude))
            }
        }
    }

    // センサーは磁北基準なので、現在地の偏角(日本ではおよそ西へ 7〜10°)を足して真北基準にする。
    val magneticHeading by remember(context) {
        val filter = HeadingFilter()
        magneticHeadingUpdates(context).map { filter.update(it) }
    }.collectAsStateWithLifecycle<Double?>(initialValue = null)
    val location = state.location
    val declination = remember(location) {
        location?.let {
            GeomagneticField(
                it.latitude.toFloat(), it.longitude.toFloat(), it.altitudeM.toFloat(), System.currentTimeMillis(),
            ).declination.toDouble()
        } ?: 0.0
    }
    val heading = magneticHeading?.let { Heading.normalize(it + declination) }

    // 選んだ山は ID で持ち、表示中の一覧から引く。歩いて現在地が変わると距離も更新される。
    var selectedId by remember { mutableStateOf<Long?>(null) }
    val selected = state.mountains.firstOrNull { it.mountain.osmId == selectedId }

    Box(
        Modifier
            .fillMaxSize()
            .background(DialBeige)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ -> viewModel.onZoom(zoom) }
            },
    ) {
        DialCanvas(
            headingDeg = heading ?: 0.0,
            mountains = state.mountains,
            rangeKm = state.rangeKm,
            modifier = Modifier.fillMaxSize(),
            onMountainTap = { selectedId = it.mountain.osmId },
        )
        Text(
            "© OpenStreetMap contributors",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        )

        if (!hasPermission) {
            PermissionRequest(
                onRequest = { permissionLauncher.launch(LOCATION_PERMISSIONS) },
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            StatusLine(
                message = statusMessage(state, headingAvailable = heading != null),
                showRetry = state.offline && !state.loading,
                onRetry = viewModel::retry,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 76.dp),
            )
        }
    }

    if (selected != null) {
        MountainDetailDialog(selected, onDismiss = { selectedId = null })
    }
}

/** タップした山の詳細。 */
@Composable
private fun MountainDetailDialog(nearby: NearbyMountain, onDismiss: () -> Unit) {
    val m = nearby.mountain
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        title = { Text(m.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("標高", m.elevationText())
                DetailRow("緯度経度", m.coordinateText())
                DetailRow("現在地からの距離", distanceText(nearby.distanceKm))
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
    state.location == null -> "現在地を取得しています…"
    !headingAvailable -> "方位センサーの値を待っています…"
    state.loading -> "山のデータを取得中…"
    state.offline && state.incomplete -> "通信できず、この付近の山データがありません"
    state.offline -> "オフライン: 保存済みのデータを表示中"
    else -> null
}

@Composable
private fun StatusLine(message: String?, showRetry: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (message == null) return
    Row(
        modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall)
        if (showRetry) TextButton(onClick = onRetry) { Text("再取得") }
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
            "現在地のまわりの山を表示するため、位置情報の利用を許可してください。\n" +
                "許可のダイアログが出ない場合は、設定アプリからこのアプリに位置情報を許可してください。",
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest) { Text("許可する") }
    }
}
