package com.android.zdtd.service.api

import com.android.zdtd.service.RootConfigManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Networking strategy (per Danil):
 * 1) try regular HTTP to 127.0.0.1
 * 2) if it doesn't work (connectivity / WebView-like issues) -> fallback to root-proxy curl
 */
class ApiClient(
  private val rootManager: RootConfigManager,
  private val baseUrlProvider: () -> String,
  private val tokenProvider: () -> String,
) {

  private val http = OkHttpClient.Builder()
    .connectTimeout(2, TimeUnit.SECONDS)
    .readTimeout(3, TimeUnit.SECONDS)
    .writeTimeout(3, TimeUnit.SECONDS)
    .build()

  private val jsonMedia = "application/json".toMediaType()

  fun getStatus(): ApiModels.StatusReport? {
    val obj = requestJson("GET", "/api/status", null)
    return ApiModels.parseStatusReport(obj)
  }

  fun startService(): Boolean = requestOk("POST", "/api/start", null)

  fun stopService(): Boolean = requestOk("POST", "/api/stop", null)

  fun getPrograms(): List<ApiModels.Program> {
    val obj = requestJson("GET", "/api/programs", null)
    return ApiModels.parsePrograms(obj)
  }

  fun setProgramEnabled(programId: String, enabled: Boolean): Boolean {
    val path = "/api/programs/${enc(programId)}/enabled"
    val body = JSONObject().put("enabled", enabled)
    return requestOk("PUT", path, body)
  }

  fun setProfileEnabled(programId: String, profile: String, enabled: Boolean): Boolean {
    val path = "/api/programs/${enc(programId)}/profiles/${enc(profile)}/enabled"
    val body = JSONObject().put("enabled", enabled)
    return requestOk("PUT", path, body)
  }

  fun deleteProfile(programId: String, profile: String): Boolean {
    val path = "/api/programs/${enc(programId)}/profiles/${enc(profile)}"
    return requestOk("DELETE", path, JSONObject())
  }

  /**
   * Create a profile. Per Danil: endpoint is /api/new/profile.
   * - If [profile] is null/blank -> server chooses the next profile name.
   * - If [profile] is provided -> server creates that profile name.
   */
  fun createProfile(programId: String, profile: String? = null): Boolean {
    val p = profile?.trim().orEmpty()
    if (programId == "myproxy") {
      val body = JSONObject()
      if (p.isNotEmpty()) body.put("name", p)
      return requestOk("POST", "/api/programs/myproxy/profiles", body)
    }
    val body = JSONObject().put("program", programId)
    if (p.isNotEmpty()) body.put("profile", p)
    return requestOk("POST", "/api/new/profile", body)
  }

  fun createSingBoxServer(profile: String, server: String? = null): Boolean {
    val path = "/api/programs/sing-box/profiles/${enc(profile)}/servers"
    val body = JSONObject()
    val s = server?.trim().orEmpty()
    if (s.isNotEmpty()) body.put("name", s)
    return requestOk("POST", path, body)
  }

  fun deleteSingBoxServer(profile: String, server: String): Boolean {
    val path = "/api/programs/sing-box/profiles/${enc(profile)}/servers/${enc(server)}"
    return requestOk("DELETE", path, null)
  }

  fun createWireProxyServer(profile: String, server: String? = null): Boolean {
    val path = "/api/programs/wireproxy/profiles/${enc(profile)}/servers"
    val body = JSONObject()
    val s = server?.trim().orEmpty()
    if (s.isNotEmpty()) body.put("name", s)
    return requestOk("POST", path, body)
  }

  fun deleteWireProxyServer(profile: String, server: String): Boolean {
    val path = "/api/programs/wireproxy/profiles/${enc(profile)}/servers/${enc(server)}"
    return requestOk("DELETE", path, null)
  }

  fun getTextContent(path: String): String {
    val obj = requestJson("GET", path, null)
    return obj?.optString("content", "") ?: ""
  }

  fun putTextContent(path: String, content: String): Boolean {
    val body = JSONObject().put("content", content)
    return requestOk("PUT", path, body)
  }

  fun getDaemonSettings(): ApiModels.DaemonSettings {
    val obj = requestJson("GET", "/api/setting", null)
    return ApiModels.parseDaemonSettings(obj)
  }

  fun setProtectorMode(mode: String): ApiModels.DaemonSettings {
    val safe = when (mode.trim().lowercase()) {
      "on", "off", "auto" -> mode.trim().lowercase()
      else -> "off"
    }
    val obj = requestJson("POST", "/api/setting", JSONObject().put("protector_mode", safe))
    return ApiModels.parseDaemonSettings(obj)
  }

  fun setHotspotT2s(
    enabled: Boolean,
    target: String,
    singboxProfile: String = "",
    wireproxyProfile: String = "",
  ): ApiModels.DaemonSettings {
    val safeTarget = when (target.trim().lowercase()) {
      "operaproxy", "opera-proxy", "opera_proxy" -> "operaproxy"
      "singbox", "sing-box", "sing_box" -> "singbox"
      "wireproxy", "wire-proxy", "wire_proxy" -> "wireproxy"
      else -> ""
    }
    val safeProfile = if (safeTarget == "singbox") singboxProfile.trim() else ""
    val safeWireproxyProfile = if (safeTarget == "wireproxy") wireproxyProfile.trim() else ""
    val body = JSONObject()
      .put("hotspot_t2s_enabled", enabled)
      .put("hotspot_t2s_target", safeTarget)
      .put("hotspot_t2s_singbox_profile", safeProfile)
      .put("hotspot_t2s_wireproxy_profile", safeWireproxyProfile)
    val obj = requestJson("POST", "/api/setting", body)
    return ApiModels.parseDaemonSettings(obj)
  }

  fun getSingBoxProfiles(): List<ApiModels.SingBoxProfileChoice> {
    val obj = requestJson("GET", "/api/programs/sing-box/profiles", null)
    return ApiModels.parseSingBoxProfiles(obj)
  }

  fun getWireProxyProfiles(): List<ApiModels.SingBoxProfileChoice> {
    val obj = requestJson("GET", "/api/programs/wireproxy/profiles", null)
    return ApiModels.parseSingBoxProfiles(obj)
  }

  fun getAppAssignments(): ApiModels.AppAssignmentsState {
    val obj = requestJson("GET", "/api/apps/assignments", null)
    return ApiModels.parseAppAssignments(obj)
  }
  fun getProxyInfo(): ApiModels.ProxyInfoState {
    val obj = requestJson("GET", "/api/proxyinfo", null)
    return ApiModels.parseProxyInfo(obj)
  }

  fun setProxyInfoEnabled(enabled: Boolean): Boolean {
    val body = JSONObject().put("enabled", enabled)
    return requestOk("PUT", "/api/proxyinfo/enabled", body)
  }

  fun getProxyInfoApps(): String {
    val obj = requestJson("GET", "/api/proxyinfo/apps", null)
    return obj?.optString("content", "") ?: ""
  }

  fun setProxyInfoApps(content: String): Boolean {
    val body = JSONObject().put("content", content)
    return requestOk("PUT", "/api/proxyinfo/apps", body)
  }


  fun saveProxyInfoAppsResolved(content: String, removeConflicts: Boolean): Boolean {
    val body = JSONObject().put("content", content).put("remove_conflicts", removeConflicts)
    val obj = requestJson("POST", "/api/proxyinfo/save", body)
    return (obj?.optBoolean("ok", false) == true) && (obj.optBoolean("saved", false))
  }

  fun applyProxyInfo(): Boolean = requestOk("POST", "/api/proxyinfo/apply", JSONObject())


  fun getBlockedQuic(): ApiModels.ProxyInfoState {
    val obj = requestJson("GET", "/api/blockedquic", null)
    return ApiModels.parseProxyInfo(obj)
  }

  fun getBlockedQuicEnabled(): Boolean {
    val obj = requestJson("GET", "/api/blockedquic/enabled", null)
    return jsonBool(obj, "enabled", false)
  }

  fun setBlockedQuicEnabled(enabled: Boolean): Boolean {
    val body = JSONObject().put("enabled", enabled)
    return requestOk("PUT", "/api/blockedquic/enabled", body)
  }

  fun getBlockedQuicApps(): String {
    val obj = requestJson("GET", "/api/blockedquic/apps", null)
    return obj?.optString("content", "") ?: ""
  }

  fun setBlockedQuicApps(content: String): Boolean {
    val body = JSONObject().put("content", content)
    return requestOk("PUT", "/api/blockedquic/apps", body)
  }

  fun saveBlockedQuicApps(content: String): Boolean {
    val body = JSONObject().put("content", content)
    val obj = requestJson("POST", "/api/blockedquic/save", body)
    return (obj?.optBoolean("ok", false) == true) && obj.optBoolean("saved", false)
  }

  fun applyBlockedQuic(): Boolean = requestOk("POST", "/api/blockedquic/apply", JSONObject())

  fun getJsonData(path: String): JSONObject {
    val obj = requestJson("GET", path, null) ?: return JSONObject()
    // Prefer wrapped payload when server uses { data: {...} }, otherwise return the object as-is.
    return obj.optJSONObject("data") ?: obj
  }

  fun putJsonData(path: String, data: JSONObject): Boolean {
    // This endpoint expects object directly (like {port:..}) or wrapper? In the HTML UI it sends the object as-is.
    return requestOk("PUT", path, data)
  }

  fun postJsonData(path: String, data: JSONObject): Boolean {
    return requestOk("POST", path, data)
  }


  fun deletePath(path: String): Boolean {
    return requestOk("DELETE", path, null)
  }


/**
 * Multipart file upload: POST <path> with form-data field "file".
 * Returns true for 2xx responses.
 */
fun uploadMultipart(path: String, filename: String, bytes: ByteArray): Boolean {
  val baseUrl = baseUrlProvider().trim().ifEmpty { "http://127.0.0.1:1006" }
  val url = baseUrl + path

  val body = MultipartBody.Builder()
    .setType(MultipartBody.FORM)
    .addFormDataPart(
      "file",
      filename,
      bytes.toRequestBody("application/octet-stream".toMediaType())
    )
    .build()

  val token = tokenProvider().trim()
  val b = Request.Builder().url(url).post(body)
  if (token.isNotEmpty()) {
    b.header("Authorization", "Bearer $token")
    b.header("X-Api-Key", token)
  }

  // 1) Try normal HTTP
  try {
    http.newCall(b.build()).execute().use { resp ->
      if (resp.isSuccessful) return true
    }
  } catch (_: IOException) {
    // fall through to root-proxy
  }

  // 2) Root fallback: write bytes to temp file and curl -F upload
  return rootManager.proxyUploadMultipart(path, filename, bytes).optInt("code", 0) in 200..299
}


  private fun jsonBool(obj: JSONObject?, key: String, default: Boolean = false): Boolean {
    if (obj == null || !obj.has(key)) return default
    return when (val raw = obj.opt(key)) {
      is Boolean -> raw
      is Number -> raw.toInt() != 0
      is String -> when (raw.trim().lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off", "" -> false
        else -> default
      }
      else -> default
    }
  }


  private fun requestOk(method: String, path: String, body: JSONObject?): Boolean {
    val obj = requestJson(method, path, body)
    // Many endpoints return { ok: true }, some return raw objects.
    // We treat missing "ok" as success if HTTP status was 2xx (handled by requestJson).
    return jsonBool(obj, "ok", true)
  }

  private fun requestJson(method: String, path: String, body: JSONObject?): JSONObject? {
    // 1) Try normal HTTP.
    try {
      val url = baseUrlProvider().trimEnd('/') + path
      val req = buildRequest(method, url, body)
      http.newCall(req).execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${text.take(120)}")
        return parseJsonOrThrow(text)
      }
    } catch (e: Throwable) {
      // 2) Root-proxy fallback.
      val raw = when (method.uppercase()) {
        "GET", "HEAD" -> rootManager.proxyGet(path)
        "POST" -> rootManager.proxyPost(path, (body ?: JSONObject()).toString())
        "PUT" -> rootManager.proxyPut(path, (body ?: JSONObject()).toString())
        "DELETE" -> rootManager.proxyDelete(path, (body ?: JSONObject()).toString())
        else -> rootManager.proxyPost(path, (body ?: JSONObject()).toString())
      }
      val wrapper = parseJsonOrThrow(raw)
      val code = wrapper.optInt("code", 0)
      val bodyText = wrapper.optString("body", "")
      if (code < 200 || code >= 300) {
        throw IOException("proxy HTTP $code: ${bodyText.take(120)}")
      }
      return parseJsonOrThrow(bodyText)
    }
  }

  private fun parseJsonOrThrow(text: String): JSONObject {
    val t = text.trim()
    if (t.isBlank()) return JSONObject()
    try {
      return JSONObject(t)
    } catch (e: Throwable) {
      throw IOException("Invalid JSON: ${t.take(160)}", e)
    }
  }

  private fun buildRequest(method: String, url: String, body: JSONObject?): Request {
    val token = tokenProvider().trim()
    val b = Request.Builder().url(url)
    if (token.isNotEmpty()) {
      b.header("Authorization", "Bearer $token")
      b.header("X-Api-Key", token)
    }
    return when (method.uppercase()) {
      "GET" -> b.get().build()
      "POST" -> b.post((body ?: JSONObject()).toString().toRequestBody(jsonMedia)).build()
      "PUT" -> b.put((body ?: JSONObject()).toString().toRequestBody(jsonMedia)).build()
      "DELETE" -> b.delete((body ?: JSONObject()).toString().toRequestBody(jsonMedia)).build()
      else -> b.method(method.uppercase(), (body ?: JSONObject()).toString().toRequestBody(jsonMedia)).build()
    }
  }

  private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
