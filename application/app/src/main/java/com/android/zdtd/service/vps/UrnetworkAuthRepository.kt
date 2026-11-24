package com.android.zdtd.service.vps

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * URnetwork authentication against the Bring Your API.
 *
 * No credentials or tokens are stored here: the e-mail/code are method
 * arguments and the resulting JWT is returned to the caller (ViewModel state).
 */
class UrnetworkAuthRepository {
  private val http = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .callTimeout(15, TimeUnit.SECONDS)
    .build()
  private val jsonMedia = "application/json; charset=utf-8".toMediaType()

  suspend fun sendCode(email: String): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      val body = JSONObject().put("user_auth", email.trim())
      val response = requestJson("/auth/verify-send", body)
      checkApiError(response)
      Unit
    }
  }

  suspend fun verifyCode(email: String, code: String): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
      val body = JSONObject()
        .put("user_auth", email.trim())
        .put("verify_code", code.trim())
      val response = requestJson("/auth/verify", body)
      checkApiError(response)
      val jwt = response.optJSONObject("network")?.optString("by_jwt", "").orEmpty()
        .ifBlank { response.optString("by_jwt", "") }
      if (jwt.isBlank()) throw IOException("URnetwork did not return an authentication token")
      jwt
    }
  }

  private fun checkApiError(response: JSONObject) {
    val message = response.optJSONObject("error")?.optString("message", "").orEmpty()
      .ifBlank { response.optString("message", "") }
    if (message.isNotBlank()) throw IOException(message)
  }

  private fun requestJson(path: String, body: JSONObject): JSONObject {
    val request = Request.Builder()
      .url(BASE_URL + path)
      .post(body.toString().toRequestBody(jsonMedia))
      .build()
    http.newCall(request).execute().use { response ->
      val text = response.body?.string().orEmpty()
      // The API answers some errors with plain text (for example
      // "Invalid login."), so a parse failure must not hide the real message.
      val parsed = runCatching { if (text.isBlank()) JSONObject() else JSONObject(text) }
        .getOrNull() ?: JSONObject()
      if (!response.isSuccessful) {
        val message = parsed.optJSONObject("error")?.optString("message", "").orEmpty()
          .ifBlank { parsed.optString("message", "") }
          .ifBlank { text.take(200) }
          .ifBlank { "HTTP ${response.code}" }
        throw IOException(message)
      }
      return parsed
    }
  }

  companion object {
    private const val BASE_URL = "https://api.bringyour.com"
  }
}
