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

data class RiverBatch(val path: android.graphics.Path, val bounds: RiverBounds)

data class WaterBatch(val path: android.graphics.Path, val bounds: RiverBounds, val river: Boolean, val sea: Boolean = false)

data class RiverFrame(val water: List<WaterBatch>, val batches: List<RiverBatch>, val latitude: Double, val longitude: Double, val widthKm: Double)

data class TerrainImage(val bitmap: Bitmap, val key: TerrainKey)

/** 更新はAPK内のハッシュで判定。端末からデータ取得の通信は行わない。 */
class TerrainData(private val context: Context) {
    private val mutex = Mutex()
    private var zip: ZipFile? = null
    private var maxZoom = 11
    private val riverCache = object : LruCache<String, List<List<PlanOffset>>>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: List<List<PlanOffset>>) = 64 + value.sumOf { 32 + it.size * 32 }
    }
    private val waterCache = object : LruCache<String, List<WaterPolygon>>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: List<WaterPolygon>) = 64 + value.sumOf { p ->
            32 + p.rings.sumOf { 32 + it.size * 32 }
        }
    }
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
            check(!terrain.getBoolean("rivers_baked_in") &&
                manifest.getJSONObject("river_vectors").getString("encoding") == "RIV1") {
                "河川を分離した地形パックが必要です"
            }
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

    suspend fun rivers(latitude: Double, longitude: Double, observerLatitude: Double,
        observerLongitude: Double, rangeKm: Double, showRivers: Boolean, showLakes: Boolean): RiverFrame = withContext(Dispatchers.IO) {
        initialize()
        val batches = mutableListOf<RiverBatch>()
        val water = mutableListOf<WaterBatch>()
        val archive = checkNotNull(zip)
        val keys = TerrainGeometry.covering(BoundingBox.around(latitude, longitude, rangeKm * 1.4), terrainZoom(rangeKm))
        val covered = keys.any { archive.getEntry("sea/${it.zoom}/${it.x}/${it.y}.wat") != null }
        for (key in keys) {
            coroutineContext.ensureActive()
            for (layer in listOf("sea", "water")) {
                val sea = layer == "sea"
                val waterName = "$layer/${key.zoom}/${key.x}/${key.y}.wat"
                val waterEntry = archive.getEntry(waterName)
                if (waterEntry != null || (sea && covered)) {
                    // Match the sea background beyond land tiles within bundled coverage.
                    val polygons = if (waterEntry == null) listOf(WaterPolygon(true, listOf(listOf(
                        PlanOffset(0.0, 0.0), PlanOffset(1.0, 0.0), PlanOffset(1.0, 1.0), PlanOffset(0.0, 1.0)))))
                    else waterCache.get(waterName) ?: archive.getInputStream(waterEntry).use {
                        WaterGeometry.decode(it.readBytes())
                    }.also { waterCache.put(waterName, it) }
                    val waterPaths = mutableMapOf<Boolean, android.graphics.Path>()
                    for (polygon in polygons) {
                        if (!sea && (if (polygon.river) !showRivers else !showLakes)) continue
                        coroutineContext.ensureActive()
                        val path = waterPaths.getOrPut(polygon.river) {
                            android.graphics.Path().apply { fillType = android.graphics.Path.FillType.EVEN_ODD }
                        }
                        for (ring in polygon.rings) {
                            ring.forEachIndexed { i, p ->
                                val point = RiverGeometry.project(key, p, observerLatitude, observerLongitude)
                                if (i == 0) path.moveTo(point.x.toFloat(), -point.y.toFloat())
                                else path.lineTo(point.x.toFloat(), -point.y.toFloat())
                            }
                            path.close()
                        }
                    }
                    for ((river, path) in waterPaths) {
                        val bounds = android.graphics.RectF()
                        path.computeBounds(bounds, true)
                        water += WaterBatch(path, RiverBounds(bounds.left.toDouble(), bounds.top.toDouble(),
                            bounds.right.toDouble(), bounds.bottom.toDouble()), river, sea)
                    }
                }
            }
            if (!showRivers) continue
            val name = "rivers/${key.zoom}/${key.x}/${key.y}.riv"
            val entry = archive.getEntry(name) ?: continue
            val lines = riverCache.get(name) ?: run {
                archive.getInputStream(entry).use { RiverGeometry.decode(it.readBytes()) }.also { riverCache.put(name, it) }
            }
            val paths = mutableMapOf<Int, android.graphics.Path>()
            val divisions = if (key.zoom == 12) 4 else 2
            for (line in lines) {
                coroutineContext.ensureActive()
                val path = paths.getOrPut(RiverGeometry.bucket(line, divisions)) { android.graphics.Path() }
                line.forEachIndexed { i, p ->
                    val point = RiverGeometry.project(key, p, observerLatitude, observerLongitude)
                    if (i == 0) path.moveTo(point.x.toFloat(), -point.y.toFloat())
                    else path.lineTo(point.x.toFloat(), -point.y.toFloat())
                }
            }
            for (path in paths.values) {
                val bounds = android.graphics.RectF()
                path.computeBounds(bounds, true)
                batches += RiverBatch(path, RiverBounds(bounds.left.toDouble(), bounds.top.toDouble(),
                    bounds.right.toDouble(), bounds.bottom.toDouble()))
            }
        }
        val widthKm = 40075.0166856 * kotlin.math.cos(Math.toRadians(latitude)) / (1 shl terrainZoom(rangeKm)) / 512 * 2.8
        RiverFrame(water, batches, observerLatitude, observerLongitude, widthKm)
    }

    /** OSM河川の機械可読データとライセンスを保存する。 */
    suspend fun exportRivers(uri: Uri) = withContext(Dispatchers.IO) {
        initialize()
        checkNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
            java.util.zip.ZipOutputStream(output).use { target ->
                for (name in listOf("rivers.geojson.gz", "water.geojson.gz", "NOTICE.txt", "ODbL-1.0.txt", "manifest.json")) {
                    val entry = checkNotNull(zip).getEntry(name) ?: continue
                    target.putNextEntry(java.util.zip.ZipEntry(name))
                    checkNotNull(zip).getInputStream(entry).use { it.copyTo(target) }
                    target.closeEntry()
                }
            }
        }
    }
}
