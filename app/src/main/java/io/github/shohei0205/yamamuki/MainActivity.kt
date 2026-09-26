package io.github.shohei0205.yamamuki

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.shohei0205.yamamuki.core.MountainQueryResult
import io.github.shohei0205.yamamuki.core.MountainRepository
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as YamamukiApp).mountainRepository
        setContent {
            MaterialTheme {
                MountainListScreen(repository)
            }
        }
    }
}

/**
 * データ取得の動作確認用の仮画面。現在地と方位盤の UI は次の作業で置き換える。
 * 地点は大月駅付近に固定している。
 */
@Composable
private fun MountainListScreen(repository: MountainRepository) {
    val latitude = 35.61
    val longitude = 138.94
    var reload by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<MountainQueryResult?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(reload) {
        loading = true
        result = repository.mountainsAround(latitude, longitude, radiusKm = 50.0, forceRefresh = reload > 0)
        loading = false
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("大月駅付近から半径50km", style = MaterialTheme.typography.titleMedium)
            val r = result
            val status = when {
                loading -> "取得中…"
                r == null -> ""
                r.error != null && r.incomplete -> "取得失敗(未取得の範囲あり): ${r.error?.message}"
                r.error != null -> "オフライン: キャッシュを表示中"
                else -> "${r.mountains.size} 件"
            }
            Text(status)
            Button(onClick = { reload++ }, enabled = !loading) { Text("再取得") }
            LazyColumn {
                items(r?.mountains.orEmpty(), key = { it.mountain.osmId }) { m ->
                    val ele = m.mountain.elevationM?.let { String.format(Locale.US, " (%.0fm)", it) }.orEmpty()
                    Text(
                        String.format(
                            Locale.US, "%s%s  %.1fkm  %.0f°", m.mountain.name, ele, m.distanceKm, m.bearingDeg,
                        ),
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}
