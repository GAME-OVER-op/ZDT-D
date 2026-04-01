package com.android.zdtd.service.singbox.importer

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * ZDT-D sing-box import helpers.
 *
 * Parsing approach and supported share-link formats are adapted from the open-source
 * NekoBox for Android project:
 * https://github.com/MatsuriDayo/NekoBoxForAndroid
 *
 * This file is intentionally kept separate from UI code so the importer can evolve
 * without affecting the existing sing-box screen logic.
 */
data class SingBoxImportResult(
  val configJson: String,
  val detectedPort: Int?,
  val suggestedName: String?,
)

object SingBoxOneLineImporter {
  fun import(rawInput: String, preferredMixedPort: Int): SingBoxImportResult {
    val text = rawInput.trim()
    require(text.isNotEmpty()) { "Empty source" }

    if (text.startsWith("{")) {
      val json = JSONObject(text)
      val normalized = json.toString(2)
      return SingBoxImportResult(
        configJson = normalized,
        detectedPort = findMixedInboundPort(json),
        suggestedName = json.optJSONArray("outbounds")
          ?.optJSONObject(0)
          ?.optString("tag")
          ?.takeIf { it.isNotBlank() && it != "proxy" },
      )
    }

    val outbound = parseShareLink(text)
    val config = buildLegacyConfig(outbound, preferredMixedPort)
    return SingBoxImportResult(
      configJson = config.toString(2),
      detectedPort = preferredMixedPort,
      suggestedName = outbound.nameHint,
    )
  }

  private fun parseShareLink(link: String): OutboundSpec {
    val scheme = link.substringBefore("://", "").lowercase()
    return when (scheme) {
      "vless" -> parseVlessLike(link, isVmess = false)
      "vmess" -> parseVmess(link)
      "trojan" -> parseTrojan(link)
      "ss" -> parseShadowsocks(link)
      "socks", "socks5", "socks4", "socks4a" -> parseSocks(link)
      else -> error("Unsupported link: $scheme")
    }
  }

  private fun parseVmess(link: String): OutboundSpec {
    if (!link.contains("?")) {
      return parseVmessBase64(link)
    }
    return parseVlessLike(link, isVmess = true)
  }

  private fun parseVlessLike(link: String, isVmess: Boolean): OutboundSpec {
    val uri = URI(link)
    val query = parseQuery(uri.rawQuery)
    val name = decodeComponent(uri.rawFragment)
    val userInfo = uri.rawUserInfo.orEmpty()
    require(userInfo.isNotBlank()) { "Missing user info" }

    val tls = buildTlsSpec(
      security = query["security"],
      allowInsecure = query["allowInsecure"],
      sni = query["sni"] ?: query["serverName"] ?: query["peer"] ?: query["host"],
      alpn = query["alpn"],
      fp = query["fp"],
      realityPublicKey = query["pbk"],
      realityShortId = query["sid"],
      defaultTls = false,
    )

    return OutboundSpec(
      type = if (isVmess) "vmess" else "vless",
      nameHint = name,
      server = uri.host ?: error("Missing host"),
      serverPort = uri.port.takeIf { it > 0 } ?: error("Missing port"),
      uuid = decodeComponent(userInfo),
      vmessSecurity = if (isVmess) query["encryption"]?.takeIf { it.isNotBlank() } ?: "auto" else null,
      alterId = if (isVmess) query["alterId"]?.toIntOrNull() ?: 0 else null,
      flow = if (!isVmess) query["flow"]?.takeIf { it.isNotBlank() } else null,
      packetEncoding = query["packetEncoding"]?.takeIf { it.isNotBlank() },
      tls = tls,
      transport = buildTransportSpec(query),
    )
  }

  private fun parseVmessBase64(link: String): OutboundSpec {
    val payload = link.substringAfter("vmess://")
      .substringBefore('#')
      .let(::decodeBase64Flexible)
    val json = JSONObject(payload)
    val query = linkedMapOf<String, String>()
    json.optString("net").takeIf { it.isNotBlank() }?.let { query["type"] = it }
    json.optString("host").takeIf { it.isNotBlank() }?.let { query["host"] = it }
    json.optString("path").takeIf { it.isNotBlank() }?.let { query["path"] = it }
    json.optString("sni").takeIf { it.isNotBlank() }?.let { query["sni"] = it }
    json.optString("alpn").takeIf { it.isNotBlank() }?.let { query["alpn"] = it }
    json.optString("fp").takeIf { it.isNotBlank() }?.let { query["fp"] = it }
    json.optString("tls").takeIf { it.isNotBlank() }?.let { query["security"] = it }

    val tls = buildTlsSpec(
      security = query["security"],
      allowInsecure = null,
      sni = query["sni"] ?: query["host"],
      alpn = query["alpn"],
      fp = query["fp"],
      realityPublicKey = null,
      realityShortId = null,
      defaultTls = false,
    )

    return OutboundSpec(
      type = "vmess",
      nameHint = json.optString("ps").takeIf { it.isNotBlank() },
      server = json.optString("add").takeIf { it.isNotBlank() } ?: error("Missing host"),
      serverPort = json.optString("port").toIntOrNull() ?: error("Missing port"),
      uuid = json.optString("id").takeIf { it.isNotBlank() } ?: error("Missing id"),
      alterId = json.optString("aid").toIntOrNull() ?: 0,
      vmessSecurity = json.optString("scy").takeIf { it.isNotBlank() } ?: "auto",
      tls = tls,
      transport = buildTransportSpec(query),
    )
  }

  private fun parseTrojan(link: String): OutboundSpec {
    val uri = URI(link)
    val query = parseQuery(uri.rawQuery)
    val userInfo = uri.rawUserInfo.orEmpty()
    require(userInfo.isNotBlank()) { "Missing password" }
    return OutboundSpec(
      type = "trojan",
      nameHint = decodeComponent(uri.rawFragment),
      server = uri.host ?: error("Missing host"),
      serverPort = uri.port.takeIf { it > 0 } ?: error("Missing port"),
      password = decodeComponent(userInfo),
      tls = buildTlsSpec(
        security = query["security"],
        allowInsecure = query["allowInsecure"],
        sni = query["sni"] ?: query["peer"] ?: query["host"],
        alpn = query["alpn"],
        fp = query["fp"],
        realityPublicKey = query["pbk"],
        realityShortId = query["sid"],
        defaultTls = true,
      ),
      transport = buildTransportSpec(query),
    )
  }

  private fun parseShadowsocks(link: String): OutboundSpec {
    val body = link.substringAfter("ss://")
    val basePart = body.substringBefore('#')
    val fragment = decodeComponent(body.substringAfter('#', ""))

    val (userInfo, hostPort, plugin) = if (basePart.contains('@')) {
      val uri = URI("https://$basePart")
      Triple(uri.rawUserInfo.orEmpty(), "${uri.host}:${uri.port}", parseQuery(uri.rawQuery)["plugin"])
    } else {
      val decoded = decodeBase64Flexible(basePart.substringBefore('?'))
      val tail = basePart.substringAfter('?', "")
      val pluginParam = parseQuery(tail)["plugin"]
      val joined = if ('@' in decoded) decoded else error("Invalid shadowsocks link")
      val u = URI("https://$joined")
      Triple(u.rawUserInfo.orEmpty(), "${u.host}:${u.port}", pluginParam)
    }

    val credential = if (':' in userInfo) userInfo else decodeBase64Flexible(userInfo)
    val method = credential.substringBefore(':')
    val password = credential.substringAfter(':', "")
    require(method.isNotBlank()) { "Missing method" }
    require(password.isNotBlank()) { "Missing password" }

    val host = hostPort.substringBeforeLast(':')
    val port = hostPort.substringAfterLast(':').toIntOrNull() ?: error("Missing port")

    return OutboundSpec(
      type = "shadowsocks",
      nameHint = fragment.takeIf { it.isNotBlank() },
      server = host,
      serverPort = port,
      method = method,
      password = password,
      plugin = plugin,
    )
  }

  private fun parseSocks(link: String): OutboundSpec {
    val scheme = link.substringBefore("://").lowercase()
    val uri = URI(link)
    return OutboundSpec(
      type = "socks",
      nameHint = decodeComponent(uri.rawFragment),
      server = uri.host ?: error("Missing host"),
      serverPort = uri.port.takeIf { it > 0 } ?: error("Missing port"),
      username = decodeComponent(uri.rawUserInfo?.substringBefore(':')),
      password = decodeComponent(uri.rawUserInfo?.substringAfter(':', "")),
      socksVersion = when (scheme) {
        "socks4" -> "4"
        "socks4a" -> "4a"
        else -> "5"
      },
    )
  }

  private fun buildLegacyConfig(outbound: OutboundSpec, mixedPort: Int): JSONObject {
    val dnsServers = JSONArray()
      .put(JSONObject().put("address", "rcode://success").put("tag", "dns-block"))
      .put(JSONObject().put("address", "local").put("detour", "direct").put("tag", "dns-local"))
      .put(
        JSONObject()
          .put("address", "https://223.5.5.5/dns-query")
          .put("address_resolver", "dns-local")
          .put("detour", "direct")
          .put("strategy", "ipv4_only")
          .put("tag", "dns-direct")
      )
      .put(
        JSONObject()
          .put("address", "https://dns.google/dns-query")
          .put("address_resolver", "dns-direct")
          .put("strategy", "ipv4_only")
          .put("tag", "dns-remote")
      )
      .put(
        JSONObject()
          .put("address", "fakeip")
          .put("strategy", "ipv4_only")
          .put("tag", "dns-fake")
      )

    val dnsRules = JSONArray()
      .put(JSONObject().put("domain", JSONArray().put("dns.google")).put("server", "dns-direct"))
      .put(JSONObject().put("outbound", JSONArray().put("any")).put("server", "dns-direct"))
      .put(
        JSONObject()
          .put("disable_cache", true)
          .put("inbound", JSONArray().put("tun-in"))
          .put("server", "dns-fake")
      )

    val dns = JSONObject()
      .put(
        "fakeip",
        JSONObject()
          .put("enabled", true)
          .put("inet4_range", "198.18.0.0/15")
          .put("inet6_range", "fc00::/18")
      )
      .put("final", "dns-remote")
      .put("independent_cache", true)
      .put("rules", dnsRules)
      .put("servers", dnsServers)

    val inbounds = JSONArray().put(
      JSONObject()
        .put("domain_strategy", "")
        .put("listen", "127.0.0.1")
        .put("listen_port", mixedPort)
        .put("sniff", true)
        .put("sniff_override_destination", false)
        .put("tag", "mixed-in")
        .put("type", "mixed")
    )

    val outbounds = JSONArray()
      .put(outbound.toJson())
      .put(JSONObject().put("tag", "direct").put("type", "direct"))
      .put(JSONObject().put("tag", "bypass").put("type", "direct"))

    val routeRules = JSONArray()
      .put(JSONObject().put("action", "hijack-dns").put("port", JSONArray().put(53)))
      .put(JSONObject().put("action", "hijack-dns").put("protocol", JSONArray().put("dns")))
      .put(
        JSONObject()
          .put("action", "reject")
          .put("ip_cidr", JSONArray().put("224.0.0.0/3").put("ff00::/8"))
          .put("source_ip_cidr", JSONArray().put("224.0.0.0/3").put("ff00::/8"))
      )

    val route = JSONObject()
      .put("auto_detect_interface", true)
      .put("rule_set", JSONArray())
      .put("rules", routeRules)

    return JSONObject()
      .put("dns", dns)
      .put("inbounds", inbounds)
      .put("log", JSONObject().put("level", "panic"))
      .put("outbounds", outbounds)
      .put("route", route)
  }

  private fun buildTlsSpec(
    security: String?,
    allowInsecure: String?,
    sni: String?,
    alpn: String?,
    fp: String?,
    realityPublicKey: String?,
    realityShortId: String?,
    defaultTls: Boolean,
  ): TlsSpec? {
    val rawSecurity = security?.lowercase().orEmpty()
    val tlsEnabled = defaultTls || rawSecurity == "tls" || rawSecurity == "reality"
    if (!tlsEnabled) return null
    return TlsSpec(
      enabled = true,
      insecure = allowInsecure == "1" || allowInsecure.equals("true", ignoreCase = true),
      serverName = sni?.takeIf { it.isNotBlank() },
      alpn = alpn.orEmpty().split(',', '\n').map { it.trim() }.filter { it.isNotBlank() },
      realityPublicKey = realityPublicKey?.takeIf { it.isNotBlank() },
      realityShortId = realityShortId?.takeIf { it.isNotBlank() },
      utlsFingerprint = when {
        fp.isNullOrBlank() && !realityPublicKey.isNullOrBlank() -> "chrome"
        fp.equals("none", ignoreCase = true) -> null
        else -> fp?.takeIf { it.isNotBlank() }
      },
    )
  }

  private fun buildTransportSpec(query: Map<String, String>): TransportSpec? {
    val type = query["type"]?.lowercase()?.replace("websocket", "ws")?.replace("none", "tcp") ?: "tcp"
    return when (type) {
      "tcp" -> null
      "ws" -> TransportSpec(
        type = "ws",
        path = query["path"]?.takeIf { it.isNotBlank() } ?: "/",
        host = query["host"]?.takeIf { it.isNotBlank() },
        maxEarlyData = query["ed"]?.toIntOrNull(),
        earlyDataHeaderName = query["eh"]?.takeIf { it.isNotBlank() },
      )
      "http" -> TransportSpec(
        type = "http",
        path = query["path"]?.takeIf { it.isNotBlank() } ?: "/",
        host = query["host"]?.takeIf { it.isNotBlank() },
      )
      "grpc" -> TransportSpec(
        type = "grpc",
        serviceName = query["serviceName"]?.takeIf { it.isNotBlank() } ?: query["path"]?.takeIf { it.isNotBlank() },
      )
      "httpupgrade" -> TransportSpec(
        type = "httpupgrade",
        path = query["path"]?.takeIf { it.isNotBlank() },
        host = query["host"]?.takeIf { it.isNotBlank() },
      )
      else -> null
    }
  }

  private fun findMixedInboundPort(json: JSONObject): Int? {
    val inbounds = json.optJSONArray("inbounds") ?: return null
    for (i in 0 until inbounds.length()) {
      val inbound = inbounds.optJSONObject(i) ?: continue
      if (inbound.optString("type") == "mixed" && inbound.optInt("listen_port", 0) in 1..65535) {
        return inbound.optInt("listen_port")
      }
    }
    return null
  }

  private fun parseQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    rawQuery.split('&').forEach { pair ->
      if (pair.isBlank()) return@forEach
      val key = decodeComponent(pair.substringBefore('='))
      val value = decodeComponent(pair.substringAfter('=', ""))
      if (key.isNotBlank()) out[key] = value
    }
    return out
  }

  private fun decodeComponent(raw: String?): String {
    if (raw.isNullOrEmpty()) return ""
    return URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
  }

  private fun decodeBase64Flexible(raw: String): String {
    val normalized = raw.trim().replace('-', '+').replace('_', '/')
    val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
    val decoded = runCatching {
      Base64.getDecoder().decode(padded)
    }.getOrElse {
      Base64.getUrlDecoder().decode(raw)
    }
    return String(decoded, StandardCharsets.UTF_8)
  }

  private data class OutboundSpec(
    val type: String,
    val nameHint: String?,
    val server: String,
    val serverPort: Int,
    val uuid: String? = null,
    val password: String? = null,
    val method: String? = null,
    val username: String? = null,
    val socksVersion: String? = null,
    val alterId: Int? = null,
    val vmessSecurity: String? = null,
    val flow: String? = null,
    val packetEncoding: String? = null,
    val plugin: String? = null,
    val tls: TlsSpec? = null,
    val transport: TransportSpec? = null,
  ) {
    fun toJson(): JSONObject {
      val out = JSONObject()
        .put("tag", "proxy")
        .put("type", type)
        .put("server", server)
        .put("server_port", serverPort)
        .put("domain_strategy", "prefer_ipv4")

      when (type) {
        "vless" -> {
          out.put("uuid", uuid)
          flow?.let { out.put("flow", it) }
          packetEncoding?.let { out.put("packet_encoding", it) }
        }
        "vmess" -> {
          out.put("uuid", uuid)
          out.put("alter_id", alterId ?: 0)
          out.put("security", vmessSecurity ?: "auto")
          packetEncoding?.let { out.put("packet_encoding", it) }
        }
        "trojan" -> out.put("password", password)
        "shadowsocks" -> {
          out.put("method", method)
          out.put("password", password)
          plugin?.takeIf { it.isNotBlank() }?.let { rawPlugin ->
            val pluginId = rawPlugin.substringBefore(';')
            val pluginOpts = rawPlugin.substringAfter(';', "")
            out.put("plugin", pluginId)
            if (pluginOpts.isNotBlank()) out.put("plugin_opts", pluginOpts)
          }
        }
        "socks" -> {
          username?.takeIf { it.isNotBlank() }?.let { out.put("username", it) }
          password?.takeIf { it.isNotBlank() }?.let { out.put("password", it) }
          socksVersion?.let { out.put("version", it) }
        }
      }

      tls?.let { tlsSpec ->
        val tlsJson = JSONObject()
          .put("enabled", tlsSpec.enabled)
          .put("insecure", tlsSpec.insecure)
        tlsSpec.serverName?.let { tlsJson.put("server_name", it) }
        if (tlsSpec.alpn.isNotEmpty()) {
          tlsJson.put("alpn", JSONArray(tlsSpec.alpn))
        }
        if (!tlsSpec.realityPublicKey.isNullOrBlank()) {
          tlsJson.put(
            "reality",
            JSONObject()
              .put("enabled", true)
              .put("public_key", tlsSpec.realityPublicKey)
              .put("short_id", tlsSpec.realityShortId.orEmpty())
          )
        }
        tlsSpec.utlsFingerprint?.let {
          tlsJson.put(
            "utls",
            JSONObject()
              .put("enabled", true)
              .put("fingerprint", it)
          )
        }
        out.put("tls", tlsJson)
      }

      transport?.let { transportSpec ->
        val transportJson = JSONObject().put("type", transportSpec.type)
        when (transportSpec.type) {
          "ws" -> {
            transportJson.put("path", transportSpec.path ?: "/")
            transportSpec.host?.let { host ->
              transportJson.put("headers", JSONObject().put("Host", host))
            }
            transportSpec.maxEarlyData?.let { transportJson.put("max_early_data", it) }
            transportSpec.earlyDataHeaderName?.let { transportJson.put("early_data_header_name", it) }
          }
          "http" -> {
            transportJson.put("path", transportSpec.path ?: "/")
            transportSpec.host?.let { host ->
              val hosts = host.split(',').map { it.trim() }.filter { it.isNotBlank() }
              transportJson.put("host", JSONArray(hosts))
            }
          }
          "grpc" -> {
            transportSpec.serviceName?.let { transportJson.put("service_name", it) }
          }
          "httpupgrade" -> {
            transportSpec.host?.let { transportJson.put("host", it) }
            transportSpec.path?.let { transportJson.put("path", it) }
          }
        }
        out.put("transport", transportJson)
      }

      return out
    }
  }

  private data class TlsSpec(
    val enabled: Boolean,
    val insecure: Boolean,
    val serverName: String?,
    val alpn: List<String>,
    val realityPublicKey: String?,
    val realityShortId: String?,
    val utlsFingerprint: String?,
  )

  private data class TransportSpec(
    val type: String,
    val path: String? = null,
    val host: String? = null,
    val serviceName: String? = null,
    val maxEarlyData: Int? = null,
    val earlyDataHeaderName: String? = null,
  )
}
