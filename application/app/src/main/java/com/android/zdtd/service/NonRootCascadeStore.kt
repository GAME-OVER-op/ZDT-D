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
)

data class NonRootCascadeProfile(
  val id: String,
  val name: String,
  val enabled: Boolean = true,
  val toolId: String = TOOL_OPERA_PROXY,
  val port: Int,
  val byedpiPort: Int,
  val operaConfig: NonRootDirectOperaConfig = NonRootDirectOperaConfig(),
) {
  companion object {
    const val TOOL_OPERA_PROXY = "operaproxy"
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
  val backendMode: NonRootCascadeBackendMode = NonRootCascadeBackendMode.BALANCE,
  val route: List<NonRootCascadeRouteItem> = emptyList(),
  val t2s: NonRootT2sConfig = NonRootT2sConfig(),
)

/**
 * Persistent non-root Cascade configuration.
 *
 * Each profile owns one Opera Proxy configuration and one loopback port. The
 * T2S route is stored separately so disabling a profile never loses its place.
 */
class NonRootCascadeStore(context: Context) {
  private val appContext = context.applicationContext
  private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val portRegistry = NonRootPortRegistry(appContext)

  @Synchronized
  fun load(): NonRootCascadeState {
    val raw = prefs.getString(KEY_STATE, null)?.takeIf { it.isNotBlank() }
      ?: return NonRootCascadeState()
    return runCatching { fromJson(JSONObject(raw)) }
      .getOrElse { NonRootCascadeState() }
  }

  @Synchronized
  fun createProfile(name: String): NonRootCascadeState {
    val current = load()
    val id = UUID.randomUUID().toString()
    val profile = NonRootCascadeProfile(
      id = id,
      name = normalizedName(name),
      port = portRegistry.getOrAllocate(portKey(id)),
      byedpiPort = portRegistry.getOrAllocate(byedpiPortKey(id)),
    )
    val insertAt = current.route.indexOfLast { it.type == NonRootCascadeRouteItemType.DIRECT_BLOCK }
      .takeIf { it >= 0 } ?: current.route.size
    val route = current.route.toMutableList().apply {
      add(insertAt, NonRootCascadeRouteItem(NonRootCascadeRouteItemType.PROFILE, id))
    }
    return persist(current.copy(profiles = current.profiles + profile, route = normalizeRoute(route, current.profiles + profile)))
  }

  @Synchronized
  fun updateProfile(profile: NonRootCascadeProfile): NonRootCascadeState {
    val current = load()
    if (current.profiles.none { it.id == profile.id }) return current
    val normalized = profile.copy(name = normalizedName(profile.name), toolId = NonRootCascadeProfile.TOOL_OPERA_PROXY)
    val profiles = current.profiles.map { if (it.id == profile.id) normalized else it }
    return persist(current.copy(profiles = profiles, route = normalizeRoute(current.route, profiles)))
  }

  @Synchronized
  fun setProfilePort(profileId: String, port: Int): NonRootCascadeState? {
    val current = load()
    val profile = current.profiles.firstOrNull { it.id == profileId } ?: return null
    if (!portRegistry.set(portKey(profileId), port)) return null
    val profiles = current.profiles.map { if (it.id == profileId) profile.copy(port = port) else it }
    return persist(current.copy(profiles = profiles))
  }

  @Synchronized
  fun setProfileByeDpiPort(profileId: String, port: Int): NonRootCascadeState? {
    val current = load()
    val profile = current.profiles.firstOrNull { it.id == profileId } ?: return null
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
    portRegistry.clear(portKey(profileId))
    portRegistry.clear(byedpiPortKey(profileId))
    val profiles = current.profiles.filterNot { it.id == profileId }
    val route = current.route.filterNot { it.type == NonRootCascadeRouteItemType.PROFILE && it.profileId == profileId }
    return persist(current.copy(profiles = profiles, route = normalizeRoute(route, profiles)))
  }

  @Synchronized
  fun setBackendMode(mode: NonRootCascadeBackendMode): NonRootCascadeState {
    val current = load()
    val route = if (mode == NonRootCascadeBackendMode.BALANCE) {
      current.route.filterNot { it.type == NonRootCascadeRouteItemType.DIRECT_START || it.type == NonRootCascadeRouteItemType.DIRECT_BLOCK }
    } else {
      current.route
    }
    return persist(current.copy(backendMode = mode, route = route))
  }

  @Synchronized
  fun setRoute(route: List<NonRootCascadeRouteItem>): NonRootCascadeState {
    val current = load()
    val allowedRoute = if (current.backendMode == NonRootCascadeBackendMode.BALANCE) {
      route.filterNot { it.type == NonRootCascadeRouteItemType.DIRECT_START || it.type == NonRootCascadeRouteItemType.DIRECT_BLOCK }
    } else {
      route
    }
    return persist(current.copy(route = normalizeRoute(allowedRoute, current.profiles)))
  }

  private fun fromJson(obj: JSONObject): NonRootCascadeState {
    val profilesArray = obj.optJSONArray("profiles") ?: JSONArray()
    val profiles = buildList {
      for (index in 0 until profilesArray.length()) {
        val item = profilesArray.optJSONObject(index) ?: continue
        val id = item.optString("id", "").trim()
        if (id.isEmpty()) continue
        add(
          NonRootCascadeProfile(
            id = id,
            name = normalizedName(item.optString("name", "")),
            enabled = item.optBoolean("enabled", true),
            toolId = NonRootCascadeProfile.TOOL_OPERA_PROXY,
            port = portRegistry.getOrAllocate(portKey(id)),
            byedpiPort = portRegistry.getOrAllocate(byedpiPortKey(id)),
            operaConfig = nonRootOperaConfigFromJson(item.optJSONObject("opera") ?: JSONObject()),
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
        val type = runCatching {
          NonRootCascadeRouteItemType.valueOf(item.optString("type", "").uppercase())
        }.getOrNull() ?: continue
        add(
          NonRootCascadeRouteItem(
            type = type,
            profileId = item.optString("profile_id", ""),
            markerId = item.optString("marker_id", ""),
          )
        )
      }
    }

    val routeForMode = if (mode == NonRootCascadeBackendMode.BALANCE) {
      route.filterNot { it.type == NonRootCascadeRouteItemType.DIRECT_START || it.type == NonRootCascadeRouteItemType.DIRECT_BLOCK }
    } else {
      route
    }
    return NonRootCascadeState(
      profiles = profiles,
      backendMode = mode,
      route = normalizeRoute(routeForMode, profiles),
      t2s = t2sFromJson(obj.optJSONObject("t2s") ?: JSONObject()),
    )
  }

  private fun persist(state: NonRootCascadeState): NonRootCascadeState {
    val routeForMode = if (state.backendMode == NonRootCascadeBackendMode.BALANCE) {
      state.route.filterNot { it.type == NonRootCascadeRouteItemType.DIRECT_START || it.type == NonRootCascadeRouteItemType.DIRECT_BLOCK }
    } else {
      state.route
    }
    val normalized = state.copy(route = normalizeRoute(routeForMode, state.profiles))
    prefs.edit().putString(KEY_STATE, toJson(normalized).toString()).apply()
    return normalized
  }

  private fun toJson(state: NonRootCascadeState): JSONObject = JSONObject().apply {
    put("backend_mode", state.backendMode.name.lowercase())
    put("t2s", t2sToJson(state.t2s))
    put("profiles", JSONArray().apply {
      state.profiles.forEach { profile ->
        put(JSONObject().apply {
          put("id", profile.id)
          put("name", profile.name)
          put("enabled", profile.enabled)
          put("tool", NonRootCascadeProfile.TOOL_OPERA_PROXY)
          put("opera", nonRootOperaConfigToJson(profile.operaConfig))
        })
      }
    })
    put("route", JSONArray().apply {
      state.route.forEach { routeItem ->
        put(JSONObject().apply {
          put("type", routeItem.type.name.lowercase())
          if (routeItem.type == NonRootCascadeRouteItemType.PROFILE) {
            put("profile_id", routeItem.profileId)
          } else if (routeItem.markerId.isNotBlank()) {
            put("marker_id", routeItem.markerId)
          }
        })
      }
    })
  }

  private fun t2sFromJson(obj: JSONObject): NonRootT2sConfig = normalizeT2sConfig(
    NonRootT2sConfig(
      prioritySpeedAware = obj.optBoolean("priority_speed_aware", false),
      maxConnections = obj.optInt("max_connections", 100),
      idleTimeoutSeconds = obj.optInt("idle_timeout_seconds", 600),
      connectTimeoutSeconds = obj.optInt("connect_timeout_seconds", 8),
      bufferSize = obj.optInt("buffer_size", 65536),
      downloadLimitMbit = obj.optString("download_limit_mbit", "0"),
      peerCoordination = obj.optBoolean("peer_coordination", true),
      serializeBackendConnects = obj.optBoolean("serialize_backend_connects", true),
      connectStaggerMs = obj.optInt("connect_stagger_ms", 100),
    )
  )

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

  private fun normalizeRoute(
    route: List<NonRootCascadeRouteItem>,
    profiles: List<NonRootCascadeProfile>,
  ): List<NonRootCascadeRouteItem> {
    val validProfileIds = profiles.mapTo(linkedSetOf()) { it.id }
    val seenProfiles = hashSetOf<String>()
    val result = mutableListOf<NonRootCascadeRouteItem>()

    route.forEach { rawItem ->
      val item = when (rawItem.type) {
        NonRootCascadeRouteItemType.PROFILE -> rawItem
        else -> rawItem.copy(markerId = rawItem.markerId.ifBlank { UUID.randomUUID().toString() })
      }
      when (item.type) {
        NonRootCascadeRouteItemType.PROFILE -> {
          if (item.profileId in validProfileIds && seenProfiles.add(item.profileId)) result += item
        }
        NonRootCascadeRouteItemType.DIRECT_START,
        NonRootCascadeRouteItemType.DIRECT_BLOCK -> {
          if (result.none { it.type == NonRootCascadeRouteItemType.DIRECT_START || it.type == NonRootCascadeRouteItemType.DIRECT_BLOCK }) {
            result += item
          }
        }
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
    val rawMiddle = result.filter {
      it.type != NonRootCascadeRouteItemType.DIRECT_START && it.type != NonRootCascadeRouteItemType.DIRECT_BLOCK
    }

    // A group marker is meaningful only between two profile entries. Collapse
    // duplicate/edge markers defensively so the persisted route and the CLI
    // representation can never disagree about empty priority groups.
    val middle = mutableListOf<NonRootCascadeRouteItem>()
    var pendingGroup: NonRootCascadeRouteItem? = null
    rawMiddle.forEach { item ->
      when (item.type) {
        NonRootCascadeRouteItemType.GROUP -> {
          if (middle.lastOrNull()?.type == NonRootCascadeRouteItemType.PROFILE && pendingGroup == null) {
            pendingGroup = item
          }
        }
        NonRootCascadeRouteItemType.PROFILE -> {
          if (pendingGroup != null && middle.lastOrNull()?.type == NonRootCascadeRouteItemType.PROFILE) {
            middle += pendingGroup!!
          }
          pendingGroup = null
          middle += item
        }
        else -> Unit
      }
    }

    return buildList {
      direct?.let(::add)
      addAll(middle)
      block?.let(::add)
    }
  }

  private fun normalizedName(raw: String): String = raw.trim().ifEmpty { DEFAULT_PROFILE_NAME }

  companion object {
    private const val PREFS_NAME = "non_root_cascade"
    private const val KEY_STATE = "cascade_state"
    private const val DEFAULT_PROFILE_NAME = "Opera"

    fun portKey(profileId: String): String = "cascade.$profileId.operaproxy"
    fun byedpiPortKey(profileId: String): String = "cascade.$profileId.byedpi"
  }
}
