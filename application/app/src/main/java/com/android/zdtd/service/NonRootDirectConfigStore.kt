package com.android.zdtd.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val DEFAULT_BYEDPI_START_ARGS = "-n m.vk.com vk.com max.ru gosuslugi.ru sun6-20.userapi.com ok.ru online.sberbank.ru -Qr -f6+nr -d2 -d11 -f9+hm -o3 -t7 -a1 -As -d1 -s3+s -s5+s -q7 -a1 -As -o2 -f-43 -a1 -As -r5 -Mh -s1:5+s -s3:7+sm -a1 -o1 -d1 -a1"
private const val DEFAULT_BYEDPI_RESTART_ARGS = "-x 1 -s 43690:2:43690"
private const val DEFAULT_API_PROXY = "https://raw.githubusercontent.com/stormsia/proxy-list/main/working_proxies.txt"
private const val DEFAULT_API_USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
private val DEFAULT_BOOTSTRAP_DNS = listOf(
  "https://xbox-dns.ru/dns-query",
  "tls://9.9.9.9:853",
  "https://ru-mow.doh.sb/dns-query",
  "https://1.1.1.3/dns-query",
  "https://security.cloudflare-dns.com/dns-query",
  "https://wikimedia-dns.org/dns-query",
  "https://dns.adguard-dns.com/dns-query",
  "https://dns.quad9.net/dns-query",
  "https://dns.comss.one/dns-query",
  "https://router.comss.one/dns-query",
  "https://dns.google/dns-query",
  "tls://dns.google:853",
  "https://cloudflare-dns.com/dns-query",
  "tls://cloudflare-dns.com:853",
  "tls://dns.adguard-dns.com:853",
  "https://family.adguard-dns.com/dns-query",
  "https://unfiltered.adguard-dns.com/dns-query",
  "https://dns.mullvad.net/dns-query",
  "https://adblock.dns.mullvad.net/dns-query",
  "https://protective.joindns4.eu/dns-query",
  "https://doh.kel.pe",
  "https://dns.adnull.com/dns-query",
  "https://dns.alidns.com/dns-query",
  "https://adfreedns.top/dns-query",
)

data class NonRootSniEntry(
  val sni: String = "",
  val useByedpi: Boolean = false,
  val overrideProxyAddress: String = "",
)

data class NonRootDirectOperaConfig(
  val byedpiStartArgs: String = DEFAULT_BYEDPI_START_ARGS,
  val byedpiRestartArgs: String = DEFAULT_BYEDPI_RESTART_ARGS,
  val restartByedpiAfterOpera: Boolean = false,
  val serverRegion: String = "EU",
  val sniEntries: List<NonRootSniEntry> = listOf(
    NonRootSniEntry(sni = "m.vk.com", useByedpi = true),
    NonRootSniEntry(sni = "vk.com", useByedpi = false),
  ),
  val apiProxy: String = DEFAULT_API_PROXY,
  val apiUserAgent: String = DEFAULT_API_USER_AGENT,
  val initRetryInterval: String = "3s",
  val serverSelection: String = "fastest",
  val serverSelectionDlLimit: String = "204800",
  val serverSelectionTestUrl: String = "https://ajax.googleapis.com/ajax/libs/angularjs/1.8.2/angular.min.js",
  val verbosity: String = "50",
  val bootstrapDns: List<String> = DEFAULT_BOOTSTRAP_DNS,
)

internal fun nonRootOperaConfigFromJson(obj: JSONObject): NonRootDirectOperaConfig {
  if (obj.length() == 0) return NonRootDirectOperaConfig()
  val defaults = NonRootDirectOperaConfig()
  val sni = obj.optJSONArray("sni")
  val sniEntries = if (sni == null) {
    defaults.sniEntries
  } else buildList {
    for (i in 0 until sni.length()) {
      val item = sni.optJSONObject(i) ?: continue
      add(
        NonRootSniEntry(
          sni = item.optString("sni", ""),
          useByedpi = item.optBoolean("use_byedpi", false),
          overrideProxyAddress = item.optString("override_proxy_address", ""),
        )
      )
    }
  }
  val dns = obj.optJSONArray("bootstrap_dns")
  val bootstrapDns = if (dns == null) {
    defaults.bootstrapDns
  } else buildList {
    for (i in 0 until dns.length()) {
      dns.optString(i).trim().takeIf { it.isNotEmpty() }?.let { add(it) }
    }
  }
  val region = obj.optString("server_region", "EU").uppercase().takeIf { it in setOf("EU", "AS", "AM") } ?: "EU"
  return NonRootDirectOperaConfig(
    byedpiStartArgs = obj.optString("byedpi_start_args", defaults.byedpiStartArgs),
    byedpiRestartArgs = obj.optString("byedpi_restart_args", defaults.byedpiRestartArgs),
    restartByedpiAfterOpera = obj.optBoolean("restart_byedpi_after_opera", false),
    serverRegion = region,
    sniEntries = sniEntries,
    apiProxy = obj.optString("api_proxy", defaults.apiProxy),
    apiUserAgent = obj.optString("api_user_agent", defaults.apiUserAgent),
    initRetryInterval = obj.optString("init_retry_interval", "3s"),
    serverSelection = obj.optString("server_selection", "fastest"),
    serverSelectionDlLimit = obj.optString("server_selection_dl_limit", "204800"),
    serverSelectionTestUrl = obj.optString("server_selection_test_url", "https://ajax.googleapis.com/ajax/libs/angularjs/1.8.2/angular.min.js"),
    verbosity = obj.optString("verbosity", "50"),
    bootstrapDns = bootstrapDns,
  )
}

internal fun nonRootOperaConfigToJson(config: NonRootDirectOperaConfig): JSONObject = JSONObject().apply {
  put("byedpi_start_args", config.byedpiStartArgs)
  put("byedpi_restart_args", config.byedpiRestartArgs)
  put("restart_byedpi_after_opera", config.restartByedpiAfterOpera)
  put("server_region", config.serverRegion)
  put("sni", JSONArray().apply {
    config.sniEntries.forEach { item ->
      put(JSONObject().apply {
        put("sni", item.sni)
        put("use_byedpi", item.useByedpi)
        put("override_proxy_address", item.overrideProxyAddress)
      })
    }
  })
  put("api_proxy", config.apiProxy)
  put("api_user_agent", config.apiUserAgent)
  put("init_retry_interval", config.initRetryInterval)
  put("server_selection", config.serverSelection)
  put("server_selection_dl_limit", config.serverSelectionDlLimit)
  put("server_selection_test_url", config.serverSelectionTestUrl)
  put("verbosity", config.verbosity)
  put("bootstrap_dns", JSONArray().apply { config.bootstrapDns.forEach { put(it) } })
}

/** Persistent Direct-mode configuration, kept separate from Cascade profiles. */
class NonRootDirectConfigStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun load(): NonRootDirectOperaConfig {
    val raw = prefs.getString(KEY_CONFIG, null)?.takeIf { it.isNotBlank() } ?: return NonRootDirectOperaConfig()
    return runCatching { nonRootOperaConfigFromJson(JSONObject(raw)) }.getOrDefault(NonRootDirectOperaConfig())
  }

  fun save(config: NonRootDirectOperaConfig) {
    prefs.edit().putString(KEY_CONFIG, nonRootOperaConfigToJson(config).toString()).apply()
  }

  companion object {
    private const val PREFS_NAME = "non_root_direct"
    private const val KEY_CONFIG = "opera_config"
  }
}
