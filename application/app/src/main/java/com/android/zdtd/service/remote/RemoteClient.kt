package com.android.zdtd.service.remote

import com.android.zdtd.service.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class RemoteClient {
  private val http = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(6, TimeUnit.SECONDS)
    .writeTimeout(6, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()
  private val jsonMedia = "application/json".toMediaType()

  fun info(host: String, port: Int): RemoteDeviceInfo {
    val obj = requestJson("GET", "http://$host:$port/remote/info", null, "")
    return RemoteProtocol.parseDevice(obj, host)
  }

  fun ping(device: RemoteDeviceInfo): RemoteDeviceInfo? = runCatching {
    val url = "http://" + device.host + ":" + device.port + "/remote/ping"
    val obj = requestJson("GET", url, null, device.sessionToken)
    RemoteProtocol.parseDevice(obj, device.host).copy(sessionToken = device.sessionToken)
  }.getOrNull()

  fun pair(host: String, port: Int, code: String): RemoteControlTarget {
    val info = info(host, port)
    if (info.appVersionCode != 0 && info.appVersionCode != BuildConfig.VERSION_CODE) {
      throw IOException("version_mismatch:${BuildConfig.VERSION_CODE}:${info.appVersionCode}")
    }
    val body = JSONObject()
      .put("code", code.replace("-", "").trim().uppercase())
      .put("appVersionCode", BuildConfig.VERSION_CODE)
      .put("protocolVersion", RemoteProtocol.PROTOCOL_VERSION)
      .put("deviceName", RemoteProtocol.localDeviceName())
      .put("deviceId", RemoteProtocol.localDeviceId())
    val obj = requestJson("POST", "http://$host:$port/remote/pair", body, "")
    if (!obj.optBoolean("ok", false)) throw IOException(obj.optString("error", "pair_failed"))
    val token = obj.optString("sessionToken", "")
    if (token.isBlank()) throw IOException("session_token_missing")
    val deviceObj = obj.optJSONObject("device") ?: JSONObject()
    val device = RemoteProtocol.parseDevice(deviceObj, host).copy(host = host, port = port, sessionToken = token)
    return RemoteControlTarget(device = device, baseUrl = "http://$host:$port", sessionToken = token)
  }

  private fun requestJson(method: String, url: String, body: JSONObject?, token: String): JSONObject {
    val b = Request.Builder().url(url)
    if (token.isNotBlank()) {
      b.header("Authorization", "Bearer $token")
      b.header("X-Api-Key", token)
    }
    val req = when (method) {
      "GET" -> b.get().build()
      else -> b.method(method, (body ?: JSONObject()).toString().toRequestBody(jsonMedia)).build()
    }
    http.newCall(req).execute().use { resp ->
      val text = resp.body?.string().orEmpty()
      if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${text.take(160)}")
      return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
  }
}
