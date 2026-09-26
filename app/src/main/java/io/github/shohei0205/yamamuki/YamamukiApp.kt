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
            install(HttpTimeout) {
                requestTimeoutMillis = 90_000
            }
        }
        MountainRepository(
            remote = OverpassClient(http, userAgent = "yamamuki-android/0.1 (+https://github.com/shohei0205/yamamuki)"),
            cache = RoomMountainCache(MountainDatabase.create(this).mountainDao()),
        )
    }
}
