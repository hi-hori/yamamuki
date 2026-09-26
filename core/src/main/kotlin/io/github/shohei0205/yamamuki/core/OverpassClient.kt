package io.github.shohei0205.yamamuki.core

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/** 山データの取得元。テストではフェイクに差し替える。 */
fun interface MountainRemoteSource {
    suspend fun fetchPeaks(box: BoundingBox): List<Mountain>
}

class OverpassException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * OSM Overpass API から natural=peak / natural=volcano の名前付きノードを取得する。
 * 最初のエンドポイントが失敗したら次のミラーを試す。
 */
class OverpassClient(
    private val httpClient: HttpClient,
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val userAgent: String = "yamamuki-android",
) : MountainRemoteSource {

    override suspend fun fetchPeaks(box: BoundingBox): List<Mountain> {
        val query = OverpassQuery.peaks(box)
        // どのエンドポイントがなぜ失敗したか追えるよう、すべての失敗を残す。
        val errors = mutableListOf<Throwable>()
        for (endpoint in endpoints) {
            try {
                val response = httpClient.submitForm(
                    url = endpoint,
                    formParameters = parameters { append("data", query) },
                ) {
                    header(HttpHeaders.UserAgent, userAgent)
                }
                if (!response.status.isSuccess()) {
                    errors += OverpassException("HTTP ${response.status.value} from $endpoint")
                    continue
                }
                return OverpassParser.parse(response.bodyAsText())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += OverpassException("${e::class.simpleName} from $endpoint", e)
            }
        }
        throw OverpassException("All Overpass endpoints failed", errors.lastOrNull()).apply {
            errors.dropLast(1).forEach(::addSuppressed)
        }
    }

    companion object {
        val DEFAULT_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
        )
    }
}

object OverpassQuery {
    fun peaks(box: BoundingBox, timeoutSec: Int = 60): String {
        val bbox = listOf(box.south, box.west, box.north, box.east)
            .joinToString(",") { String.format(Locale.US, "%.5f", it) }
        return """
            [out:json][timeout:$timeoutSec];
            (
              node["natural"="peak"]["name"]($bbox);
              node["natural"="volcano"]["name"]($bbox);
            );
            out body;
        """.trimIndent()
    }
}

object OverpassParser {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Response(val elements: List<Element> = emptyList())

    @Serializable
    private data class Element(
        val type: String,
        val id: Long,
        val lat: Double? = null,
        val lon: Double? = null,
        val tags: Map<String, String> = emptyMap(),
    )

    fun parse(body: String): List<Mountain> =
        json.decodeFromString<Response>(body).elements.mapNotNull { e ->
            if (e.type != "node" || e.lat == null || e.lon == null) return@mapNotNull null
            val name = (e.tags["name:ja"] ?: e.tags["name"])?.trim()
            if (name.isNullOrEmpty()) return@mapNotNull null
            Mountain(
                osmId = e.id,
                name = name,
                latitude = e.lat,
                longitude = e.lon,
                elevationM = parseElevation(e.tags["ele"]),
            )
        }.distinctBy { it.osmId }

    /**
     * ele タグを m 単位の数値にする。"3776", "3776 m", "3,776", "3776;3775", "12345 ft" などに対応。
     */
    fun parseElevation(raw: String?): Double? {
        if (raw == null) return null
        val first = raw.split(';').first().trim().lowercase(Locale.US)
        val match = Regex("""^(-?[\d,]*\.?\d+)\s*(m|meters?|metres?|ft|feet|')?$""").find(first)
            ?: return null
        val value = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        return when (match.groupValues[2]) {
            "ft", "feet", "'" -> value * 0.3048
            else -> value
        }
    }
}
