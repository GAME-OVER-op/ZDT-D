package com.android.zdtd.service.remote

import android.os.Build
import com.android.zdtd.service.BuildConfig
import org.json.JSONObject
import java.util.UUID

data class RemoteDeviceInfo(
  val deviceId: String,
  val deviceName: String,
  val manufacturer: String,
  val model: String,
  val host: String,
  val port: Int,
  val appVersionCode: Int,
  val protocolVersion: Int = RemoteProtocol.PROTOCOL_VERSION,
  val online: Boolean = false,
  val lastSeenMs: Long = 0L,
  val sessionToken: String = "",
) {
  fun displayTitle(): String = deviceName.ifBlank { listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { host } }
}

object RemoteProtocol {
  const val PROTOCOL_VERSION = 1
  const val UDP_PORT = 10319
  const val DISCOVER = "ZDTD_DISCOVER_V1"
  const val SERVICE = "ZDTD_REMOTE_HOST_V1"
  const val QR_SCHEME = "zdtd://remote-pair"

  fun localDeviceName(): String {
    val parts = listOf(Build.MANUFACTURER, Build.MODEL)
      .map { it.trim() }
      .filter { it.isNotBlank() && it.lowercase() != "unknown" }
      .distinctBy { it.lowercase() }
    return parts.joinToString(" ").ifBlank { "Android" }
  }

  fun localDeviceId(): String = UUID.nameUUIDFromBytes(
    (Build.MANUFACTURER + ":" + Build.MODEL + ":" + Build.DEVICE + ":" + Build.PRODUCT).toByteArray()
  ).toString()

  fun infoJson(host: String, port: Int, pairing: Boolean): JSONObject = JSONObject()
    .put("type", SERVICE)
    .put("deviceId", localDeviceId())
    .put("deviceName", localDeviceName())
    .put("manufacturer", Build.MANUFACTURER ?: "")
    .put("model", Build.MODEL ?: "")
    .put("host", host)
    .put("port", port)
    .put("pairing", pairing)
    .put("appVersionCode", BuildConfig.VERSION_CODE)
    .put("appVersionName", BuildConfig.VERSION_NAME)
    .put("protocolVersion", PROTOCOL_VERSION)

  fun parseDevice(obj: JSONObject, fallbackHost: String = ""): RemoteDeviceInfo = RemoteDeviceInfo(
    deviceId = obj.optString("deviceId", ""),
    deviceName = obj.optString("deviceName", ""),
    manufacturer = obj.optString("manufacturer", ""),
    model = obj.optString("model", ""),
    host = obj.optString("host", fallbackHost),
    port = obj.optInt("port", 0),
    appVersionCode = obj.optInt("appVersionCode", 0),
    protocolVersion = obj.optInt("protocolVersion", PROTOCOL_VERSION),
    online = true,
    lastSeenMs = System.currentTimeMillis(),
    sessionToken = obj.optString("sessionToken", ""),
  )

  fun qrPayload(host: String, port: Int, code: String, deviceId: String = localDeviceId()): String {
    return "$QR_SCHEME?host=$host&port=$port&code=$code&device=$deviceId&vc=${BuildConfig.VERSION_CODE}&pv=$PROTOCOL_VERSION"
  }

  fun parseQrPayload(raw: String): Map<String, String> {
    val s = raw.trim()
    if (!s.startsWith(QR_SCHEME)) return emptyMap()
    val q = s.substringAfter('?', "")
    return q.split('&')
      .mapNotNull {
        val k = it.substringBefore('=', "")
        val v = it.substringAfter('=', "")
        if (k.isBlank()) null else k to java.net.URLDecoder.decode(v, "UTF-8")
      }
      .toMap()
  }
}

data class RemoteHostState(
  val running: Boolean = false,
  val host: String = "",
  val port: Int = 0,
  val code: String = "",
  val qrPayload: String = "",
  val pairedDevice: String = "",
  val error: String = "",
)

data class RemoteControlTarget(
  val device: RemoteDeviceInfo,
  val baseUrl: String,
  val sessionToken: String,
)
