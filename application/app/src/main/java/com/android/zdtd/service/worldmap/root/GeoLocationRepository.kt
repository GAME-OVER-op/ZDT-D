package com.android.zdtd.service.worldmap.root

import com.android.zdtd.service.worldmap.WorldMapStrings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class GeoLocation(
    val x: Float,
    val y: Float,
    val label: String,
)

enum class GeoResolveState {
    RESOLVED,
    PENDING,
    FAILED,
    SKIPPED,
}

class GeoLocationRepository(
    private val strings: WorldMapStrings,
) {

    private data class RemoteGeo(
        val latitude: Double,
        val longitude: Double,
        val countryCode: String,
        val city: String,
        val region: String,
    )

    private val cache = LinkedHashMap<String, GeoLocation>()
    private val retryAfter = LinkedHashMap<String, Long>()
    private val inFlight = mutableSetOf<String>()

    fun cached(ip: String): GeoLocation? = synchronized(this) { cache[ip] }

    fun stateFor(ip: String): GeoResolveState = synchronized(this) {
        when {
            cache.containsKey(ip) -> GeoResolveState.RESOLVED
            !shouldResolveIp(ip) -> GeoResolveState.SKIPPED
            inFlight.contains(ip) -> GeoResolveState.PENDING
            (retryAfter[ip] ?: 0L) > System.currentTimeMillis() -> GeoResolveState.FAILED
            else -> GeoResolveState.PENDING
        }
    }

    suspend fun resolveMissing(ips: Collection<String>): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val targets = synchronized(this@GeoLocationRepository) {
            ips.distinct()
                .asSequence()
                .filter(::shouldResolveIp)
                .filter { !cache.containsKey(it) }
                .filter { !inFlight.contains(it) }
                .filter { (retryAfter[it] ?: 0L) <= now }
                .take(MAX_BATCH_RESOLVE)
                .toList()
                .also { batch -> inFlight += batch }
        }

        var changed = false
        targets.forEach { ip ->
            val resolved = runCatching { fetchLocation(ip) }.getOrNull()
            val finishedAt = System.currentTimeMillis()
            synchronized(this@GeoLocationRepository) {
                if (resolved != null) {
                    cache[ip] = resolved
                    retryAfter.remove(ip)
                    changed = true
                } else {
                    retryAfter[ip] = finishedAt + RETRY_BACKOFF_MS
                }
                inFlight -= ip
                trimCacheLocked()
            }
        }
        changed
    }

    private fun trimCacheLocked() {
        while (cache.size > MAX_CACHE_SIZE) {
            val oldest = cache.entries.firstOrNull()?.key ?: break
            cache.remove(oldest)
        }
        while (retryAfter.size > MAX_CACHE_SIZE) {
            val oldest = retryAfter.entries.firstOrNull()?.key ?: break
            retryAfter.remove(oldest)
        }
    }

    private fun shouldResolveIp(ip: String): Boolean {
        if (ip.isBlank() || ip == "*" || ip == "0.0.0.0" || ip == "::") return false
        if (ip == "127.0.0.1" || ip == "::1") return false
        if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("169.254.")) return false
        if (ip.startsWith("fc") || ip.startsWith("fd") || ip.startsWith("fe80:")) return false
        val secondOctet = ip.split('.').getOrNull(1)?.toIntOrNull()
        if (ip.startsWith("172.") && secondOctet != null && secondOctet in 16..31) return false
        return ip.any { it == '.' || it == ':' }
    }

    private fun fetchLocation(ip: String): GeoLocation? {
        val remote = fetchRemoteGeo(ip) ?: return null
        val projected = LandSampleBank.projectLonLatToCanvas(
            longitude = remote.longitude.toFloat(),
            latitude = remote.latitude.toFloat(),
        )
        val refined = LandSampleBank.refineCanvasPoint(projected.first, projected.second)

        return GeoLocation(
            x = refined.first,
            y = refined.second,
            label = buildLabel(remote),
        )
    }

    private fun fetchRemoteGeo(ip: String): RemoteGeo? {
        return fetchFromIpWhoIs(ip) ?: fetchFromIpApiCo(ip)
    }

    private fun fetchFromIpWhoIs(ip: String): RemoteGeo? {
        val json = readJson("https://ipwho.is/$ip") ?: return null
        if (!json.optBoolean("success", false)) return null

        val latitude = json.optDouble("latitude", Double.NaN)
        val longitude = json.optDouble("longitude", Double.NaN)
        if (latitude.isNaN() || longitude.isNaN()) return null

        return RemoteGeo(
            latitude = latitude,
            longitude = longitude,
            countryCode = json.optString("country_code").ifBlank { "?" },
            city = json.optString("city").trim(),
            region = json.optString("region").trim(),
        )
    }

    private fun fetchFromIpApiCo(ip: String): RemoteGeo? {
        val json = readJson("https://ipapi.co/$ip/json/") ?: return null
        if (json.optBoolean("error", false)) return null

        val latitude = json.optDouble("latitude", Double.NaN)
        val longitude = json.optDouble("longitude", Double.NaN)
        if (latitude.isNaN() || longitude.isNaN()) return null

        return RemoteGeo(
            latitude = latitude,
            longitude = longitude,
            countryCode = json.optString("country_code").ifBlank { "?" },
            city = json.optString("city").trim(),
            region = json.optString("region").trim(),
        )
    }

    private fun buildLabel(remote: RemoteGeo): String {
        return strings.localityLabel(
            countryCode = remote.countryCode,
            city = remote.city,
            region = remote.region,
        )
    }

    private fun readJson(url: String): JSONObject? {
        val connection = (URL(url).openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2600
            readTimeout = 2600
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "RootNetMap/0.0.7")
        }

        return try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: return null
            }
            val body = stream.bufferedReader().use { it.readText() }
            if (body.isBlank()) null else JSONObject(body)
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        private const val MAX_CACHE_SIZE = 256
        private const val MAX_BATCH_RESOLVE = 10
        private const val RETRY_BACKOFF_MS = 10_000L
    }
}
