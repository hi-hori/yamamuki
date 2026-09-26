package io.github.shohei0205.yamamuki.core

import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MountainRepositoryTest {
    // 大月駅付近から富士山(約 34km)と、遠くの山(範囲外)を返すフェイク
    private val here = 35.61 to 138.94
    private val fuji = Mountain(1, "富士山", 35.3606, 138.7274, 3776.0)
    private val near = Mountain(2, "岩殿山", 35.62, 138.96, 634.0)
    private val far = Mountain(3, "遠い山", 36.4, 138.94, 2000.0)

    private class FakeRemote(var peaks: List<Mountain>) : MountainRemoteSource {
        var calls = mutableListOf<BoundingBox>()
        var fail = false
        override suspend fun fetchPeaks(box: BoundingBox): List<Mountain> {
            calls += box
            if (fail) throw IOException("offline")
            return peaks.filter { box.contains(it.latitude, it.longitude) }
        }
    }

    private var now = 1_000_000L

    private fun repo(remote: FakeRemote, cache: MountainCache) =
        MountainRepository(remote, cache, maxAgeMillis = 1000, clock = { now })

    @Test
    fun fetchesThenServesFromCache() = runTest {
        val remote = FakeRemote(listOf(fuji, near, far))
        val cache = InMemoryMountainCache()
        val repo = repo(remote, cache)

        val first = repo.mountainsAround(here.first, here.second, radiusKm = 50.0)
        assertEquals(listOf("岩殿山", "富士山"), first.mountains.map { it.mountain.name })
        assertFalse(first.incomplete)
        assertNull(first.error)
        assertEquals(1, remote.calls.size)

        val second = repo.mountainsAround(here.first, here.second, radiusKm = 50.0)
        assertEquals(first.mountains, second.mountains)
        assertEquals(1, remote.calls.size, "キャッシュ済みなら再取得しない")
    }

    @Test
    fun nearbyHasDistanceAndBearing() = runTest {
        val repo = repo(FakeRemote(listOf(fuji)), InMemoryMountainCache())
        val m = repo.mountainsAround(here.first, here.second, 50.0).mountains.single()
        assertEquals(33.8, m.distanceKm, 1.0)
        assertTrue(m.bearingDeg in 200.0..240.0, "富士山は南西方向: ${m.bearingDeg}")
    }

    @Test
    fun offlineUsesCacheAndReportsError() = runTest {
        val remote = FakeRemote(listOf(fuji, near))
        val cache = InMemoryMountainCache()
        val repo = repo(remote, cache)
        repo.mountainsAround(here.first, here.second, 50.0)

        remote.fail = true
        now += 5000 // キャッシュを古くして再取得を試みさせる
        val result = repo.mountainsAround(here.first, here.second, 50.0)

        assertEquals(2, result.mountains.size)
        assertFalse(result.incomplete)
        assertNotNull(result.error)
    }

    @Test
    fun offlineWithoutCacheIsIncomplete() = runTest {
        val remote = FakeRemote(listOf(fuji)).apply { fail = true }
        val result = repo(remote, InMemoryMountainCache()).mountainsAround(here.first, here.second, 50.0)
        assertTrue(result.mountains.isEmpty())
        assertTrue(result.incomplete)
        assertNotNull(result.error)
    }

    @Test
    fun onlyMissingTilesAreFetched() = runTest {
        val remote = FakeRemote(listOf(fuji, near))
        val cache = InMemoryMountainCache()
        val repo = repo(remote, cache)
        repo.mountainsAround(here.first, here.second, 10.0)
        val cachedTiles = cache.tiles.keys.toSet()

        repo.mountainsAround(here.first, here.second, 50.0)

        assertTrue(cachedTiles.isNotEmpty())
        assertEquals(2, remote.calls.size)
        // 2回目は拡大分だけを記録し、既存タイルの取得時刻は変えない
        cachedTiles.forEach { assertEquals(1_000_000L, cache.tiles[it]) }
        assertTrue(cache.tiles.size > cachedTiles.size)
    }

    @Test
    fun skipsNetworkWhenNotAllowed() = runTest {
        val remote = FakeRemote(listOf(fuji, near))
        val cache = InMemoryMountainCache()
        val repo = repo(remote, cache)

        val result = repo.mountainsAround(here.first, here.second, 50.0, allowNetwork = false)
        assertEquals(0, remote.calls.size)
        assertTrue(result.networkSkipped)
        assertTrue(result.incomplete)
        assertNull(result.error)

        // 取得済みなら通信を控えていても表示でき、控えた扱いにもならない。
        repo.mountainsAround(here.first, here.second, 50.0)
        val cached = repo.mountainsAround(here.first, here.second, 50.0, allowNetwork = false)
        assertEquals(listOf("岩殿山", "富士山"), cached.mountains.map { it.mountain.name })
        assertFalse(cached.networkSkipped)
    }

    @Test
    fun maxAgeCanBeGivenPerCall() = runTest {
        val remote = FakeRemote(listOf(fuji))
        val repo = repo(remote, InMemoryMountainCache())
        repo.mountainsAround(here.first, here.second, 50.0)
        now += 5000 // 既定の 1000ms は過ぎているが、10000ms 以内
        repo.mountainsAround(here.first, here.second, 50.0, maxAgeMillis = 10_000)
        assertEquals(1, remote.calls.size)
        repo.mountainsAround(here.first, here.second, 50.0)
        assertEquals(2, remote.calls.size)
    }
}
