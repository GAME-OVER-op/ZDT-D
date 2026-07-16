package com.android.zdtd.service.remote

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class RemoteRootResult(
  val ok: Boolean,
  val code: Int,
  val out: String,
  val err: String,
)

class RemoteRootClient {
  private val http = OkHttpClient.Builder()
    .connectTimeout(4, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.MINUTES)
    .writeTimeout(15, TimeUnit.MINUTES)
    .retryOnConnectionFailure(true)
    .build()
  private val jsonMedia = "application/json".toMediaType()

  fun readText(target: RemoteControlTarget, path: String): String? {
    val obj = post(target, "/remote/root/read", JSONObject().put("path", path))
    return if (obj.optBoolean("ok", false)) obj.optString("content", "") else null
  }

  fun writeText(target: RemoteControlTarget, path: String, content: String): Boolean {
    val obj = post(target, "/remote/root/write", JSONObject().put("path", path).put("content", content))
    return obj.optBoolean("ok", false)
  }

  fun readBytes(target: RemoteControlTarget, path: String): ByteArray? {
    val obj = post(target, "/remote/root/readBytes", JSONObject().put("path", path))
    return if (obj.optBoolean("ok", false)) {
      Base64.decode(obj.optString("base64", ""), Base64.DEFAULT)
    } else {
      null
    }
  }

  fun writeBytes(target: RemoteControlTarget, path: String, bytes: ByteArray): Boolean {
    val obj = post(target, "/remote/root/writeBytes", JSONObject()
      .put("path", path)
      .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP)))
    return obj.optBoolean("ok", false)
  }

  fun exec(target: RemoteControlTarget, script: String): RemoteRootResult {
    val obj = post(target, "/remote/root/exec", JSONObject().put("script", script))
    return RemoteRootResult(
      ok = obj.optBoolean("ok", false),
      code = obj.optInt("code", -1),
      out = obj.optString("out", ""),
      err = obj.optString("err", ""),
    )
  }

  private fun post(target: RemoteControlTarget, path: String, body: JSONObject): JSONObject {
    val req = Request.Builder()
      .url(target.baseUrl.trimEnd('/') + path)
      .header("Authorization", "Bearer ${target.sessionToken}")
      .header("X-Api-Key", target.sessionToken)
      .post(body.toString().toRequestBody(jsonMedia))
      .build()
    http.newCall(req).execute().use { resp ->
      val text = resp.body?.string().orEmpty()
      if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${text.take(160)}")
      return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
  }
}
