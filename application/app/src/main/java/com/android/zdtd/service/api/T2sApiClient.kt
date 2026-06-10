package com.android.zdtd.service.api

import com.android.zdtd.service.RootConfigManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class T2sApiClient(
  private val rootManager: RootConfigManager,
  private val port: Int,
) {
  private val http = OkHttpClient.Builder()
    .connectTimeout(900, TimeUnit.MILLISECONDS)
    .readTimeout(1800, TimeUnit.MILLISECONDS)
    .writeTimeout(1800, TimeUnit.MILLISECONDS)
    .build()
  private val jsonMedia = "application/json".toMediaType()
  private val baseUrl: String get() = "http://127.0.0.1:$port"

  fun poll(): T2sPollResult {
    val obj = requestJson("GET", "/api/v1/poll", null)
    return T2sPollResult.fromJson(obj)
  }

  fun setDownloadLimit(mbit: Double): Boolean {
    val obj = requestJson("PUT", "/api/v1/limits", JSONObject().put("mbit", mbit))
    return obj.optBoolean("ok", false)
  }

  fun killConnection(cid: String): Boolean {
    val obj = requestJson("POST", "/api/v1/connections/kill", JSONObject().put("cid", cid))
    return obj.optBoolean("ok", false) || obj.optString("result") == "ok"
  }

  fun addBackend(host: String, port: Int, username: String = "", password: String = ""): Boolean {
    val body = JSONObject().put("host", host).put("port", port)
    if (username.isNotBlank()) body.put("username", username)
    if (password.isNotBlank()) body.put("password", password)
    val obj = requestJson("POST", "/api/v1/backends/add", body)
    return obj.optBoolean("ok", false) || obj.optString("result") == "ok"
  }

  fun removeBackend(addr: String): Boolean {
    val host = addr.substringBeforeLast(':', addr)
    val port = addr.substringAfterLast(':', "0").toIntOrNull() ?: return false
    val obj = requestJson("POST", "/api/v1/backends/remove", JSONObject().put("host", host).put("port", port))
    return obj.optBoolean("ok", false) || obj.optString("result") == "ok"
  }

  fun recheckBackends(): Boolean {
    val obj = requestJson("POST", "/api/v1/backends/recheck", JSONObject())
    return obj.optBoolean("ok", false)
  }

  private fun requestJson(method: String, path: String, body: JSONObject?): JSONObject {
    val token = rootManager.readApiToken().trim()
    val builder = Request.Builder().url(baseUrl + path)
    if (token.isNotEmpty()) {
      builder.header("Authorization", "Bearer $token")
      builder.header("X-Api-Key", token)
    }
    val request = when (method.uppercase()) {
      "GET" -> builder.get().build()
      "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(jsonMedia)).build()
      "PUT" -> builder.put((body ?: JSONObject()).toString().toRequestBody(jsonMedia)).build()
      else -> builder.method(method.uppercase(), (body ?: JSONObject()).toString().toRequestBody(jsonMedia)).build()
    }
    http.newCall(request).execute().use { resp ->
      val text = resp.body?.string().orEmpty()
      if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${text.take(120)}")
      return if (text.trim().isEmpty()) JSONObject() else JSONObject(text)
    }
  }
}

data class T2sPollResult(
  val collecting: Boolean = false,
  val suggestedIntervalMs: Long = 1000L,
  val minIntervalMs: Long = 750L,
  val state: T2sState = T2sState(),
) {
  companion object {
    fun fromJson(obj: JSONObject): T2sPollResult {
      val poll = obj.optJSONObject("poll") ?: JSONObject()
      val stateObj = obj.optJSONObject("state") ?: obj
      return T2sPollResult(
        collecting = poll.optBoolean("collecting", false),
        suggestedIntervalMs = poll.optLong("suggested_interval_ms", 1000L).coerceIn(500L, 5000L),
        minIntervalMs = poll.optLong("min_interval_ms", 750L).coerceIn(300L, 5000L),
        state = T2sState.fromJson(stateObj),
      )
    }
  }
}

data class T2sState(
  val instance: T2sInstance = T2sInstance(),
  val ts: Long = 0L,
  val bytesUp: Long = 0L,
  val bytesDown: Long = 0L,
  val errors: Long = 0L,
  val socksOk: Long = 0L,
  val socksFail: Long = 0L,
  val policyDrop: Long = 0L,
  val activeConnections: Int = 0,
  val ports: List<T2sPort> = emptyList(),
  val connections: List<T2sConnection> = emptyList(),
  val backends: List<T2sBackend> = emptyList(),
  val downloadLimitMbit: Double = 0.0,
  val runtime: T2sRuntime = T2sRuntime(),
) {
  companion object {
    fun fromJson(obj: JSONObject): T2sState {
      val stats = obj.optJSONObject("stats") ?: JSONObject()
      val portsObj = obj.optJSONObject("ports") ?: JSONObject()
      val ports = buildList {
        portsObj.optJSONObject("internal")?.let { add(T2sPort.fromJson(it)) }
        portsObj.optJSONObject("external")?.let { add(T2sPort.fromJson(it)) }
      }
      val limits = obj.optJSONObject("limits") ?: JSONObject()
      return T2sState(
        instance = T2sInstance.fromJson(obj.optJSONObject("instance")),
        ts = obj.optLong("ts", 0L),
        bytesUp = stats.optLong("bytes_up", 0L),
        bytesDown = stats.optLong("bytes_down", 0L),
        errors = stats.optLong("errors", 0L),
        socksOk = stats.optLong("socks_ok", 0L),
        socksFail = stats.optLong("socks_fail", 0L),
        policyDrop = stats.optLong("policy_drop", 0L),
        activeConnections = obj.optInt("active_connections", 0),
        ports = ports,
        connections = parseArray(obj.optJSONArray("connections") ?: obj.optJSONArray("conns")) { T2sConnection.fromJson(it) },
        backends = parseArray(obj.optJSONArray("backends")) { T2sBackend.fromJson(it) },
        downloadLimitMbit = limits.optDouble("download_mbit", obj.optDouble("download_limit_mbit", 0.0)),
        runtime = T2sRuntime.fromJson(obj.optJSONObject("runtime")),
      )
    }
  }
}

data class T2sInstance(
  val instanceId: String = "",
  val program: String = "t2s",
  val profile: String = "",
  val scope: String = "",
  val pid: Int = 0,
  val webPort: Int = 0,
  val listenPort: Int = 0,
  val externalPort: Int = 0,
  val backendMode: String = "",
  val updatedAt: Long = 0L,
) {
  companion object {
    fun fromJson(obj: JSONObject?): T2sInstance = T2sInstance(
      instanceId = obj?.optString("instance_id", "").orEmpty(),
      program = obj?.optString("program", "t2s").orEmpty().ifBlank { "t2s" },
      profile = obj?.optString("profile", "").orEmpty(),
      scope = obj?.optString("scope", "").orEmpty(),
      pid = obj?.optInt("pid", 0) ?: 0,
      webPort = obj?.optInt("web_port", 0) ?: 0,
      listenPort = obj?.optInt("listen_port", 0) ?: 0,
      externalPort = obj?.optInt("external_port", 0) ?: 0,
      backendMode = obj?.optString("backend_mode", "").orEmpty(),
      updatedAt = obj?.optLong("updated_at", 0L) ?: 0L,
    )
  }
}

data class T2sPort(val label: String = "", val listen: String = "", val active: Int = 0, val up: Long = 0L, val down: Long = 0L) {
  companion object { fun fromJson(obj: JSONObject) = T2sPort(obj.optString("label"), obj.optString("listen"), obj.optInt("active_connections"), obj.optLong("bytes_up"), obj.optLong("bytes_down")) }
}

data class T2sConnection(val cid: String = "", val domain: String = "", val peer: String = "", val dstIp: String = "", val mode: String = "", val ingress: String = "", val server: String = "", val up: Long = 0L, val down: Long = 0L) {
  companion object { fun fromJson(obj: JSONObject) = T2sConnection(obj.optString("cid"), obj.optString("domain"), obj.optString("peer"), obj.optString("dst_ip"), obj.optString("mode"), obj.optString("ingress"), obj.optString("server"), obj.optLong("bytes_up"), obj.optLong("bytes_down")) }
}

data class T2sBackend(val addr: String = "", val state: String = "", val healthy: Boolean = false, val lastError: String = "", val socksPingMs: Double? = null, val internetPingMs: Double? = null, val totalBytes: Long = 0L, val recentBps: Double = 0.0, val degraded: Boolean = false) {
  companion object { fun fromJson(obj: JSONObject) = T2sBackend(obj.optString("addr"), obj.optString("state"), obj.optBoolean("healthy"), obj.optString("last_error"), obj.optNullableDouble("socks_ping_ms"), obj.optNullableDouble("internet_ping_ms"), obj.optLong("total_bytes"), obj.optDouble("recent_bps", 0.0), obj.optBoolean("speed_degraded")) }
}

data class T2sRuntime(val maxConns: Int = 0, val backendMode: String = "", val prioritySpeedAware: Boolean = false, val bufferSize: Int = 0, val idleTimeout: Int = 0, val connectTimeout: Int = 0) {
  companion object { fun fromJson(obj: JSONObject?) = T2sRuntime(obj?.optInt("max_conns", 0) ?: 0, obj?.optString("backend_mode", "").orEmpty(), obj?.optBoolean("priority_speed_aware", false) == true, obj?.optInt("buffer_size", 0) ?: 0, obj?.optInt("idle_timeout", 0) ?: 0, obj?.optInt("connect_timeout", 0) ?: 0) }
}

private fun <T> parseArray(arr: JSONArray?, mapper: (JSONObject) -> T): List<T> {
  if (arr == null) return emptyList()
  val out = ArrayList<T>(arr.length())
  for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out += mapper(it) }
  return out
}

private fun JSONObject.optNullableDouble(name: String): Double? = if (has(name) && !isNull(name)) optDouble(name) else null
