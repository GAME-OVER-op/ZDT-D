package com.android.zdtd.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class NonRootCascadeBackendMode {
  BALANCE,
  PRIORITY,
}

enum class NonRootCascadeRouteItemType {
  PROFILE,
  GROUP,
  DIRECT_START,
  DIRECT_BLOCK,
}

data class NonRootCascadeRouteItem(
  val type: NonRootCascadeRouteItemType,
  val profileId: String = "",
  val markerId: String = "",
  val name: String = "",
)

data class NonRootBackendServer(
  val id: String,
  val name: String,
  val enabled: Boolean = true,
  val port: Int,
  val auxPort: Int = 0,
  val configText: String = "",
  val logLevel: String = "info",
)

data class NonRootCascadeProfile(
  val id: String,
  val name: String,
  val enabled: Boolean = true,
  val toolId: String = TOOL_OPERA_PROXY,
  val port: Int,
  val byedpiPort: Int,
  val operaConfig: NonRootDirectOperaConfig = NonRootDirectOperaConfig(),
  val servers: List<NonRootBackendServer> = emptyList(),
) {
  val serverCount: Int get() = if (toolId == TOOL_OPERA_PROXY) 1 else servers.size
  val directEligible: Boolean get() = serverCount == 1

  companion object {
    const val TOOL_OPERA_PROXY = "operaproxy"
    const val TOOL_HYSTERIA2 = "hysteria2"
    const val TOOL_SING_BOX = "sing-box"
    const val TOOL_MIERU = "mieru"
    const val TOOL_WIREPROXY = "wireproxy"

    val SUPPORTED_TOOLS = listOf(
      TOOL_OPERA_PROXY,
      TOOL_HYSTERIA2,
      TOOL_SING_BOX,
      TOOL_MIERU,
      TOOL_WIREPROXY,
    )

    fun normalizeToolId(raw: String): String = when (raw.trim().lowercase()) {
      "hysteria", "hysteria2" -> TOOL_HYSTERIA2
      "singbox", "sing-box", "sing_box" -> TOOL_SING_BOX
      "mieru" -> TOOL_MIERU
      "wireproxy", "wire-proxy", "wire_proxy" -> TOOL_WIREPROXY
      else -> TOOL_OPERA_PROXY
    }
  }
}

data class NonRootT2sConfig(
  val prioritySpeedAware: Boolean = false,
  val maxConnections: Int = 100,
  val idleTimeoutSeconds: Int = 600,
  val connectTimeoutSeconds: Int = 8,
  val bufferSize: Int = 65536,
  val downloadLimitMbit: String = "0",
  val peerCoordination: Boolean = true,
  val serializeBackendConnects: Boolean = true,
  val connectStaggerMs: Int = 100,
)

data class NonRootCascadeState(
  val profiles: List<NonRootCascadeProfile> = emptyList(),
  val directSelectedProfileId: String = "",
  val backendMode: NonRootCascadeBackendMode = NonRootCascadeBackendMode.BALANCE,
  val route: List<NonRootCascadeRouteItem> = emptyList(),
  val t2s: NonRootT2sConfig = NonRootT2sConfig(),
)

/** Persistent non-root profile/Cascade configuration. */
class NonRootCascadeStore(context: Context) {
  private val appContext = context.applicationContext
  private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val portRegistry = NonRootPortRegistry(appContext)

  @Synchronized
  fun load(): NonRootCascadeState {
    val raw = prefs.getString(KEY_STATE, null)?.takeIf { it.isNotBlank() }
      ?: return NonRootCascadeState()
    return runCatching { fromJson(JSONObject(raw)) }.getOrElse { NonRootCascadeState() }
  }

  @Synchronized
  fun createProfile(name: String, toolId: String = NonRootCascadeProfile.TOOL_OPERA_PROXY): NonRootCascadeState {
    val current = load()
    val id = UUID.randomUUID().toString()
    val tool = NonRootCascadeProfile.normalizeToolId(toolId)
    val firstServer = if (tool == NonRootCascadeProfile.TOOL_OPERA_PROXY) null else createServerModel(id, tool, DEFAULT_SERVER_NAME)
    val profile = NonRootCascadeProfile(
      id = id,
      name = normalizedName(name, tool),
      toolId = tool,
      port = if (tool == NonRootCascadeProfile.TOOL_OPERA_PROXY) portRegistry.getOrAllocate(portKey(id)) else firstServer!!.port,
      byedpiPort = if (tool == NonRootCascadeProfile.TOOL_OPERA_PROXY) portRegistry.getOrAllocate(byedpiPortKey(id)) else 0,
      servers = listOfNotNull(firstServer),
    )
    val insertAt = current.route.indexOfLast { it.type == NonRootCascadeRouteItemType.DIRECT_BLOCK }
      .takeIf { it >= 0 } ?: current.route.size
    val route = current.route.toMutableList().apply {
      add(insertAt, NonRootCascadeRouteItem(NonRootCascadeRouteItemType.PROFILE, id))
    }
    val direct = current.directSelectedProfileId.ifBlank { if (profile.directEligible) id else "" }
    return persist(current.copy(
      profiles = current.profiles + profile,
      directSelectedProfileId = direct,
      route = normalizeRoute(route, current.profiles + profile),
    ))
  }

  @Synchronized
  fun importLegacyDirectProfileIfNeeded(config: NonRootDirectOperaConfig?): NonRootCascadeState {
    val current = load()
    if (config == null || prefs.getBoolean(KEY_LEGACY_DIRECT_IMPORTED, false)) return current

    val id = UUID.randomUUID().toString()
    val legacyPort = portRegistry.getPersisted(NonRootPortRegistry.DIRECT_OPERA_KEY)
    val legacyByeDpiPort = portRegistry.getPersisted(NonRootPortRegistry.DIRECT_BYEDPI_KEY)
    portRegistry.clear(NonRootPortRegistry.DIRECT_OPERA_KEY)
    portRegistry.clear(NonRootPortRegistry.DIRECT_BYEDPI_KEY)
    val profilePort = legacyPort?.takeIf { portRegistry.set(portKey(id), it) }
      ?: portRegistry.getOrAllocate(portKey(id))
    val profileByeDpiPort = legacyByeDpiPort?.takeIf { portRegistry.set(byedpiPortKey(id), it) }
      ?: portRegistry.getOrAllocate(byedpiPortKey(id))
    val profile = NonRootCascadeProfile(
      id = id,
      name = DEFAULT_DIRECT_PROFILE_NAME,
      enabled = false,
      port = profilePort,
      byedpiPort = profileByeDpiPort,
      operaConfig = config,
    )
    val profiles = current.profiles + profile
    val insertAt = current.route.indexOfLast { it.type == NonRootCascadeRouteItemType.DIRECT_BLOCK }
      .takeIf { it >= 0 } ?: current.route.size
    val route = current.route.toMutableList().apply {
      add(insertAt, NonRootCascadeRouteItem(NonRootCascadeRouteItemType.PROFILE, id))
    }
    prefs.edit().putBoolean(KEY_LEGACY_DIRECT_IMPORTED, true).apply()
    return persist(current.copy(
      profiles = profiles,
      directSelectedProfileId = current.directSelectedProfileId.ifBlank { id },
      route = normalizeRoute(route, profiles),
    ))
  }

  @Synchronized
  fun setDirectSelectedProfile(profileId: String?): NonRootCascadeState {
    val current = load()
    val selected = profileId.orEmpty().takeIf { id ->
      current.profiles.firstOrNull { it.id == id }?.directEligible == true
    }.orEmpty()
    return persist(current.copy(directSelectedProfileId = selected))
  }

  @Synchronized
  fun updateProfile(profile: NonRootCascadeProfile): NonRootCascadeState {
    val current = load()
    val old = current.profiles.firstOrNull { it.id == profile.id } ?: return current
    val tool = NonRootCascadeProfile.normalizeToolId(profile.toolId)
    val normalized = profile.copy(
      name = normalizedName(profile.name, tool),
      toolId = tool,
      port = if (tool == NonRootCascadeProfile.TOOL_OPERA_PROXY) profile.port else profile.servers.firstOrNull()?.port ?: old.port,
      byedpiPort = if (tool == NonRootCascadeProfile.TOOL_OPERA_PROXY) profile.byedpiPort else 0,
    )
    val profiles = current.profiles.map { if (it.id == profile.id) normalized else it }
    val direct = current.directSelectedProfileId.takeUnless { it == normalized.id && !normalized.directEligible }.orEmpty()
    return persist(current.copy(profiles = profiles, directSelectedProfileId = direct, route = normalizeRoute(current.route, profiles)))
  }

  @Synchronized
  fun addServer(profileId: String, name: String = DEFAULT_SERVER_NAME, configText: String? = null): NonRootCascadeState {
    val current = load()
    val profile = current.profiles.firstOrNull { it.id == profileId } ?: return current
    if (profile.toolId == NonRootCascadeProfile.TOOL_OPERA_PROXY) return current
    val server = createServerModel(profile.id, profile.toolId, normalizedServerName(name, profile.servers.size + 1), configText)
    val profiles = current.profiles.map {
      if (it.id == profile.id) it.copy(servers = it.servers + server, port = it.servers.firstOrNull()?.port ?: server.port) else it
    }
    val direct = current.directSelectedProfileId.takeUnless { it == profileId }.orEmpty()
    return persist(current.copy(profiles = profiles, directSelectedProfileId = direct))
  }

  @Synchronized
  fun moveServer(profileId: String, fromIndex: Int, toIndex: Int): NonRootCascadeState {
    val current = load()
    val profile = current.profiles.firstOrNull { it.id == profileId } ?: return current
    if (fromIndex !in profile.servers.indices || toIndex !in profile.servers.indices || fromIndex == toIndex) return current
    val reordered = profile.servers.toMutableList().apply {
      val item = removeAt(fromIndex)
      add(toIndex, item)
    }
    val profiles = current.profiles.map { item ->
      if (item.id == profileId) item.copy(servers = reordered, port = reordered.firstOrNull()?.port ?: item.port) else item
    }
    return persist(current.copy(profiles = profiles))
  }

  @Synchronized
  fun updateServer(profileId: String, server: NonRootBackendServer): NonRootCascadeState {
    val current = load()
    val profile = current.profiles.firstOrNull { it.id == profileId } ?: return current
    if (profile.servers.none { it.id == server.id }) return current
    val normalized = server.copy(name = server.name.trim().ifBlank { DEFAULT_SERVER_NAME })
    val profiles = current.profiles.map { item ->
      if (item.id != profileId) item else item.copy(
        servers = item.servers.map { if (it.id == server.id) normalized else it },
        port = if (item.servers.firstOrNull()?.id == server.id) normalized.port else item.port,
      )
    }
    return persist(current.copy(profiles = profiles))
  }

  @Synchronized
  fun deleteServer(profileId: String, serverId: String): NonRootCascadeState {
    val current = load()
    val profile = current.profiles.firstOrNull { it.id == profileId } ?: return current
    val target = profile.servers.firstOrNull { it.id == serverId } ?: return current
    portRegistry.clear(serverPortKey(profileId, target.id))
    portRegistry.clear(serverAuxPortKey(profileId, target.id))
    val profiles = current.profiles.map { item ->
      if (item.id != profileId) item else {
        val servers = item.servers.filterNot { it.id == serverId }
        item.copy(servers = servers, port = servers.firstOrNull()?.port ?: item.port)
      }
    }
    val updated = profiles.first { it.id == profileId }
    val direct = current.directSelectedProfileId.takeUnless { it == profileId && !updated.directEligible }.orEmpty()
    return persist(current.copy(profiles = profiles, directSelectedProfileId = direct))
  }

  @Synchronized
  fun setServerPort(profileId: String, serverId: String, port: Int): NonRootCascadeState? {
    val current = load()
    val profile = current.profiles.firstOrNull { it.id == profileId } ?: return null
    val server = profile.servers.firstOrNull { it.id == serverId } ?: return null
    if (!portRegistry.set(serverPortKey(profileId, serverId), port)) return null
    return updateServer(profileId, server.copy(port = port))
  }

  @Synchronized
  fun setServerAuxPort(profileId: String, serverId: String, port: Int): NonRootCascadeState? {
    val current = load()
    val profile = current.profiles.firstOrNull { it.id == profileId } ?: return null
    val server = profile.servers.firstOrNull { it.id == serverId } ?: return null
    if (profile.toolId != NonRootCascadeProfile.TOOL_MIERU) return null
    if (!portRegistry.set(serverAuxPortKey(profileId, serverId), port)) return null
    return updateServer(profileId, server.copy(auxPort = port))
  }

  @Synchronized
  fun setProfilePort(profileId: String, port: Int): NonRootCascadeState? {
    val current = load()
    val profile = current.profiles.firstOrNull { it.id == profileId } ?: return null
    if (profile.toolId != NonRootCascadeProfile.TOOL_OPERA_PROXY) {
      val only = profile.servers.singleOrNull() ?: return null
      return setServerPort(profileId, only.id, port)
    }
    if (!portRegistry.set(portKey(profileId), port)) return null
    val profiles = current.profiles.map { if (it.id == profileId) profile.copy(port = port) else it }
    return persist(current.copy(profiles = profiles))
  }

  @Synchronized
  fun setProfileByeDpiPort(profileId: String, port: Int): NonRootCascadeState? {
    val current = load()
    val profile = current.profiles.firstOrNull { it.id == profileId } ?: return null
    if (profile.toolId != NonRootCascadeProfile.TOOL_OPERA_PROXY) return null
    if (!portRegistry.set(byedpiPortKey(profileId), port)) return null
    val profiles = current.profiles.map { if (it.id == profileId) profile.copy(byedpiPort = port) else it }
    return persist(current.copy(profiles = profiles))
  }

  @Synchronized
  fun setT2sConfig(config: NonRootT2sConfig): NonRootCascadeState {
    val current = load()
    return persist(current.copy(t2s = normalizeT2sConfig(config)))
  }

  @Synchronized
  fun deleteProfile(profileId: String): NonRootCascadeState {
    val current = load()
    current.profiles.firstOrNull { it.id == profileId }?.let { profile ->
      portRegistry.clear(portKey(profileId))
      portRegistry.clear(byedpiPortKey(profileId))
      profile.servers.forEach { server ->
        portRegistry.clear(serverPortKey(profileId, server.id))
        portRegistry.clear(serverAuxPortKey(profileId, server.id))
      }
    }
    val profiles = current.profiles.filterNot { it.id == profileId }
    val route = current.route.filterNot { it.type == NonRootCascadeRouteItemType.PROFILE && it.profileId == profileId }
    return persist(current.copy(
      profiles = profiles,
      directSelectedProfileId = current.directSelectedProfileId.takeUnless { it == profileId }.orEmpty(),
      route = normalizeRoute(route, profiles),
    ))
  }

  @Synchronized
  fun setBackendMode(mode: NonRootCascadeBackendMode): NonRootCascadeState {
    val current = load()
    val route = if (mode == NonRootCascadeBackendMode.BALANCE) {
      current.route.filterNot { it.type == NonRootCascadeRouteItemType.DIRECT_START || it.type == NonRootCascadeRouteItemType.DIRECT_BLOCK }
    } else current.route
    return persist(current.copy(backendMode = mode, route = route))
  }

  @Synchronized
  fun setRoute(route: List<NonRootCascadeRouteItem>): NonRootCascadeState {
    val current = load()
    val allowedRoute = if (current.backendMode == NonRootCascadeBackendMode.BALANCE) {
      route.filterNot { it.type == NonRootCascadeRouteItemType.DIRECT_START || it.type == NonRootCascadeRouteItemType.DIRECT_BLOCK }
    } else route
    return persist(current.copy(route = normalizeRoute(allowedRoute, current.profiles)))
  }

  fun cascadeServerPorts(profile: NonRootCascadeProfile): List<Int> = when (profile.toolId) {
    NonRootCascadeProfile.TOOL_OPERA_PROXY -> listOf(profile.port)
    else -> profile.servers.filter { it.enabled }.map { it.port }
  }

  fun directServerPort(profile: NonRootCascadeProfile): Int? = when (profile.toolId) {
    NonRootCascadeProfile.TOOL_OPERA_PROXY -> profile.port
    else -> profile.servers.singleOrNull()?.port
  }

  private fun fromJson(obj: JSONObject): NonRootCascadeState {
    val profilesArray = obj.optJSONArray("profiles") ?: JSONArray()
    val profiles = buildList {
      for (index in 0 until profilesArray.length()) {
        val item = profilesArray.optJSONObject(index) ?: continue
        val id = item.optString("id", "").trim()
        if (id.isEmpty()) continue
        val tool = NonRootCascadeProfile.normalizeToolId(item.optString("tool", NonRootCascadeProfile.TOOL_OPERA_PROXY))
        val servers = parseServers(id, tool, item.optJSONArray("servers"))
        add(
          NonRootCascadeProfile(
            id = id,
            name = normalizedName(item.optString("name", ""), tool),
            enabled = item.optBoolean("enabled", true),
            toolId = tool,
            port = if (tool == NonRootCascadeProfile.TOOL_OPERA_PROXY) portRegistry.getOrAllocate(portKey(id))
              else servers.firstOrNull()?.port ?: portRegistry.getOrAllocate(portKey(id)),
            byedpiPort = if (tool == NonRootCascadeProfile.TOOL_OPERA_PROXY) portRegistry.getOrAllocate(byedpiPortKey(id)) else 0,
            operaConfig = nonRootOperaConfigFromJson(item.optJSONObject("opera") ?: JSONObject()),
            servers = servers,
          )
        )
      }
    }

    val mode = runCatching {
      NonRootCascadeBackendMode.valueOf(obj.optString("backend_mode", "BALANCE").uppercase())
    }.getOrDefault(NonRootCascadeBackendMode.BALANCE)

    val routeArray = obj.optJSONArray("route") ?: JSONArray()
    val route = buildList {
      for (index in 0 until routeArray.length()) {
        val item = routeArray.optJSONObject(index) ?: continue
        val type = runCatching { NonRootCascadeRouteItemType.valueOf(item.optString("type", "").uppercase()) }.getOrNull() ?: continue
        add(NonRootCascadeRouteItem(
          type = type,
          profileId = item.optString("profile_id", ""),
          markerId = item.optString("marker_id", ""),
          name = item.optString("name", ""),
        ))
      }
    }

    val routeForMode = if (mode == NonRootCascadeBackendMode.BALANCE) {
      route.filterNot { it.type == NonRootCascadeRouteItemType.DIRECT_START || it.type == NonRootCascadeRouteItemType.DIRECT_BLOCK }
    } else route
    val direct = obj.optString("direct_selected_profile_id", "")
      .takeIf { selected -> profiles.firstOrNull { it.id == selected }?.directEligible == true }.orEmpty()
    return NonRootCascadeState(
      profiles = profiles,
      directSelectedProfileId = direct,
      backendMode = mode,
      route = normalizeRoute(routeForMode, profiles),
      t2s = t2sFromJson(obj.optJSONObject("t2s") ?: JSONObject()),
    )
  }

  private fun parseServers(profileId: String, toolId: String, array: JSONArray?): List<NonRootBackendServer> {
    if (toolId == NonRootCascadeProfile.TOOL_OPERA_PROXY) return emptyList()
    val source = array ?: JSONArray()
    return buildList {
      for (index in 0 until source.length()) {
        val item = source.optJSONObject(index) ?: continue
        val id = item.optString("id", "").trim().ifBlank { UUID.randomUUID().toString() }
        val port = portRegistry.getOrAllocate(serverPortKey(profileId, id))
        val aux = if (toolId == NonRootCascadeProfile.TOOL_MIERU) portRegistry.getOrAllocate(serverAuxPortKey(profileId, id)) else 0
        add(NonRootBackendServer(
          id = id,
          name = item.optString("name", "Server ${index + 1}").trim().ifBlank { "Server ${index + 1}" },
          enabled = item.optBoolean("enabled", true),
          port = port,
          auxPort = aux,
          configText = item.optString("config", defaultServerConfig(toolId, port, aux)),
          logLevel = item.optString("log_level", "info").trim().ifBlank { "info" },
        ))
      }
    }
  }

  private fun persist(state: NonRootCascadeState): NonRootCascadeState {
    val routeForMode = if (state.backendMode == NonRootCascadeBackendMode.BALANCE) {
      state.route.filterNot { it.type == NonRootCascadeRouteItemType.DIRECT_START || it.type == NonRootCascadeRouteItemType.DIRECT_BLOCK }
    } else state.route
    val direct = state.directSelectedProfileId.takeIf { selected ->
      state.profiles.firstOrNull { it.id == selected }?.directEligible == true
    }.orEmpty()
    val normalized = state.copy(directSelectedProfileId = direct, route = normalizeRoute(routeForMode, state.profiles))
    prefs.edit().putString(KEY_STATE, toJson(normalized).toString()).apply()
    return normalized
  }

  private fun toJson(state: NonRootCascadeState): JSONObject = JSONObject().apply {
    put("direct_selected_profile_id", state.directSelectedProfileId)
    put("backend_mode", state.backendMode.name.lowercase())
    put("t2s", t2sToJson(state.t2s))
    put("profiles", JSONArray().apply {
      state.profiles.forEach { profile ->
        put(JSONObject().apply {
          put("id", profile.id)
          put("name", profile.name)
          put("enabled", profile.enabled)
          put("tool", profile.toolId)
          if (profile.toolId == NonRootCascadeProfile.TOOL_OPERA_PROXY) put("opera", nonRootOperaConfigToJson(profile.operaConfig))
          if (profile.servers.isNotEmpty()) put("servers", JSONArray().apply {
            profile.servers.forEach { server ->
              put(JSONObject().apply {
                put("id", server.id)
                put("name", server.name)
                put("enabled", server.enabled)
                put("config", server.configText)
                put("log_level", server.logLevel)
              })
            }
          })
        })
      }
    })
    put("route", JSONArray().apply {
      state.route.forEach { routeItem ->
        put(JSONObject().apply {
          put("type", routeItem.type.name.lowercase())
          if (routeItem.type == NonRootCascadeRouteItemType.PROFILE) put("profile_id", routeItem.profileId)
          else {
            if (routeItem.markerId.isNotBlank()) put("marker_id", routeItem.markerId)
            if (routeItem.type == NonRootCascadeRouteItemType.GROUP && routeItem.name.isNotBlank()) put("name", routeItem.name)
          }
        })
      }
    })
  }

  private fun createServerModel(profileId: String, toolId: String, name: String, configText: String? = null): NonRootBackendServer {
    val id = UUID.randomUUID().toString()
    val port = portRegistry.getOrAllocate(serverPortKey(profileId, id))
    val aux = if (toolId == NonRootCascadeProfile.TOOL_MIERU) portRegistry.getOrAllocate(serverAuxPortKey(profileId, id)) else 0
    return NonRootBackendServer(
      id = id,
      name = name,
      port = port,
      auxPort = aux,
      configText = configText ?: defaultServerConfig(toolId, port, aux),
    )
  }

  private fun defaultServerConfig(toolId: String, port: Int, auxPort: Int): String = when (toolId) {
    NonRootCascadeProfile.TOOL_HYSTERIA2 -> JSONObject()
      .put("server", "")
      .put("auth", "")
      .put("tls", JSONObject().put("sni", "").put("insecure", false))
      .put("socks5", JSONObject().put("listen", "127.0.0.1:$port").put("disableUDP", false))
      .toString(2)
    NonRootCascadeProfile.TOOL_SING_BOX -> JSONObject()
      .put("log", JSONObject().put("level", "info"))
      .put("inbounds", JSONArray().put(JSONObject().put("type", "mixed").put("tag", "mixed-in").put("listen", "127.0.0.1").put("listen_port", port)))
      .put("outbounds", JSONArray())
      .toString(2)
    NonRootCascadeProfile.TOOL_MIERU -> JSONObject()
      .put("profiles", JSONArray().put(JSONObject()
        .put("profileName", "default")
        .put("user", JSONObject().put("name", "").put("password", ""))
        .put("servers", JSONArray().put(JSONObject()
          .put("domainName", "")
          .put("portBindings", JSONArray().put(JSONObject().put("port", 443).put("protocol", "TCP")))))
        .put("multiplexing", JSONObject().put("level", "MULTIPLEXING_HIGH"))
        .put("handshakeMode", "HANDSHAKE_STANDARD")))
      .put("activeProfile", "default")
      .put("rpcPort", auxPort)
      .put("socks5Port", port)
      .put("loggingLevel", "INFO")
      .put("socks5ListenLAN", false)
      .toString(2)
    NonRootCascadeProfile.TOOL_WIREPROXY -> """
      [Interface]
      PrivateKey =
      Address = 172.16.0.2/32

      [Peer]
      PublicKey =
      Endpoint =
      AllowedIPs = 0.0.0.0/0, ::/0

      [Socks5]
      BindAddress = 127.0.0.1:$port
    """.trimIndent()
    else -> ""
  }

  private fun t2sFromJson(obj: JSONObject): NonRootT2sConfig = normalizeT2sConfig(NonRootT2sConfig(
    prioritySpeedAware = obj.optBoolean("priority_speed_aware", false),
    maxConnections = obj.optInt("max_connections", 100),
    idleTimeoutSeconds = obj.optInt("idle_timeout_seconds", 600),
    connectTimeoutSeconds = obj.optInt("connect_timeout_seconds", 8),
    bufferSize = obj.optInt("buffer_size", 65536),
    downloadLimitMbit = obj.optString("download_limit_mbit", "0"),
    peerCoordination = obj.optBoolean("peer_coordination", true),
    serializeBackendConnects = obj.optBoolean("serialize_backend_connects", true),
    connectStaggerMs = obj.optInt("connect_stagger_ms", 100),
  ))

  private fun t2sToJson(config: NonRootT2sConfig): JSONObject = JSONObject().apply {
    put("priority_speed_aware", config.prioritySpeedAware)
    put("max_connections", config.maxConnections)
    put("idle_timeout_seconds", config.idleTimeoutSeconds)
    put("connect_timeout_seconds", config.connectTimeoutSeconds)
    put("buffer_size", config.bufferSize)
    put("download_limit_mbit", config.downloadLimitMbit)
    put("peer_coordination", config.peerCoordination)
    put("serialize_backend_connects", config.serializeBackendConnects)
    put("connect_stagger_ms", config.connectStaggerMs)
  }

  private fun normalizeT2sConfig(config: NonRootT2sConfig): NonRootT2sConfig = config.copy(
    maxConnections = config.maxConnections.coerceIn(1, 100_000),
    idleTimeoutSeconds = config.idleTimeoutSeconds.coerceIn(0, 86_400),
    connectTimeoutSeconds = config.connectTimeoutSeconds.coerceIn(1, 600),
    bufferSize = config.bufferSize.coerceIn(4_096, 16 * 1024 * 1024),
    downloadLimitMbit = config.downloadLimitMbit.trim().toDoubleOrNull()?.coerceAtLeast(0.0)?.toString() ?: "0",
    connectStaggerMs = config.connectStaggerMs.coerceIn(0, 60_000),
  )

  private fun normalizeRoute(route: List<NonRootCascadeRouteItem>, profiles: List<NonRootCascadeProfile>): List<NonRootCascadeRouteItem> {
    val validProfileIds = profiles.mapTo(linkedSetOf()) { it.id }
    val seenProfiles = hashSetOf<String>()
    val result = mutableListOf<NonRootCascadeRouteItem>()
    route.forEach { rawItem ->
      val item = if (rawItem.type == NonRootCascadeRouteItemType.PROFILE) rawItem
      else rawItem.copy(markerId = rawItem.markerId.ifBlank { UUID.randomUUID().toString() })
      when (item.type) {
        NonRootCascadeRouteItemType.PROFILE -> if (item.profileId in validProfileIds && seenProfiles.add(item.profileId)) result += item
        NonRootCascadeRouteItemType.DIRECT_START,
        NonRootCascadeRouteItemType.DIRECT_BLOCK -> if (result.none { it.type == NonRootCascadeRouteItemType.DIRECT_START || it.type == NonRootCascadeRouteItemType.DIRECT_BLOCK }) result += item
        NonRootCascadeRouteItemType.GROUP -> result += item
      }
    }
    profiles.forEach { profile ->
      if (seenProfiles.add(profile.id)) {
        val blockIndex = result.indexOfFirst { it.type == NonRootCascadeRouteItemType.DIRECT_BLOCK }
        if (blockIndex >= 0) result.add(blockIndex, NonRootCascadeRouteItem(NonRootCascadeRouteItemType.PROFILE, profile.id))
        else result += NonRootCascadeRouteItem(NonRootCascadeRouteItemType.PROFILE, profile.id)
      }
    }
    val direct = result.firstOrNull { it.type == NonRootCascadeRouteItemType.DIRECT_START }
    val block = result.firstOrNull { it.type == NonRootCascadeRouteItemType.DIRECT_BLOCK }
    val rawMiddle = result.filter { it.type != NonRootCascadeRouteItemType.DIRECT_START && it.type != NonRootCascadeRouteItemType.DIRECT_BLOCK }
    val middle = mutableListOf<NonRootCascadeRouteItem>()
    var pendingGroup: NonRootCascadeRouteItem? = null
    rawMiddle.forEach { item ->
      when (item.type) {
        NonRootCascadeRouteItemType.GROUP -> if (middle.lastOrNull()?.type == NonRootCascadeRouteItemType.PROFILE && pendingGroup == null) pendingGroup = item
        NonRootCascadeRouteItemType.PROFILE -> {
          if (pendingGroup != null && middle.lastOrNull()?.type == NonRootCascadeRouteItemType.PROFILE) middle += pendingGroup!!
          pendingGroup = null
          middle += item
        }
        else -> Unit
      }
    }
    return buildList { direct?.let(::add); addAll(middle); block?.let(::add) }
  }

  private fun normalizedName(raw: String, toolId: String): String = raw.trim().ifEmpty {
    when (toolId) {
      NonRootCascadeProfile.TOOL_HYSTERIA2 -> "Hysteria2"
      NonRootCascadeProfile.TOOL_SING_BOX -> "sing-box"
      NonRootCascadeProfile.TOOL_MIERU -> "Mieru"
      NonRootCascadeProfile.TOOL_WIREPROXY -> "WireProxy"
      else -> DEFAULT_PROFILE_NAME
    }
  }

  private fun normalizedServerName(raw: String, index: Int): String = raw.trim().ifEmpty { "Server $index" }

  companion object {
    private const val PREFS_NAME = "non_root_cascade"
    private const val KEY_STATE = "cascade_state"
    private const val KEY_LEGACY_DIRECT_IMPORTED = "legacy_direct_imported"
    private const val DEFAULT_PROFILE_NAME = "Opera"
    private const val DEFAULT_DIRECT_PROFILE_NAME = "Direct"
    private const val DEFAULT_SERVER_NAME = "Server 1"

    fun portKey(profileId: String): String = "cascade.$profileId.operaproxy"
    fun byedpiPortKey(profileId: String): String = "cascade.$profileId.byedpi"
    fun serverPortKey(profileId: String, serverId: String): String = "cascade.$profileId.server.$serverId.socks"
    fun serverAuxPortKey(profileId: String, serverId: String): String = "cascade.$profileId.server.$serverId.aux"
  }
}
