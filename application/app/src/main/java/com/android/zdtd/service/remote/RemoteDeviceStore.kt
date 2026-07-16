package com.android.zdtd.service.remote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class RemoteDeviceStore(context: Context) {
  private val prefs = context.getSharedPreferences("zdtd_remote_devices", Context.MODE_PRIVATE)

  fun load(): List<RemoteDeviceInfo> {
    val raw = prefs.getString("devices", "[]") ?: "[]"
    return runCatching {
      val arr = JSONArray(raw)
      (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        RemoteDeviceInfo(
          deviceId = o.optString("deviceId"),
          deviceName = o.optString("deviceName"),
          manufacturer = o.optString("manufacturer"),
          model = o.optString("model"),
          host = o.optString("host"),
          port = o.optInt("port"),
          appVersionCode = o.optInt("appVersionCode"),
          protocolVersion = o.optInt("protocolVersion", RemoteProtocol.PROTOCOL_VERSION),
          online = false,
          lastSeenMs = o.optLong("lastSeenMs"),
          sessionToken = "",
        )
      }
    }.getOrDefault(emptyList())
  }

  fun save(device: RemoteDeviceInfo) {
    val current = load().filterNot { it.deviceId == device.deviceId || (it.host == device.host && it.port == device.port) }
    val next = listOf(device.copy(lastSeenMs = System.currentTimeMillis())) + current
    val arr = JSONArray()
    next.take(24).forEach { d ->
      arr.put(JSONObject()
        .put("deviceId", d.deviceId)
        .put("deviceName", d.deviceName)
        .put("manufacturer", d.manufacturer)
        .put("model", d.model)
        .put("host", d.host)
        .put("port", d.port)
        .put("appVersionCode", d.appVersionCode)
        .put("protocolVersion", d.protocolVersion)
        .put("lastSeenMs", d.lastSeenMs))
    }
    prefs.edit().putString("devices", arr.toString()).apply()
  }

  fun clear() {
    prefs.edit().remove("devices").apply()
  }
}
