package io.github.shohei0205.yamamuki.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import io.github.shohei0205.yamamuki.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

data class TerrainImage(val bitmap: Bitmap, val key: TerrainKey)

/** 更新はAPK内のハッシュで判定。端末からデータ取得の通信は行わない。 */
class TerrainData(private val context: Context) {
    private val mutex = Mutex()
    private var zip: ZipFile? = null
    private var maxZoom = 11
    private val bitmaps = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    private suspend fun initialize() = mutex.withLock {
        if (zip != null) return@withLock
        val expected = context.assets.open("offline/terrain.sha256").bufferedReader().use { it.readText().trim() }
        require(expected.matches(Regex("[0-9a-f]{64}")))
        val dir = File(context.noBackupFilesDir, "offline").apply { mkdirs() }
        val file = File(dir, "terrain.zip")
        val marker = File(dir, "terrain.sha256")
        if (!file.exists() || !marker.exists() || marker.readText() != expected) {
            val temp = File(dir, "terrain.tmp")
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open("offline/terrain.zip").use { input -> temp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val length = input.read(buffer)
                    if (length < 0) break
                    digest.update(buffer, 0, length)
                    output.write(buffer, 0, length)
                }
            } }
            check(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "内蔵データが破損しています" }
            check(temp.renameTo(file)) { "内蔵データを展開できません" }
            marker.writeText(expected)
        }
        val opened = ZipFile(file)
        try {
            val manifest = JSONObject(opened.getInputStream(opened.getEntry("manifest.json")).bufferedReader().use { it.readText() })
            check(manifest.getInt("format_version") == 1)
            val terrain = manifest.getJSONObject("terrain")
            check(terrain.optString("encoding") == "hillshade-webp" && terrain.optInt("tile_size") == 512) {
                "512pxの陰影地形パックが必要です"
            }
            check(terrain.getInt("min_zoom") == 10 && terrain.getInt("max_zoom") == 12)
            maxZoom = terrain.getInt("max_zoom")
            zip = opened
        } catch (e: Exception) { opened.close(); throw e }
    }

    suspend fun terrain(latitude: Double, longitude: Double, rangeKm: Double): List<TerrainImage> = withContext(Dispatchers.IO) {
        initialize()
        val z = minOf(terrainZoom(rangeKm), maxZoom)
        TerrainGeometry.covering(BoundingBox.around(latitude, longitude, rangeKm * 1.4), z).mapNotNull { key ->
            coroutineContext.ensureActive()
            val archive = checkNotNull(zip)
            val name = key.entry
            val entry = archive.getEntry(name) ?: return@mapNotNull null
            val bitmap = bitmaps.get(name) ?: archive.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
                ?.also { it.prepareToDraw(); bitmaps.put(name, it) } ?: error("陰影地形を読み込めません")
            TerrainImage(bitmap, key)
        }
    }

    /** 地形に合成したOSM河川の機械可読データとライセンスを保存する。 */
    suspend fun exportRivers(uri: Uri) = withContext(Dispatchers.IO) {
        initialize()
        checkNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
            java.util.zip.ZipOutputStream(output).use { target ->
                for (name in listOf("rivers.geojson.gz", "NOTICE.txt", "ODbL-1.0.txt", "manifest.json")) {
                    target.putNextEntry(java.util.zip.ZipEntry(name))
                    checkNotNull(zip).getInputStream(checkNotNull(zip).getEntry(name)).use { it.copyTo(target) }
                    target.closeEntry()
                }
            }
        }
    }
}
