package com.android.zdtd.service.worldmap.root

import android.content.Context
import com.android.zdtd.service.worldmap.WorldMapStrings
import com.maxmind.db.CHMCache
import com.maxmind.db.Reader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Offline GeoIP resolver for the world map.
 *
 * The database is downloaded on demand from the ZDT-D GitHub Technical_Assets release:
 *   https://github.com/GAME-OVER-op/ZDT-D/releases/download/Technical_Assets/dbip-city-lite.mmdb.gz
 *
 * Source: DB-IP IP to City Lite MMDB, https://db-ip.com/db/download/ip-to-city-lite
 * Attribution required: IP geolocation data by DB-IP.com.
 *
 * This repository intentionally performs no external geolocation API requests. The only
 * network access is the optional one-time download of the local MMDB component; all
 * IP -> location lookups happen locally in the application process after that.
 */
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

enum class GeoDatabaseStage {
    READY,
    IDLE,
    DOWNLOADING,
    UNPACKING,
    VERIFYING,
    FAILED,
}

data class GeoDatabaseState(
    val stage: GeoDatabaseStage = GeoDatabaseStage.IDLE,
    val fileName: String = GeoLocationRepository.GEO_DB_ARCHIVE_FILE,
    val progressPercent: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val errorMessage: String? = null,
) {
    val isReady: Boolean get() = stage == GeoDatabaseStage.READY
    val isWorking: Boolean get() = stage == GeoDatabaseStage.DOWNLOADING || stage == GeoDatabaseStage.UNPACKING || stage == GeoDatabaseStage.VERIFYING
}

class GeoLocationRepository(
    private val context: Context,
    private val strings: WorldMapStrings,
) {

    private data class LocalGeo(
        val latitude: Double,
        val longitude: Double,
        val countryCode: String,
        val city: String,
        val region: String,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val cache = LinkedHashMap<String, GeoLocation>()
    private val failed = LinkedHashSet<String>()
    private val inFlight = mutableSetOf<String>()
    private var reader: Reader? = null
    private var databaseLoadFailed = false

    private val _databaseState = MutableStateFlow(
        if (hasReadyDatabase()) GeoDatabaseState(stage = GeoDatabaseStage.READY) else GeoDatabaseState(stage = GeoDatabaseStage.IDLE)
    )
    val databaseState: StateFlow<GeoDatabaseState> = _databaseState.asStateFlow()

    fun cached(ip: String): GeoLocation? = synchronized(this) { cache[ip] }

    fun isDatabaseReady(): Boolean = hasReadyDatabase()

    fun stateFor(ip: String): GeoResolveState = synchronized(this) {
        when {
            cache.containsKey(ip) -> GeoResolveState.RESOLVED
            !shouldResolveIp(ip) -> GeoResolveState.SKIPPED
            inFlight.contains(ip) -> GeoResolveState.PENDING
            failed.contains(ip) || databaseLoadFailed || _databaseState.value.stage == GeoDatabaseStage.FAILED -> GeoResolveState.FAILED
            !_databaseState.value.isReady -> GeoResolveState.PENDING
            else -> GeoResolveState.PENDING
        }
    }

    suspend fun prepareDatabase(): Boolean = withContext(Dispatchers.IO) {
        if (hasReadyDatabase()) {
            _databaseState.value = GeoDatabaseState(stage = GeoDatabaseStage.READY)
            synchronized(this@GeoLocationRepository) { databaseLoadFailed = false }
            return@withContext true
        }

        runCatching {
            prepareDatabaseInternal()
        }.onSuccess {
            _databaseState.value = GeoDatabaseState(stage = GeoDatabaseStage.READY)
            synchronized(this@GeoLocationRepository) {
                databaseLoadFailed = false
                failed.clear()
            }
        }.onFailure { error ->
            _databaseState.value = GeoDatabaseState(
                stage = GeoDatabaseStage.FAILED,
                errorMessage = error.message ?: error.javaClass.simpleName,
            )
        }.isSuccess
    }

    suspend fun resolveMissing(ips: Collection<String>): Boolean = withContext(Dispatchers.IO) {
        if (!databaseState.value.isReady) return@withContext false

        val targets = synchronized(this@GeoLocationRepository) {
            ips.distinct()
                .asSequence()
                .filter(::shouldResolveIp)
                .filter { !cache.containsKey(it) }
                .filter { !failed.contains(it) }
                .filter { !inFlight.contains(it) }
                .take(MAX_LOCAL_GEO_RESOLVE_PER_CYCLE)
                .toList()
                .also { batch -> inFlight += batch }
        }

        var changed = false
        targets.forEach { ip ->
            val resolved = runCatching { resolveLocalLocation(ip) }.getOrNull()
            synchronized(this@GeoLocationRepository) {
                if (resolved != null) {
                    cache[ip] = resolved
                    failed.remove(ip)
                    changed = true
                } else {
                    failed += ip
                }
                inFlight -= ip
                trimCacheLocked()
            }
        }
        changed
    }

    fun close() {
        synchronized(this) {
            runCatching { reader?.close() }
            reader = null
        }
    }

    private fun trimCacheLocked() {
        while (cache.size > MAX_CACHE_SIZE) {
            val oldest = cache.entries.firstOrNull()?.key ?: break
            cache.remove(oldest)
        }
        while (failed.size > MAX_CACHE_SIZE) {
            val oldest = failed.firstOrNull() ?: break
            failed.remove(oldest)
        }
    }

    private fun shouldResolveIp(ip: String): Boolean {
        if (ip.isBlank() || ip == "*" || ip == "0.0.0.0" || ip == "::") return false
        if (ip == "127.0.0.1" || ip == "::1") return false
        if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("169.254.")) return false
        val normalized = ip.lowercase(Locale.US)
        if (normalized.startsWith("fc") || normalized.startsWith("fd") || normalized.startsWith("fe80:")) return false
        val secondOctet = ip.split('.').getOrNull(1)?.toIntOrNull()
        if (ip.startsWith("172.") && secondOctet != null && secondOctet in 16..31) return false
        return ip.any { it == '.' || it == ':' }
    }

    private fun resolveLocalLocation(ip: String): GeoLocation? {
        val local = lookupLocalGeo(ip) ?: return null
        val projected = LandSampleBank.projectLonLatToCanvas(
            longitude = local.longitude.toFloat(),
            latitude = local.latitude.toFloat(),
        )
        val refined = LandSampleBank.refineCanvasPoint(projected.first, projected.second)

        return GeoLocation(
            x = refined.first,
            y = refined.second,
            label = buildLabel(local),
        )
    }

    private fun lookupLocalGeo(ip: String): LocalGeo? {
        val activeReader = getReader() ?: return null
        val record = activeReader.get(InetAddress.getByName(ip), Map::class.java) as? Map<*, *> ?: return null
        val location = record.mapValue("location")
        val latitude = location?.numberValue("latitude") ?: return null
        val longitude = location.numberValue("longitude") ?: return null
        if (latitude.isNaN() || longitude.isNaN()) return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null

        val country = record.mapValue("country")
            ?: record.mapValue("registered_country")
            ?: record.mapValue("represented_country")
        val city = record.mapValue("city")
        val subdivisions = record["subdivisions"] as? List<*>
        val subdivision = subdivisions?.firstOrNull() as? Map<*, *>

        return LocalGeo(
            latitude = latitude,
            longitude = longitude,
            countryCode = country?.stringValue("iso_code")?.ifBlank { null } ?: "?",
            city = localizedName(city),
            region = localizedName(subdivision),
        )
    }

    private fun getReader(): Reader? = synchronized(this) {
        reader?.let { return@synchronized it }
        if (databaseLoadFailed) return@synchronized null
        if (!_databaseState.value.isReady && !hasReadyDatabase()) return@synchronized null

        val databaseFile = databaseFile()
        if (!databaseFile.canRead()) {
            databaseLoadFailed = true
            return@synchronized null
        }

        runCatching {
            Reader(databaseFile, CHMCache())
        }.onSuccess { opened ->
            reader = opened
        }.onFailure {
            databaseLoadFailed = true
            _databaseState.value = GeoDatabaseState(
                stage = GeoDatabaseStage.FAILED,
                errorMessage = it.message ?: it.javaClass.simpleName,
            )
        }.getOrNull()
    }

    private fun prepareDatabaseInternal() {
        val geoDir = geoDir()
        if (!geoDir.exists() && !geoDir.mkdirs()) {
            error("Unable to create geo database directory")
        }

        cleanupLegacyDatabaseIfNeeded()

        val archiveFile = File(geoDir, GEO_DB_ARCHIVE_FILE)
        val archiveTmpFile = File(geoDir, "$GEO_DB_ARCHIVE_FILE.tmp")
        val databaseFile = databaseFile()
        val databaseTmpFile = File(geoDir, "$GEO_DB_FILE.tmp")

        archiveTmpFile.delete()
        databaseTmpFile.delete()

        downloadArchive(archiveTmpFile)
        if (archiveFile.exists()) archiveFile.delete()
        if (!archiveTmpFile.renameTo(archiveFile)) {
            archiveTmpFile.copyTo(archiveFile, overwrite = true)
            archiveTmpFile.delete()
        }

        _databaseState.value = GeoDatabaseState(
            stage = GeoDatabaseStage.UNPACKING,
            progressPercent = 100,
        )

        GZIPInputStream(archiveFile.inputStream().buffered()).use { gzip ->
            databaseTmpFile.outputStream().buffered().use { output ->
                gzip.copyTo(output)
            }
        }

        if (!databaseTmpFile.isFile || databaseTmpFile.length() <= MIN_EXPECTED_DB_BYTES) {
            databaseTmpFile.delete()
            error("Downloaded DB-IP Lite City MMDB is missing or too small")
        }

        _databaseState.value = GeoDatabaseState(
            stage = GeoDatabaseStage.VERIFYING,
            progressPercent = 100,
        )

        runCatching {
            Reader(databaseTmpFile, CHMCache()).use { verifier ->
                verifier.get(InetAddress.getByName("8.8.8.8"), Map::class.java)
            }
        }.getOrElse { error ->
            databaseTmpFile.delete()
            throw IOException("Downloaded MMDB verification failed", error)
        }

        if (databaseFile.exists()) databaseFile.delete()
        if (!databaseTmpFile.renameTo(databaseFile)) {
            databaseTmpFile.copyTo(databaseFile, overwrite = true)
            databaseTmpFile.delete()
        }

        archiveFile.delete()
        synchronized(this) {
            runCatching { reader?.close() }
            reader = null
        }
    }

    private fun downloadArchive(targetTmpFile: File) {
        _databaseState.value = GeoDatabaseState(
            stage = GeoDatabaseStage.DOWNLOADING,
            progressPercent = 0,
        )

        val request = Request.Builder()
            .url(GEO_DB_DOWNLOAD_URL)
            .header("User-Agent", "ZDT-D Android")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to download ${GEO_DB_ARCHIVE_FILE}: HTTP ${response.code}")
            }

            val body = response.body
            val totalBytes = body.contentLength()
            var downloaded = 0L
            var lastProgress = -1
            var lastProgressBytes = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

            body.byteStream().use { input ->
                targetTmpFile.outputStream().buffered().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read.toLong()

                        val percent = if (totalBytes > 0L) {
                            ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        if (percent != lastProgress || downloaded - lastProgressBytes >= PROGRESS_EMIT_BYTES) {
                            lastProgress = percent
                            lastProgressBytes = downloaded
                            _databaseState.value = GeoDatabaseState(
                                stage = GeoDatabaseStage.DOWNLOADING,
                                progressPercent = percent,
                                downloadedBytes = downloaded,
                                totalBytes = totalBytes,
                            )
                        }
                    }
                }
            }
        }

        if (!targetTmpFile.isFile || targetTmpFile.length() <= MIN_EXPECTED_ARCHIVE_BYTES) {
            targetTmpFile.delete()
            error("Downloaded ${GEO_DB_ARCHIVE_FILE} is missing or too small")
        }
    }

    private fun hasReadyDatabase(): Boolean {
        cleanupLegacyDatabaseIfNeeded()
        val file = databaseFile()
        return file.isFile && file.length() > MIN_EXPECTED_DB_BYTES
    }

    private fun cleanupLegacyDatabaseIfNeeded() {
        val legacy = File(geoDir(), LEGACY_GEO_DB_FILE)
        val current = databaseFile()
        if (current.exists() || !legacy.exists()) return
        runCatching {
            if (!geoDir().exists()) geoDir().mkdirs()
            if (!legacy.renameTo(current)) {
                legacy.copyTo(current, overwrite = true)
                legacy.delete()
            }
        }
    }

    private fun geoDir(): File = File(context.filesDir, GEO_DB_DIR)

    private fun databaseFile(): File = File(geoDir(), GEO_DB_FILE)

    private fun buildLabel(local: LocalGeo): String {
        return strings.localityLabel(
            countryCode = local.countryCode,
            city = local.city,
            region = local.region,
        )
    }

    private fun localizedName(node: Map<*, *>?): String {
        if (node == null) return ""
        val direct = node["name"] as? String
        if (!direct.isNullOrBlank()) return direct.trim()

        val names = node["names"] as? Map<*, *> ?: return ""
        val language = Locale.getDefault().language.lowercase(Locale.US)
        val preferred = listOf(language, "en", "ru")
        preferred.forEach { key ->
            val value = names[key] as? String
            if (!value.isNullOrBlank()) return value.trim()
        }
        return names.values.firstOrNull { it is String && it.isNotBlank() }?.let { (it as String).trim() }.orEmpty()
    }

    private fun Map<*, *>.mapValue(key: String): Map<*, *>? = this[key] as? Map<*, *>

    private fun Map<*, *>.stringValue(key: String): String? = this[key] as? String

    private fun Map<*, *>.numberValue(key: String): Double? {
        return when (val value = this[key]) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    companion object {
        const val GEO_DB_ARCHIVE_FILE = "dbip-city-lite.mmdb.gz"
        private const val GEO_DB_FILE = "dbip-city-lite.mmdb"
        private const val LEGACY_GEO_DB_FILE = "dbip-city-lite-2026-05.mmdb"
        private const val GEO_DB_DIR = "geo"
        private const val GEO_DB_DOWNLOAD_URL = "https://github.com/GAME-OVER-op/ZDT-D/releases/download/Technical_Assets/dbip-city-lite.mmdb.gz"
        private const val MAX_CACHE_SIZE = 4096
        private const val MAX_LOCAL_GEO_RESOLVE_PER_CYCLE = 64
        private const val MIN_EXPECTED_DB_BYTES = 1_000_000L
        private const val MIN_EXPECTED_ARCHIVE_BYTES = 512_000L
        private const val PROGRESS_EMIT_BYTES = 512L * 1024L
    }
}
