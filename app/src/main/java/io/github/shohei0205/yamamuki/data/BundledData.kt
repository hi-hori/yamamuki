package io.github.shohei0205.yamamuki.data

import android.content.Context
import android.net.Uri
import io.github.shohei0205.yamamuki.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

data class BundledInfo(val peakCount: Int, val osmDate: String, val inputDate: String, val bytes: Long)

/** 更新はAPK内のハッシュで判定。端末からデータ取得の通信は行わない。 */
class BundledData(private val context: Context) {
    private val mutex = Mutex()
    private var zip: ZipFile? = null
    private var mountains: Map<Tile, List<Mountain>> = emptyMap()
    private var info: BundledInfo? = null
    private suspend fun initialize() = mutex.withLock {
        if (zip != null) return@withLock
        val expected = context.assets.open("offline/peaks.sha256").bufferedReader().use { it.readText().trim() }
        require(expected.matches(Regex("[0-9a-f]{64}")))
        val dir = File(context.noBackupFilesDir, "offline").apply { mkdirs() }
        val file = File(dir, "peaks.zip")
        val marker = File(dir, "sha256")
        if (!file.exists() || !marker.exists() || marker.readText() != expected) {
            val temp = File(dir, "peaks.tmp")
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open("offline/peaks.zip").use { input -> temp.outputStream().use { output ->
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
            val peaks = opened.getInputStream(opened.getEntry("peaks.csv.gz")).use { input ->
                GZIPInputStream(input).bufferedReader().use { BundledPeaks.parse(it.readText()) }
            }
            check(peaks.size == manifest.getJSONObject("peaks").getInt("count"))
            mountains = peaks.groupBy { Tile.of(it.latitude, it.longitude) }
            info = BundledInfo(peaks.size, manifest.getJSONObject("peaks").getString("osm_timestamp").take(10),
                manifest.getString("input_last_fetched").take(10), file.length())
            zip = opened
        } catch (e: Exception) { opened.close(); throw e }
    }

    suspend fun info(): BundledInfo = withContext(Dispatchers.IO) { initialize(); checkNotNull(info) }

    suspend fun nearby(latitude: Double, longitude: Double, radiusKm: Double): List<NearbyMountain> = withContext(Dispatchers.IO) {
        initialize()
        Tile.covering(BoundingBox.around(latitude, longitude, radiusKm)).flatMap { mountains[it].orEmpty() }
            .map { it.seenFrom(latitude, longitude) }
            .filter { it.distanceKm <= radiusKm }
    }

    /** 利用者が内蔵版と同一の山頂CSV＋ライセンスを自由に取り出せる。 */
    suspend fun exportPeaks(uri: Uri) = withContext(Dispatchers.IO) {
        initialize()
        checkNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
            java.util.zip.ZipOutputStream(output).use { target ->
                for (name in listOf("peaks.csv.gz", "NOTICE.txt", "ODbL-1.0.txt", "manifest.json")) {
                    target.putNextEntry(java.util.zip.ZipEntry(name))
                    checkNotNull(zip).getInputStream(checkNotNull(zip).getEntry(name)).use { it.copyTo(target) }
                    target.closeEntry()
                }
            }
        }
    }
}
