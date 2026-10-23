package com.android.zdtd.service.singboxlx.warp

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cloudflare WARP MASQUE registration and sing-box-lx config generation.
 *
 * The device private P-256 key is generated locally and only its public SPKI DER
 * is sent to Cloudflare. The initial /reg request uses a one-shot X25519 public
 * key, matching the current Cloudflare Android registration flow used by LxBox.
 */
data class WarpMasqueAccount(
  val privateKeyDer: String,
  val serverPublicKeyDer: String,
  val clientV4: String,
  val clientV6: String,
  val server: String,
  val port: Int,
  val deviceId: String,
  val token: String,
  val createdAt: String,
) {
  fun toJson(): JSONObject = JSONObject()
    .put("priv_key_der", privateKeyDer)
    .put("server_pub_der", serverPublicKeyDer)
    .put("client_v4", clientV4)
    .put("client_v6", clientV6)
    .put("server", server)
    .put("port", port)
    .put("device_id", deviceId)
    .put("token", token)
    .put("created_at", createdAt)

  companion object {
    fun fromJson(obj: JSONObject?): WarpMasqueAccount? {
      if (obj == null) return null
      val privateKey = obj.optString("priv_key_der").trim()
      val serverKey = obj.optString("server_pub_der").trim()
      val v4 = obj.optString("client_v4").trim()
      val v6 = obj.optString("client_v6").trim()
      val server = obj.optString("server").trim()
      val port = obj.optInt("port", 0)
      if (privateKey.isBlank() || serverKey.isBlank() || (v4.isBlank() && v6.isBlank()) || server.isBlank() || port !in 1..65535) {
        return null
      }
      return WarpMasqueAccount(
        privateKeyDer = privateKey,
        serverPublicKeyDer = serverKey,
        clientV4 = v4,
        clientV6 = v6,
        server = server,
        port = port,
        deviceId = obj.optString("device_id"),
        token = obj.optString("token"),
        createdAt = obj.optString("created_at"),
      )
    }
  }
}

enum class WarpMasqueFailure {
  NETWORK,
  API,
  RESPONSE,
  CRYPTO,
}

class WarpMasqueException(
  message: String,
  val failure: WarpMasqueFailure = WarpMasqueFailure.RESPONSE,
  val httpCode: Int? = null,
) : Exception(message)

class WarpMasqueRegistrar(
  private val client: OkHttpClient = DEFAULT_CLIENT,
) {
  private val jsonMediaType = "application/json; charset=UTF-8".toMediaType()

  fun register(): WarpMasqueAccount {
    val createdAt = Instant.now().toString()
    val oneShotPublicKey = generateX25519PublicKey()
    val ecKeys = generateP256Keys()

    val regBody = JSONObject()
      .put("key", Base64.getEncoder().encodeToString(oneShotPublicKey))
      .put("install_id", "")
      .put("fcm_token", "")
      .put("tos", createdAt)
      .put("model", "PC")
      .put("type", "Android")
      .put("locale", "en_US")
      .toString()

    val failedHosts = mutableListOf<String>()
    var activeHost: String? = null
    var regJson: JSONObject? = null

    for (host in API_HOSTS) {
      val request = Request.Builder()
        .url("$host/$API_VERSION/reg")
        .headers(apiHeaders())
        .post(regBody.toRequestBody(jsonMediaType))
        .build()
      try {
        client.newCall(request).execute().use { response ->
          val body = response.body.string()
          activeHost = host
          if (!response.isSuccessful) {
            throw WarpMasqueException(
              "registration failed (HTTP ${response.code})",
              WarpMasqueFailure.API,
              response.code,
            )
          }
          regJson = runCatching { JSONObject(body) }.getOrElse {
            throw WarpMasqueException(
              "registration failed: Cloudflare returned invalid JSON",
              WarpMasqueFailure.RESPONSE,
            )
          }
        }
        break
      } catch (e: WarpMasqueException) {
        throw e
      } catch (e: Exception) {
        failedHosts += "$host: ${e.javaClass.simpleName}"
      }
    }

    val host = activeHost ?: throw WarpMasqueException(
      "network error: ${failedHosts.joinToString("; ").ifBlank { "Cloudflare API is unreachable" }}",
      WarpMasqueFailure.NETWORK,
    )
    val registration = regJson ?: throw WarpMasqueException("registration failed: empty Cloudflare response", WarpMasqueFailure.RESPONSE)
    val deviceId = registration.optString("id").trim()
    val token = registration.optString("token").trim()
    if (deviceId.isBlank() || token.isBlank()) {
      throw WarpMasqueException(
        "registration failed: Cloudflare response is missing device credentials",
        WarpMasqueFailure.RESPONSE,
      )
    }

    val patchBody = JSONObject()
      .put("key", Base64.getEncoder().encodeToString(ecKeys.publicDer))
      .put("key_type", "secp256r1")
      .put("tunnel_type", "masque")
      .toString()
    val patch = Request.Builder()
      .url("$host/$API_VERSION/reg/$deviceId")
      .headers(apiHeaders(token))
      .patch(patchBody.toRequestBody(jsonMediaType))
      .build()

    val enrolled = try {
      client.newCall(patch).execute().use { response ->
        val body = response.body.string()
        if (!response.isSuccessful) {
          throw WarpMasqueException(
            "MASQUE enroll failed (HTTP ${response.code})",
            WarpMasqueFailure.API,
            response.code,
          )
        }
        runCatching { JSONObject(body) }.getOrElse {
          throw WarpMasqueException(
            "MASQUE enroll failed: Cloudflare returned invalid JSON",
            WarpMasqueFailure.RESPONSE,
          )
        }
      }
    } catch (e: WarpMasqueException) {
      throw e
    } catch (e: Exception) {
      throw WarpMasqueException(
        "MASQUE enroll network error: ${e.javaClass.simpleName}",
        WarpMasqueFailure.NETWORK,
      )
    }

    return parseEnrollment(
      enrolled,
      privateKeyDer = Base64.getEncoder().encodeToString(ecKeys.privateDer),
      deviceId = deviceId,
      token = token,
      createdAt = createdAt,
    )
  }

  private fun parseEnrollment(
    root: JSONObject,
    privateKeyDer: String,
    deviceId: String,
    token: String,
    createdAt: String,
  ): WarpMasqueAccount {
    val config = root.optJSONObject("config")
      ?: throw WarpMasqueException("MASQUE enroll failed: missing tunnel config")
    val addresses = config.optJSONObject("interface")?.optJSONObject("addresses")
    val clientV4 = addresses?.optString("v4")?.trim().orEmpty()
    val clientV6 = addresses?.optString("v6")?.trim().orEmpty()
    if (clientV4.isBlank() && clientV6.isBlank()) {
      throw WarpMasqueException("MASQUE enroll failed: missing interface address")
    }

    val peer = config.optJSONArray("peers")?.optJSONObject(0)
      ?: throw WarpMasqueException("MASQUE enroll failed: missing peer")
    val serverPublicKey = normalizeServerPublicKey(peer.optString("public_key"))
    if (serverPublicKey.isBlank()) {
      throw WarpMasqueException("MASQUE enroll failed: missing server public key")
    }

    val endpoint = peer.optJSONObject("endpoint")
    val server = stripEndpointHost(
      endpoint?.optString("v4")?.takeIf { it.isNotBlank() }
        ?: endpoint?.optString("v6").orEmpty()
    ).ifBlank { DEFAULT_SERVER }
    val ports = endpoint?.optJSONArray("ports")
    val port = ports?.optInt(0, DEFAULT_PORT)?.takeIf { it in 1..65535 } ?: DEFAULT_PORT

    return WarpMasqueAccount(
      privateKeyDer = privateKeyDer,
      serverPublicKeyDer = serverPublicKey,
      clientV4 = clientV4,
      clientV6 = clientV6,
      server = server,
      port = port,
      deviceId = deviceId,
      token = token,
      createdAt = createdAt,
    )
  }

  private fun apiHeaders(token: String? = null) = okhttp3.Headers.Builder()
    .add("Content-Type", "application/json")
    .add("User-Agent", USER_AGENT)
    .add("CF-Client-Version", CLIENT_VERSION)
    .apply { if (!token.isNullOrBlank()) add("Authorization", "Bearer $token") }
    .build()

  private fun generateX25519PublicKey(): ByteArray {
    val privateKey = X25519PrivateKeyParameters(SecureRandom())
    return privateKey.generatePublicKey().encoded
  }

  private data class P256KeyMaterial(val privateDer: ByteArray, val publicDer: ByteArray)

  private fun generateP256Keys(): P256KeyMaterial {
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
    val pair = generator.generateKeyPair()
    val privateKey = pair.private as? ECPrivateKey
      ?: throw WarpMasqueException("P-256 private key generation failed", WarpMasqueFailure.CRYPTO)
    val publicKey = pair.public as? ECPublicKey
      ?: throw WarpMasqueException("P-256 public key generation failed", WarpMasqueFailure.CRYPTO)

    val d = bigIntToFixed(privateKey.s, 32)
    val x = bigIntToFixed(publicKey.w.affineX, 32)
    val y = bigIntToFixed(publicKey.w.affineY, 32)
    val point = byteArrayOf(0x04) + x + y

    val privateDer = derSequence(
      derIntegerOne(),
      derOctetString(d),
      derTagged(0xA0, OID_P256),
      derTagged(0xA1, derBitString(point)),
    )
    val publicDer = derSequence(
      derSequence(OID_EC_PUBLIC_KEY, OID_P256),
      derBitString(point),
    )
    return P256KeyMaterial(privateDer, publicDer)
  }

  private fun bigIntToFixed(value: BigInteger, size: Int): ByteArray {
    val raw = value.toByteArray()
    val unsigned = if (raw.size > 1 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
    require(unsigned.size <= size) { "EC coordinate is too large" }
    return ByteArray(size).also { out ->
      System.arraycopy(unsigned, 0, out, size - unsigned.size, unsigned.size)
    }
  }

  private fun derIntegerOne() = byteArrayOf(0x02, 0x01, 0x01)
  private fun derOctetString(bytes: ByteArray) = der(0x04, bytes)
  private fun derBitString(bytes: ByteArray) = der(0x03, byteArrayOf(0x00) + bytes)
  private fun derTagged(tag: Int, inner: ByteArray) = der(tag, inner)
  private fun derSequence(vararg parts: ByteArray) = der(0x30, parts.fold(ByteArray(0)) { acc, bytes -> acc + bytes })

  private fun der(tag: Int, content: ByteArray): ByteArray =
    byteArrayOf(tag.toByte()) + derLength(content.size) + content

  private fun derLength(length: Int): ByteArray = when {
    length < 0x80 -> byteArrayOf(length.toByte())
    length <= 0xFF -> byteArrayOf(0x81.toByte(), length.toByte())
    else -> byteArrayOf(0x82.toByte(), (length ushr 8).toByte(), length.toByte())
  }

  private fun normalizeServerPublicKey(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""
    val body = if (trimmed.contains("-----BEGIN")) {
      trimmed
        .replace(Regex("-----BEGIN[^-]*-----"), "")
        .replace(Regex("-----END[^-]*-----"), "")
        .replace(Regex("\\s"), "")
    } else {
      trimmed.replace(Regex("\\s"), "")
    }
    return runCatching {
      val der = Base64.getDecoder().decode(body)
      val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)) as? ECPublicKey
        ?: error("not an EC public key")
      require(key.params.curve.field.fieldSize == 256) { "server EC key is not P-256" }
      body
    }.getOrElse {
      throw WarpMasqueException(
        "MASQUE enroll failed: invalid server public key",
        WarpMasqueFailure.RESPONSE,
      )
    }
  }

  private fun stripEndpointHost(raw: String): String {
    val value = raw.trim()
    if (value.startsWith("[")) return value.substringAfter('[').substringBefore(']')
    if (value.count { it == ':' } > 1 && value.endsWith(":0")) return value.dropLast(2)
    val colons = value.count { it == ':' }
    if (colons == 1) {
      val suffix = value.substringAfterLast(':')
      if (suffix.toIntOrNull() != null) return value.substringBeforeLast(':')
    }
    return value
  }

  companion object {
    private val DEFAULT_CLIENT = OkHttpClient.Builder()
      .connectTimeout(5, TimeUnit.SECONDS)
      .readTimeout(5, TimeUnit.SECONDS)
      .writeTimeout(5, TimeUnit.SECONDS)
      .callTimeout(7, TimeUnit.SECONDS)
      .build()

    private val API_HOSTS = listOf(
      "https://api.devices.cloudflare.com",
      "https://api.cloudflareclient.com",
    )
    private const val API_VERSION = "v0a2158"
    private const val CLIENT_VERSION = "a-7.21-0721"
    private const val USER_AGENT = "okhttp/3.12.1"
    private const val DEFAULT_SERVER = "162.159.198.1"
    private const val DEFAULT_PORT = 443

    // DER-encoded object identifiers.
    private val OID_EC_PUBLIC_KEY = byteArrayOf(0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01)
    private val OID_P256 = byteArrayOf(0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07)
  }
}

data class WarpMasqueNodeOptions(
  val name: String = "WARP MASQUE",
  val vhttp: String = "auto",
  val server: String = "",
  val port: Int? = null,
  val sni: String = "www.cloudflare.com",
  val mtu: Int = 1280,
  val idleTimeoutMinutes: Int? = null,
  val keepAliveSeconds: Int? = null,
)

object WarpMasqueConfigFactory {
  fun build(account: WarpMasqueAccount, options: WarpMasqueNodeOptions, mixedPort: Int): String {
    require(mixedPort in 1..65535) { "Invalid local proxy port" }
    val vhttp = options.vhttp.trim().lowercase().takeIf { it in setOf("auto", "h3", "h2") } ?: "auto"
    val endpoint = options.server.trim().ifBlank { account.server }
    require(endpoint.isNotBlank()) { "Missing MASQUE endpoint" }
    val endpointPort = options.port?.takeIf { it in 1..65535 } ?: account.port
    val mtu = options.mtu.coerceIn(576, 16000)

    val outbound = JSONObject()
      .put("type", "masque")
      .put("tag", "proxy")
      .put("server", endpoint)
      .put("server_port", endpointPort)
      .put("profile", "cloudflare")
      .put("vhttp", vhttp)
      .put("private_key", account.privateKeyDer)
      .put("public_key", account.serverPublicKeyDer)
      .put("mtu", mtu)

    account.clientV4.takeIf { it.isNotBlank() }?.let { outbound.put("ip", ensureCidr(it, false)) }
    account.clientV6.takeIf { it.isNotBlank() }?.let { outbound.put("ipv6", ensureCidr(it, true)) }
    options.sni.trim().takeIf { it.isNotBlank() }?.let {
      outbound.put("tls", JSONObject().put("server_name", it))
    }
    options.idleTimeoutMinutes?.takeIf { it > 0 }?.let { outbound.put("idle_timeout", "${it}m") }
    if (vhttp != "h2") {
      options.keepAliveSeconds?.takeIf { it > 0 }?.let { outbound.put("keep_alive_period", "${it}s") }
    }

    val dnsRules = JSONArray().put(
      JSONObject().put("domain", JSONArray().put("dns.google")).put("server", "dns-direct")
    )
    if (!isIpLiteral(endpoint)) {
      dnsRules.put(JSONObject().put("domain", JSONArray().put(endpoint)).put("server", "dns-direct"))
    }
    dnsRules.put(JSONObject().put("query_type", JSONArray().put("A").put("AAAA")).put("server", "dns-fake"))

    val dns = JSONObject()
      .put("servers", JSONArray()
        .put(JSONObject().put("type", "udp").put("tag", "dns-direct").put("server", "8.8.8.8").put("server_port", 53))
        .put(JSONObject().put("type", "udp").put("tag", "dns-remote").put("server", "8.8.8.8").put("server_port", 53).put("detour", "proxy"))
        .put(JSONObject().put("type", "fakeip").put("tag", "dns-fake").put("inet4_range", "198.18.0.0/15").put("inet6_range", "fc00::/18")))
      .put("rules", dnsRules)
      .put("final", "dns-remote")
      .put("strategy", "ipv4_only")

    val inbounds = JSONArray().put(
      JSONObject()
        .put("listen", "127.0.0.1")
        .put("listen_port", mixedPort)
        .put("tag", "mixed-in")
        .put("type", "mixed")
    )
    val outbounds = JSONArray()
      .put(outbound)
      .put(JSONObject().put("tag", "direct").put("type", "direct"))
      .put(JSONObject().put("tag", "bypass").put("type", "direct"))
    val route = JSONObject()
      .put("auto_detect_interface", true)
      .put("default_domain_resolver", JSONObject().put("server", "dns-direct").put("strategy", "ipv4_only"))
      .put("rule_set", JSONArray())
      .put("rules", JSONArray()
        .put(JSONObject().put("inbound", JSONArray().put("mixed-in")).put("action", "sniff"))
        .put(JSONObject().put("action", "hijack-dns").put("port", JSONArray().put(53)))
        .put(JSONObject().put("action", "hijack-dns").put("protocol", JSONArray().put("dns")))
        .put(JSONObject().put("action", "reject")
          .put("ip_cidr", JSONArray().put("224.0.0.0/3").put("ff00::/8"))
          .put("source_ip_cidr", JSONArray().put("224.0.0.0/3").put("ff00::/8"))))
      .put("final", "proxy")

    return JSONObject()
      .put("dns", dns)
      .put("inbounds", inbounds)
      .put("log", JSONObject().put("level", "panic"))
      .put("outbounds", outbounds)
      .put("route", route)
      .toString(2)
  }

  private fun ensureCidr(value: String, ipv6: Boolean): String {
    val trimmed = value.trim()
    return if ('/' in trimmed) trimmed else "$trimmed/${if (ipv6) 128 else 32}"
  }

  private fun isIpLiteral(value: String): Boolean {
    if (':' in value) return true
    val parts = value.split('.')
    return parts.size == 4 && parts.all { part -> part.toIntOrNull() in 0..255 }
  }
}
