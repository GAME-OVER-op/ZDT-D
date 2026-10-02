package com.android.zdtd.service

import android.content.Context
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

data class NonRootTgWsConfig(
  val enabled: Boolean = false,
  val port: Int = 1443,
  val secret: String = "",
  val fakeTlsEnabled: Boolean = false,
  val fakeTlsDomain: String = "",
  val defaultDomains: Boolean = false,
  val cfDomains: List<String> = emptyList(),
  val cfWorkerDomains: List<String> = emptyList(),
  val cfPriority: Boolean = false,
  val cfBalance: Boolean = false,
  val mtprotoProxies: List<String> = emptyList(),
  val dcIp: List<String> = emptyList(),
  val frontingDomain: String = "",
  val frontingCooldown: Long = 1800L,
  val bufKb: Int = 256,
  val poolSize: Int = 4,
  val maxConnections: Int = 0,
  val verbose: Boolean = false,
  val quiet: Boolean = false,
  val outboundProxy: String = "",
  val noOutboundProxy: Boolean = false,
  val noProxy: String = "",
  val skipTlsVerify: Boolean = false,
)

/** App-owned Telegram WS Proxy settings. Non-root always binds loopback only. */
class NonRootTgWsStore(context: Context) {
  private val appContext = context.applicationContext
  private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  private val ports = NonRootPortRegistry(appContext)

  @Synchronized
  fun load(): NonRootTgWsConfig {
    val obj = prefs.getString(KEY_JSON, null)?.takeIf { it.isNotBlank() }
      ?.let { runCatching { JSONObject(it) }.getOrNull() }
    val port = ports.getPersisted(PORT_KEY)
      ?: obj?.optInt("port", 0)?.takeIf { it in NonRootPortRegistry.MIN_PORT..65535 }
        ?.takeIf { ports.set(PORT_KEY, it) }
      ?: ports.getOrAllocate(PORT_KEY)
    val config = if (obj == null) NonRootTgWsConfig(port = port, secret = generateSecret()) else fromJson(obj).copy(port = port)
    if (obj == null || config.secret.isBlank()) {
      return save(config.copy(secret = config.secret.ifBlank(::generateSecret)))
    }
    return config
  }

  @Synchronized
  fun save(config: NonRootTgWsConfig): NonRootTgWsConfig {
    val normalized = normalize(config)
    val currentPort = ports.getPersisted(PORT_KEY)
    val effectivePort = if (currentPort == normalized.port || ports.set(PORT_KEY, normalized.port)) {
      normalized.port
    } else {
      currentPort ?: ports.getOrAllocate(PORT_KEY)
    }
    val effective = normalized.copy(port = effectivePort)
    prefs.edit().putString(KEY_JSON, toJson(effective).toString()).apply()
    return effective
  }

  @Synchronized
  fun setPort(port: Int): NonRootTgWsConfig? {
    if (!ports.set(PORT_KEY, port)) return null
    val next = load().copy(port = port)
    prefs.edit().putString(KEY_JSON, toJson(next).toString()).apply()
    return next
  }

  private fun normalize(c: NonRootTgWsConfig): NonRootTgWsConfig = c.copy(
    secret = normalizeSecret(c.secret),
    fakeTlsDomain = c.fakeTlsDomain.trim(),
    cfDomains = clean(c.cfDomains),
    cfWorkerDomains = clean(c.cfWorkerDomains),
    mtprotoProxies = clean(c.mtprotoProxies),
    dcIp = clean(c.dcIp),
    frontingDomain = c.frontingDomain.trim(),
    frontingCooldown = c.frontingCooldown.coerceAtLeast(0L),
    bufKb = c.bufKb.coerceIn(16, 65536),
    poolSize = c.poolSize.coerceIn(1, 128),
    maxConnections = c.maxConnections.coerceAtLeast(0),
    quiet = c.quiet,
    verbose = c.verbose && !c.quiet,
    outboundProxy = c.outboundProxy.trim(),
    noProxy = c.noProxy.trim(),
  )

  private fun fromJson(obj: JSONObject): NonRootTgWsConfig = NonRootTgWsConfig(
    enabled = obj.optBoolean("enabled", false),
    port = obj.optInt("port", 1443),
    secret = obj.optString("secret", ""),
    fakeTlsEnabled = obj.optBoolean("fake_tls_enabled", false),
    fakeTlsDomain = obj.optString("fake_tls_domain", ""),
    defaultDomains = obj.optBoolean("default_domains", false),
    cfDomains = arrayToList(obj.optJSONArray("cf_domains")),
    cfWorkerDomains = arrayToList(obj.optJSONArray("cf_worker_domains")),
    cfPriority = obj.optBoolean("cf_priority", false),
    cfBalance = obj.optBoolean("cf_balance", false),
    mtprotoProxies = arrayToList(obj.optJSONArray("mtproto_proxies")),
    dcIp = arrayToList(obj.optJSONArray("dc_ip")),
    frontingDomain = obj.optString("fronting_domain", ""),
    frontingCooldown = obj.optLong("fronting_cooldown", 1800L),
    bufKb = obj.optInt("buf_kb", 256),
    poolSize = obj.optInt("pool_size", 4),
    maxConnections = obj.optInt("max_connections", 0),
    verbose = obj.optBoolean("verbose", false),
    quiet = obj.optBoolean("quiet", false),
    outboundProxy = obj.optString("outbound_proxy", ""),
    noOutboundProxy = obj.optBoolean("no_outbound_proxy", false),
    noProxy = obj.optString("no_proxy", ""),
    skipTlsVerify = obj.optBoolean("skip_tls_verify", false),
  )

  private fun toJson(c: NonRootTgWsConfig): JSONObject = JSONObject()
    .put("enabled", c.enabled)
    .put("port", c.port)
    .put("secret", c.secret)
    .put("fake_tls_enabled", c.fakeTlsEnabled)
    .put("fake_tls_domain", c.fakeTlsDomain)
    .put("default_domains", c.defaultDomains)
    .put("cf_domains", listToArray(c.cfDomains))
    .put("cf_worker_domains", listToArray(c.cfWorkerDomains))
    .put("cf_priority", c.cfPriority)
    .put("cf_balance", c.cfBalance)
    .put("mtproto_proxies", listToArray(c.mtprotoProxies))
    .put("dc_ip", listToArray(c.dcIp))
    .put("fronting_domain", c.frontingDomain)
    .put("fronting_cooldown", c.frontingCooldown)
    .put("buf_kb", c.bufKb)
    .put("pool_size", c.poolSize)
    .put("max_connections", c.maxConnections)
    .put("verbose", c.verbose)
    .put("quiet", c.quiet)
    .put("outbound_proxy", c.outboundProxy)
    .put("no_outbound_proxy", c.noOutboundProxy)
    .put("no_proxy", c.noProxy)
    .put("skip_tls_verify", c.skipTlsVerify)

  companion object {
    private const val PREFS = "non_root_tgws"
    private const val KEY_JSON = "config_json"
    const val PORT_KEY = "tgws.port"

    fun normalizeSecret(raw: String): String {
      var value = raw.trim().lowercase()
      if ((value.startsWith("dd") || value.startsWith("ee")) && value.length >= 34) value = value.substring(2, 34)
      return value.take(32)
    }

    fun isValidSecret(raw: String): Boolean = normalizeSecret(raw).matches(Regex("^[0-9a-f]{32}$"))

    fun generateSecret(): String {
      val bytes = ByteArray(16)
      SecureRandom().nextBytes(bytes)
      return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun clean(items: List<String>): List<String> = items.map(String::trim).filter(String::isNotEmpty).distinct()
    private fun arrayToList(arr: JSONArray?): List<String> = if (arr == null) emptyList() else buildList {
      for (i in 0 until arr.length()) arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let(::add)
    }
    private fun listToArray(items: List<String>): JSONArray = JSONArray().also { arr ->
      items.forEach { arr.put(it) }
    }
  }
}
