package io.github.shohei0205.yamamuki

import android.app.Application
import io.github.shohei0205.yamamuki.core.MountainRepository
import io.github.shohei0205.yamamuki.core.OverpassClient
import io.github.shohei0205.yamamuki.data.MountainDatabase
import io.github.shohei0205.yamamuki.data.RoomMountainCache
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

class YamamukiApp : Application() {

    val mountainRepository: MountainRepository by lazy {
        val http = HttpClient(OkHttp) {
            // Overpass は集計が終わるまで応答を返さず 20 秒以上かかることがある。
            // socketTimeout を指定しないと OkHttp 既定の 10 秒で読み込みが打ち切られる。
            install(HttpTimeout) {
                requestTimeoutMillis = 90_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 75_000
            }
        }
        MountainRepository(
            remote = OverpassClient(http, userAgent = "yamamuki-android/0.1 (+https://github.com/shohei0205/yamamuki)"),
            cache = RoomMountainCache(MountainDatabase.create(this).mountainDao()),
        )
    }
}
