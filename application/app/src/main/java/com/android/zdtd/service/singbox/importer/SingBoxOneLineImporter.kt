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
 * The LX-only XHTTP mapping follows the public LxBox/sing-box-lx protocol contract
 * (native xhttp, URI extra precedence, aliases and validation rules) rather than
 * silently translating XHTTP to another wire transport.
 *
 * This file is intentionally kept separate from UI code so the importer can evolve
 * without affecting the existing sing-box screen logic.
 */
data class SingBoxImportResult(
  val configJson: String,
  val detectedPort: Int?,
  val suggestedName: String?,
)

enum class SingBoxImportTarget {
  STANDARD,
  LX,
}

class SingBoxImportCompatibilityException(
  val feature: String,
  val requiredTarget: SingBoxImportTarget = SingBoxImportTarget.LX,
) : IllegalArgumentException("$feature requires sing-box-lx")

class SingBoxImportValidationException(
  val code: String,
) : IllegalArgumentException(code)

object SingBoxOneLineImporter {
  private val VLESS_ENCRYPTION_PATTERN = Regex("^mlkem768x25519plus(?:\\.[^.]+){3,}$")
  private val AWG_ROOT_FIELDS = setOf(
    "jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4",
    "i1", "i2", "i3", "i4", "i5", "id", "ip", "ib",
    "header_protection_key", "content_padding_addition", "rekey_after_time",
    "rekey_timeout", "reject_after_time", "keepalive_timeout", "max_handshake_attempts",
    "random_trailers", "disable_cookies",
  )

  fun import(
    rawInput: String,
    preferredMixedPort: Int,
    target: SingBoxImportTarget = SingBoxImportTarget.STANDARD,
  ): SingBoxImportResult {
    val text = rawInput.trim()
    require(text.isNotEmpty()) { "Empty source" }

    if (text.startsWith("{")) {
      val json = JSONObject(text)
      normalizeAndValidateTargetCompatibility(json, target)
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

    val outbound = parseShareLink(text, target)
    val config = buildLegacyConfig(outbound, preferredMixedPort)
    return SingBoxImportResult(
      configJson = config.toString(2),
      detectedPort = preferredMixedPort,
      suggestedName = outbound.nameHint,
    )
  }

  private fun parseShareLink(link: String, target: SingBoxImportTarget): OutboundSpec {
    val scheme = link.substringBefore("://", "").lowercase()
    return when (scheme) {
      "vless" -> parseVlessLike(link, isVmess = false, target = target)
      "vmess" -> parseVmess(link, target)
      "trojan" -> parseTrojan(link, target)
      "ss" -> parseShadowsocks(link)
      "socks", "socks5", "socks4", "socks4a" -> parseSocks(link)
      else -> error("Unsupported link: $scheme")
    }
  }

  private fun parseVmess(link: String, target: SingBoxImportTarget): OutboundSpec {
    if (!link.contains("?")) {
      return parseVmessBase64(link, target)
    }
    return parseVlessLike(link, isVmess = true, target = target)
  }

  private fun parseVlessLike(
    link: String,
    isVmess: Boolean,
    target: SingBoxImportTarget,
  ): OutboundSpec {
    val uri = URI(link)
    val query = parseQuery(uri.rawQuery).toMutableMap()
    preserveXhttpPathQuery(uri.rawQuery, query)
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

    val transport = buildTransportSpec(query, target)
    val vlessEncryption = if (isVmess) null else normalizeVlessEncryption(query["encryption"], target)
    val requestedFlow = if (!isVmess) query["flow"]?.takeIf { it.isNotBlank() } else null
    val effectiveFlow = when {
      isVmess -> null
      // LxBox/current sing-box-lx allows Vision above the VLESS encryption layer,
      // including transported nodes. Without encryption keep LX conservative and
      // drop Vision on any non-TCP transport. STANDARD keeps its legacy behavior.
      target == SingBoxImportTarget.LX && transport != null && vlessEncryption == null -> null
      else -> requestedFlow
    }
    return OutboundSpec(
      type = if (isVmess) "vmess" else "vless",
      nameHint = name,
      server = uri.host ?: error("Missing host"),
      serverPort = uri.port.takeIf { it > 0 } ?: error("Missing port"),
      uuid = decodeComponent(userInfo),
      vmessSecurity = if (isVmess) query["encryption"]?.takeIf { it.isNotBlank() } ?: "auto" else null,
      vlessEncryption = vlessEncryption,
      alterId = if (isVmess) query["alterId"]?.toIntOrNull() ?: 0 else null,
      flow = effectiveFlow,
      packetEncoding = query["packetEncoding"]?.takeIf { it.isNotBlank() },
      tls = tls,
      transport = transport,
    )
  }

  private fun parseVmessBase64(link: String, target: SingBoxImportTarget): OutboundSpec {
    val payload = link.substringAfter("vmess://")
      .substringBefore('#')
      .let(::decodeBase64Flexible)
    val json = JSONObject(payload)
    val query = linkedMapOf<String, String>()
    json.optString("net").takeIf { it.isNotBlank() }?.let { query["type"] = it }
    json.optString("host").takeIf { it.isNotBlank() }?.let { query["host"] = it }
    json.optString("path").takeIf { it.isNotBlank() }?.let { query["path"] = it }
    json.optString("mode").takeIf { it.isNotBlank() }?.let { query["mode"] = it }
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
      transport = buildTransportSpec(query, target),
    )
  }

  private fun parseTrojan(link: String, target: SingBoxImportTarget): OutboundSpec {
    val uri = URI(link)
    val query = parseQuery(uri.rawQuery).toMutableMap()
    preserveXhttpPathQuery(uri.rawQuery, query)
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
      transport = buildTransportSpec(query, target),
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
    val directDomains = linkedSetOf("dns.google")
    if (outbound.server.isNotBlank() && !outbound.server.contains(':') && !outbound.server.matches(Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$"))) {
      directDomains += outbound.server
    }
    val dnsRules = JSONArray()
      .put(
        JSONObject()
          .put("domain", JSONArray().also { arr -> directDomains.forEach { arr.put(it) } })
          .put("server", "dns-direct")
      )
      .put(
        JSONObject()
          .put("inbound", JSONArray().put("mixed-in"))
          .put("query_type", JSONArray().put("A").put("AAAA"))
          .put("server", "dns-fake")
          .put("disable_cache", true)
      )

    val dns = JSONObject()
      .put(
        "servers",
        JSONArray()
          .put(JSONObject().put("type", "local").put("tag", "dns-local"))
          .put(
            JSONObject()
              .put("type", "udp")
              .put("tag", "dns-direct")
              .put("server", "8.8.8.8")
              .put("server_port", 53)
          )
          .put(
            JSONObject()
              .put("type", "https")
              .put("tag", "dns-remote")
              .put("server", "dns.google")
              .put("server_port", 443)
              .put("path", "/dns-query")
              .put(
                "domain_resolver",
                JSONObject()
                  .put("server", "dns-direct")
                  .put("strategy", "ipv4_only")
              )
          )
          .put(
            JSONObject()
              .put("type", "fakeip")
              .put("tag", "dns-fake")
              .put("inet4_range", "198.18.0.0/15")
              .put("inet6_range", "fc00::/18")
          )
      )
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
      .put(outbound.toJson())
      .put(JSONObject().put("tag", "direct").put("type", "direct"))
      .put(JSONObject().put("tag", "bypass").put("type", "direct"))

    val routeRules = JSONArray()
      .put(JSONObject().put("inbound", JSONArray().put("mixed-in")).put("action", "sniff"))
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
      .put(
        "default_domain_resolver",
        JSONObject()
          .put("server", "dns-direct")
          .put("strategy", "ipv4_only")
      )
      .put("rule_set", JSONArray())
      .put("rules", routeRules)
      .put("final", "proxy")

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

  private val XHTTP_MODES = setOf("auto", "packet-up", "stream-up", "stream-one")
  private val XHTTP_PLACEMENTS = setOf("path", "query", "header", "cookie")
  private val XHTTP_UPLINK_PLACEMENTS = setOf("body", "auto", "header", "cookie")
  private val XHTTP_PADDING_PLACEMENTS = setOf("cookie", "header", "query", "queryInHeader")
  private val XHTTP_PADDING_METHODS = setOf("repeat-x", "tokenish")
  private val XHTTP_PERCENT_ESCAPE = Regex("%[0-9A-Fa-f]{2}")

  private fun buildTransportSpec(
    query: Map<String, String>,
    target: SingBoxImportTarget,
  ): TransportSpec? {
    val type = queryValueFold(query, "type")
      ?.lowercase()
      ?.replace("websocket", "ws")
      ?.replace("none", "tcp")
      ?.let { if (it == "splithttp") "xhttp" else it }
      ?: "tcp"
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
      "xhttp" -> {
        if (target != SingBoxImportTarget.LX) {
          throw SingBoxImportCompatibilityException("XHTTP")
        }
        buildXhttpTransportSpec(query)
      }
      else -> error("Unsupported transport: $type")
    }
  }

  /**
   * Map the Xray/LxBox XHTTP URI dialect to sing-box-lx native transport JSON.
   *
   * Compatibility rules intentionally mirror LxBox's current contract:
   * - mode/path/host are read only from the flat query layer;
   * - extended fields prefer the URL-encoded JSON `extra` object and then fall
   *   back to flat camelCase/snake_case query aliases;
   * - malformed `extra` is ignored instead of making the whole link unusable;
   * - invalid enum values are omitted so one bad field cannot make the entire
   *   sing-box-lx configuration fail to load;
   * - uplink_data_placement=header/cookie materializes packet-up when mode was
   *   omitted, but is dropped when an explicitly incompatible mode was given.
   */
  private fun buildXhttpTransportSpec(query: Map<String, String>): TransportSpec {
    val extra = parseXhttpExtra(query)
    val options = JSONObject()

    fun flat(vararg names: String): String? = queryValueFold(query, *names)?.takeIf { it.isNotBlank() }
    fun extraScalar(vararg names: String): String? =
      jsonValueFold(extra, *names)?.let(::valueAsString)?.takeIf { it.isNotBlank() }
    fun extended(vararg names: String): String? = extraScalar(*names) ?: flat(*names)
    fun extendedBool(vararg names: String): Boolean? {
      val fromExtra = extraScalar(*names)
      if (fromExtra != null) return parseBooleanSpelled(fromExtra)
      return flat(*names)?.let(::parseBooleanSpelled)
    }
    fun extendedInt(vararg names: String): Int? {
      val fromExtra = extraScalar(*names)
      if (fromExtra != null) return parseFlexibleInt(fromExtra)
      return flat(*names)?.let(::parseFlexibleInt)
    }
    val extraXmux = jsonObjectFold(extra, "xmux")
    fun extendedXmux(vararg names: String): String? =
      extraScalar(*names)
        ?: jsonValueFold(extraXmux, *names)?.let(::valueAsString)?.takeIf { it.isNotBlank() }
        ?: flat(*names)
    fun extendedXmuxInt(vararg names: String): Int? {
      val fromExtra = extraScalar(*names)
        ?: jsonValueFold(extraXmux, *names)?.let(::valueAsString)?.takeIf { it.isNotBlank() }
      if (fromExtra != null) return parseFlexibleInt(fromExtra)
      return flat(*names)?.let(::parseFlexibleInt)
    }

    val rawMode = flat("mode")
    var mode = rawMode?.takeIf { it in XHTTP_MODES }

    queryValueFold(query, "path")?.let { options.put("path", it.ifEmpty { "/" }) }
    flat("host")?.let { options.put("host", it) }

    extended("xPaddingBytes", "x_padding_bytes")?.let { options.put("x_padding_bytes", it) }

    val sessionPlacement = extended("sessionPlacement", "session_placement", "sessionIDPlacement")
      ?.takeIf { it in XHTTP_PLACEMENTS }
    sessionPlacement?.let { options.put("session_placement", it) }
    extended("sessionKey", "session_key", "sessionIDKey")?.let { options.put("session_key", it) }

    val seqPlacement = extended("seqPlacement", "seq_placement")
      ?.takeIf { it in XHTTP_PLACEMENTS }
    seqPlacement?.let { options.put("seq_placement", it) }
    extended("seqKey", "seq_key")?.let { options.put("seq_key", it) }

    var uplinkPlacement = extended("uplinkDataPlacement", "uplink_data_placement")
      ?.takeIf { it in XHTTP_UPLINK_PLACEMENTS }
    if (uplinkPlacement == "header" || uplinkPlacement == "cookie") {
      when {
        rawMode.isNullOrBlank() -> mode = "packet-up"
        mode != "packet-up" -> uplinkPlacement = null
      }
    }
    uplinkPlacement?.let { options.put("uplink_data_placement", it) }
    extended("uplinkDataKey", "uplink_data_key")?.let { options.put("uplink_data_key", it) }
    extended("uplinkChunkSize", "uplink_chunk_size")?.let { options.put("uplink_chunk_size", it) }
    extended("uplinkHTTPMethod", "uplink_http_method")?.let { options.put("uplink_http_method", it) }

    extendedBool("xPaddingObfsMode", "x_padding_obfs_mode")
      ?.takeIf { it }
      ?.let { options.put("x_padding_obfs_mode", true) }
    extended("xPaddingKey", "x_padding_key")?.let { options.put("x_padding_key", it) }
    extended("xPaddingHeader", "x_padding_header")?.let { options.put("x_padding_header", it) }
    extended("xPaddingPlacement", "x_padding_placement")
      ?.takeIf { it in XHTTP_PADDING_PLACEMENTS }
      ?.let { options.put("x_padding_placement", it) }
    extended("xPaddingMethod", "x_padding_method")
      ?.takeIf { it in XHTTP_PADDING_METHODS }
      ?.let { options.put("x_padding_method", it) }

    extended("scMaxEachPostBytes", "sc_max_each_post_bytes")
      ?.let(::normalizeScRange)
      ?.let { options.put("sc_max_each_post_bytes", it) }
    extended("scMinPostsIntervalMs", "sc_min_posts_interval_ms")
      ?.let(::normalizeScRange)
      ?.let { options.put("sc_min_posts_interval_ms", it) }
    extended("scStreamUpServerSecs", "sc_stream_up_server_secs")
      ?.let(::normalizeScRange)
      ?.let { options.put("sc_stream_up_server_secs", it) }
    extendedBool("noGRPCHeader", "no_grpc_header")
      ?.takeIf { it }
      ?.let { options.put("no_grpc_header", true) }
    extendedBool("noSSEHeader", "no_sse_header")
      ?.takeIf { it }
      ?.let { options.put("no_sse_header", true) }
    extendedInt("scMaxBufferedPosts", "sc_max_buffered_posts")?.let { options.put("sc_max_buffered_posts", it) }

    val xmux = JSONObject()
    extendedXmux("maxConcurrency", "max_concurrency")?.let { xmux.put("max_concurrency", it) }
    extendedXmux("maxConnections", "max_connections")?.let { xmux.put("max_connections", it) }
    extendedXmux("cMaxReuseTimes", "c_max_reuse_times")?.let { xmux.put("c_max_reuse_times", it) }
    extendedXmux("hMaxRequestTimes", "h_max_request_times")?.let { xmux.put("h_max_request_times", it) }
    extendedXmux("hMaxReusableSecs", "h_max_reusable_secs")?.let { xmux.put("h_max_reusable_secs", it) }
    extendedXmuxInt("hKeepAlivePeriod", "h_keep_alive_period")?.let { xmux.put("h_keep_alive_period", it) }
    if (xmux.length() > 0) options.put("xmux", xmux)

    mode?.let { options.put("mode", it) }

    return TransportSpec(type = "xhttp", xhttpOptions = options)
  }

  private fun normalizeAndValidateTargetCompatibility(json: JSONObject, target: SingBoxImportTarget) {
    fun inspectNode(node: JSONObject, isEndpoint: Boolean) {
      val type = node.optString("type", "").trim().lowercase()

      if (type == "vless") {
        val encryption = node.optString("encryption", "").trim()
        if (encryption.isNotBlank() && !encryption.equals("none", ignoreCase = true)) {
          if (!VLESS_ENCRYPTION_PATTERN.matches(encryption)) {
            throw SingBoxImportValidationException("vless_encryption_invalid")
          }
          if (target != SingBoxImportTarget.LX) {
            throw SingBoxImportCompatibilityException("VLESS + ML-KEM-768")
          }
          node.put("encryption", encryption)
        }
      }

      if (type == "masque" && target != SingBoxImportTarget.LX) {
        throw SingBoxImportCompatibilityException("MASQUE")
      }

      if (type == "wireguard" && hasAmneziaWgExtension(node) && target != SingBoxImportTarget.LX) {
        throw SingBoxImportCompatibilityException("AmneziaWG")
      }

      val transport = node.optJSONObject("transport")
      if (transport != null) {
        val transportType = transport.optString("type", "").trim().lowercase()
        if (transportType == "xhttp" || transportType == "splithttp") {
          if (target != SingBoxImportTarget.LX) {
            throw SingBoxImportCompatibilityException("XHTTP")
          }
          if (transportType == "splithttp") transport.put("type", "xhttp")
        }
      }

      // WireGuard/AWG is an endpoint in sing-box 1.12+. Keep accepting the old
      // outbound location for hand-written configs, but capability-gate both.
      if (isEndpoint && type == "wireguard" && target == SingBoxImportTarget.LX) {
        // No rewriting: AWG 1.x/2.x/3.x fields are intentionally raw-preserved
        // and validated by the pinned sing-box-lx core itself.
      }
    }

    json.optJSONArray("outbounds")?.let { items ->
      for (i in 0 until items.length()) items.optJSONObject(i)?.let { inspectNode(it, false) }
    }
    json.optJSONArray("endpoints")?.let { items ->
      for (i in 0 until items.length()) items.optJSONObject(i)?.let { inspectNode(it, true) }
    }
  }

  private fun normalizeVlessEncryption(raw: String?, target: SingBoxImportTarget): String? {
    val encryption = raw?.trim().orEmpty()
    if (encryption.isBlank() || encryption.equals("none", ignoreCase = true)) return null
    if (!VLESS_ENCRYPTION_PATTERN.matches(encryption)) {
      throw SingBoxImportValidationException("vless_encryption_invalid")
    }
    if (target != SingBoxImportTarget.LX) {
      throw SingBoxImportCompatibilityException("VLESS + ML-KEM-768")
    }
    return encryption
  }

  private fun hasAmneziaWgExtension(node: JSONObject): Boolean {
    if (AWG_ROOT_FIELDS.any { node.has(it) }) return true
    val peers = node.optJSONArray("peers") ?: return false
    for (i in 0 until peers.length()) {
      val peer = peers.optJSONObject(i) ?: continue
      val keepalive = peer.opt("persistent_keepalive_interval")?.toString()?.trim().orEmpty()
      if (Regex("^\\d+\\s*-\\s*\\d+$").matches(keepalive)) return true
    }
    return false
  }

  private fun parseXhttpExtra(query: Map<String, String>): JSONObject? {
    val raw = queryValueFold(query, "extra")?.trim().orEmpty()
    if (raw.isBlank()) return null
    val candidates = linkedSetOf(raw)
    runCatching { decodeComponent(raw) }.getOrNull()?.takeIf { it.isNotBlank() }?.let(candidates::add)
    for (candidate in candidates) {
      val parsed = runCatching { JSONObject(candidate) }.getOrNull()
      if (parsed != null) return parsed
    }
    return null
  }

  private fun queryValueFold(query: Map<String, String>, vararg names: String): String? {
    names.forEach { name ->
      query.entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
        ?.value
        ?.let { return it }
    }
    return null
  }

  private fun jsonValueFold(json: JSONObject?, vararg names: String): Any? {
    if (json == null) return null
    names.forEach { name ->
      val keys = json.keys()
      while (keys.hasNext()) {
        val key = keys.next()
        if (key.equals(name, ignoreCase = true)) {
          val value = json.opt(key)
          if (value != null && value != JSONObject.NULL) return value
        }
      }
    }
    return null
  }

  private fun jsonObjectFold(json: JSONObject?, vararg names: String): JSONObject? =
    jsonValueFold(json, *names) as? JSONObject

  private fun valueAsString(value: Any): String? = when (value) {
    is JSONObject, is JSONArray -> null
    is Number -> normalizeNumberString(value)
    is Boolean -> value.toString()
    else -> value.toString()
  }

  private fun parseFlexibleInt(raw: String): Int? {
    val value = raw.trim()
    value.toIntOrNull()?.let { return it }
    return value.toDoubleOrNull()?.toInt()
  }

  private fun normalizeScRange(raw: String): String {
    val value = raw.trim()
    val asDouble = value.toDoubleOrNull() ?: return value
    return if (asDouble.isFinite() && asDouble == asDouble.toLong().toDouble()) {
      asDouble.toLong().toString()
    } else {
      value
    }
  }

  private fun normalizeNumberString(value: Number): String {
    val raw = value.toString()
    if (!raw.contains('.') && !raw.contains('e', ignoreCase = true)) return raw
    return runCatching {
      java.math.BigDecimal(raw).stripTrailingZeros().toPlainString()
    }.getOrDefault(raw)
  }

  private fun parseBooleanSpelled(raw: String): Boolean? = when (raw.trim().lowercase()) {
    "1", "true" -> true
    "0", "false" -> false
    else -> null
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

  private fun preserveXhttpPathQuery(rawQuery: String?, query: MutableMap<String, String>) {
    val type = queryValueFold(query, "type")?.trim()?.lowercase()
    if (type != "xhttp" && type != "splithttp") return
    if (rawQuery.isNullOrBlank()) return
    rawQuery.split('&').forEach { pair ->
      if (pair.isBlank()) return@forEach
      val rawKey = pair.substringBefore('=')
      val key = decodeComponent(rawKey)
      if (!key.equals("path", ignoreCase = true)) return@forEach
      val rawValue = pair.substringAfter('=', "")
      // LxBox/Xray path semantics keep '+' literal. URLDecoder normally turns
      // it into a space, so protect it before percent-decoding this one field.
      var value = URLDecoder.decode(rawValue.replace("+", "%2B"), StandardCharsets.UTF_8.name())
      repeat(2) {
        if (!XHTTP_PERCENT_ESCAPE.containsMatchIn(value)) return@repeat
        val decoded = runCatching {
          URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrNull() ?: return@repeat
        if (decoded == value) return@repeat
        value = decoded
      }
      // Collapse case variants to one canonical key so queryValueFold cannot
      // pick the URLDecoder-decoded (and therefore '+'-mangled) copy first.
      query.keys.filter { it.equals("path", ignoreCase = true) }.toList().forEach(query::remove)
      query["path"] = value
      return
    }
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
    val vlessEncryption: String? = null,
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
        .put(
          "domain_resolver",
          JSONObject()
            .put("server", "dns-direct")
            .put("strategy", "prefer_ipv4")
        )

      when (type) {
        "vless" -> {
          out.put("uuid", uuid)
          vlessEncryption?.let { out.put("encryption", it) }
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
          "xhttp" -> {
            transportSpec.xhttpOptions?.let { options ->
              val keys = options.keys()
              while (keys.hasNext()) {
                val key = keys.next()
                transportJson.put(key, options.get(key))
              }
            }
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
    val xhttpOptions: JSONObject? = null,
  )
}
