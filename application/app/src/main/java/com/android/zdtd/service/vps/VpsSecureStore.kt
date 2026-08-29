package com.android.zdtd.service.vps

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class VpsSecureStore(context: Context) {
  private val prefs = context.getSharedPreferences("vps_servers_secure", Context.MODE_PRIVATE)
  private val alias = "zdt_vps_servers_aes_v1"

  @Synchronized
  fun loadServers(): List<VpsServer> {
    val encrypted = prefs.getString("servers", null) ?: return emptyList()
    val json = runCatching { decrypt(encrypted) }.getOrElse { return emptyList() }
    val array = runCatching { JSONArray(json) }.getOrElse { return emptyList() }
    return buildList {
      for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        val host = obj.optString("host").trim()
        val user = obj.optString("username").trim()
        if (host.isEmpty() || user.isEmpty()) continue
        add(
          VpsServer(
            id = obj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            name = obj.optString("name").ifBlank { host },
            host = host,
            port = obj.optInt("port", 22).coerceIn(1, 65535),
            username = user,
            password = obj.optString("password"),
            authType = runCatching {
              VpsAuthType.valueOf(obj.optString("authType", VpsAuthType.PASSWORD.name))
            }.getOrDefault(VpsAuthType.PASSWORD),
            privateKey = obj.optString("privateKey"),
            privateKeyName = obj.optString("privateKeyName"),
            privateKeyPassphrase = obj.optString("privateKeyPassphrase"),
            pinnedHostKey = obj.optString("hostKey"),
            fingerprint = obj.optString("fingerprint"),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            lastSuccessfulCheck = obj.optLong("lastSuccessfulCheck", 0L),
          )
        )
      }
    }
  }

  @Synchronized
  fun saveServers(servers: List<VpsServer>) {
    val array = JSONArray()
    servers.forEach { server ->
      array.put(
        JSONObject()
          .put("id", server.id)
          .put("name", server.name)
          .put("host", server.host)
          .put("port", server.port)
          .put("username", server.username)
          .put("password", server.password)
          .put("authType", server.authType.name)
          .put("privateKey", server.privateKey)
          .put("privateKeyName", server.privateKeyName)
          .put("privateKeyPassphrase", server.privateKeyPassphrase)
          .put("hostKey", server.pinnedHostKey)
          .put("fingerprint", server.fingerprint)
          .put("createdAt", server.createdAt)
          .put("lastSuccessfulCheck", server.lastSuccessfulCheck)
      )
    }
    prefs.edit().putString("servers", encrypt(array.toString())).apply()
  }

  private fun secretKey(): SecretKey {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    generator.init(
      KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    )
    return generator.generateKey()
  }

  private fun encrypt(value: String): String {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, secretKey())
    val iv = cipher.iv
    val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    return Base64.encodeToString(iv + body, Base64.NO_WRAP)
  }

  private fun decrypt(value: String): String {
    val raw = Base64.decode(value, Base64.NO_WRAP)
    require(raw.size > 12) { "Invalid encrypted VPS data" }
    val iv = raw.copyOfRange(0, 12)
    val body = raw.copyOfRange(12, raw.size)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
    return cipher.doFinal(body).toString(Charsets.UTF_8)
  }
}
