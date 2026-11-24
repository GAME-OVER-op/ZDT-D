package com.android.zdtd.service.vps

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class VpsRemoteController(
  context: Context,
  private val ssh: VpsSshClient = VpsSshClient(),
) {
  private val script: String = context.assets.open("vps/zdt-vpsctl.sh").bufferedReader().use { it.readText() }
  private val remotePath = "/tmp/zdt-vpsctl.sh"

  suspend fun inventory(server: VpsServer): List<VpsServiceState> {
    val result = run(server, listOf("inventory"))
    check(result.successful) { result.output.ifBlank { "Unable to read installed services" } }
    return result.output.lineSequence().mapNotNull { line ->
      val parts = line.split('|')
      if (parts.size < 5 || parts[0] != "ZDT_SERVICE") return@mapNotNull null
      val kind = VpsServiceKind.fromWireId(parts[1]) ?: return@mapNotNull null
      VpsServiceState(
        kind = kind,
        installed = parts[2] == "1",
        active = parts[3] == "1",
        version = decode(parts[4]),
      )
    }.toList()
  }

  suspend fun install(
    server: VpsServer,
    kind: VpsServiceKind,
    variant: String = "normal",
    onLine: (String) -> Unit,
    jwt: String? = null,
  ): VpsCommandResult {
    val timeout = when {
      kind == VpsServiceKind.URNETWORK -> 15 * 60_000L
      kind == VpsServiceKind.OPENVPN && variant == "strong" -> 30 * 60_000L
      else -> 12 * 60_000L
    }
    val env = jwt?.takeIf { it.isNotBlank() }
      ?.let { mapOf(URNETWORK_JWT_ENV to it) }
      .orEmpty()
    return run(server, listOf("install", kind.wireId, variant), onLine = onLine, timeoutMs = timeout, env = env)
  }

  /** Enable or disable routing traffic through the URnetwork exit node. */
  suspend fun configureUrnetworkExit(
    server: VpsServer,
    enable: Boolean,
    onLine: (String) -> Unit,
  ): VpsCommandResult = run(
    server,
    listOf(if (enable) "enable-exit" else "disable-exit"),
    onLine = onLine,
    timeoutMs = 5 * 60_000L,
  )

  /** Read the current URnetwork exit state from the remote host. */
  suspend fun checkUrnetworkExit(
    server: VpsServer,
    onLine: ((String) -> Unit)? = null,
  ): VpsUrnetworkExitInfo = withContext(Dispatchers.IO) {
    val result = run(server, listOf("check-exit"), onLine = onLine, timeoutMs = 2 * 60_000L)
    check(result.successful) { result.output.ifBlank { "Unable to read URnetwork exit state" } }
    parseUrnetworkExitInfo(result.output)
  }

  /** Read the full URnetwork configuration from the remote host. */
  suspend fun getUrnetworkSettings(server: VpsServer): VpsUrnetworkSettings {
    val result = run(server, listOf("get-settings"), timeoutMs = 30_000L)
    check(result.successful) { result.output.ifBlank { "Unable to read URnetwork settings" } }
    return parseUrnetworkSettings(result.output)
  }

  /** Write the full URnetwork configuration; the caller is responsible for restarting the service. */
  suspend fun setUrnetworkSettings(server: VpsServer, settings: VpsUrnetworkSettings): VpsCommandResult =
    run(
      server,
      listOf(
        "set-settings",
        "${KEY_SHARE}=${settingBool(settings.share)}",
        "${KEY_ROUTING}=${settingBool(settings.routing)}",
        "${KEY_COUNTRY}=${settings.country}",
        "${KEY_REGION}=${settings.region}",
        "${KEY_CITY}=${settings.city}",
        "${KEY_PROVIDER_ID}=${settings.providerId}",
        "${KEY_SOCKS_ADDR}=${settings.socksAddr}",
        "${KEY_MAX_MEMORY}=${settings.maxMemory}",
        "${KEY_NO_PORT_MAPPING}=${settingBool(settings.noPortMapping)}",
        "${KEY_API_URL}=${settings.apiUrl}",
        "${KEY_PLATFORM_URL}=${settings.platformUrl}",
        "${KEY_API_HOSTS}=${settings.apiHosts}",
      ),
      timeoutMs = 30_000L,
    )

  /** List exit locations offered by the URnetwork backend. */
  suspend fun listUrnetworkCountries(server: VpsServer): List<VpsUrnetworkLocationOption> {
    val result = run(server, listOf("list-countries"), timeoutMs = 30_000L)
    checkPickerResult(result, "Unable to list URnetwork countries")
    return result.output.lineSequence().mapNotNull { line ->
      val body = line.removePrefix("ZDT_LOCATION=")
      if (body == line) return@mapNotNull null
      val p = body.split('|', limit = 4)
      if (p.size < 4) return@mapNotNull null
      val id = p[0].trim()
      if (id.isBlank()) return@mapNotNull null
      VpsUrnetworkLocationOption(
        id = id,
        name = p[1].trim(),
        countryCode = p[2].trim(),
        providerCount = p[3].trim().toIntOrNull() ?: 0,
      )
    }.toList()
  }

  /** List providers available within a URnetwork exit location. */
  suspend fun listUrnetworkProviders(server: VpsServer, locationId: String): List<VpsUrnetworkProviderOption> {
    val result = run(server, listOf("list-providers", locationId), timeoutMs = 30_000L)
    checkPickerResult(result, "Unable to list URnetwork providers")
    return result.output.lineSequence().mapNotNull { line ->
      val body = line.removePrefix("ZDT_PROVIDER=")
      if (body == line) return@mapNotNull null
      val p = body.split('|', limit = 3)
      if (p.size < 3) return@mapNotNull null
      val id = p[0].trim()
      if (id.isBlank()) return@mapNotNull null
      VpsUrnetworkProviderOption(
        id = id,
        name = p[1].trim(),
        country = p[2].trim(),
      )
    }.toList()
  }

  suspend fun remove(server: VpsServer, kind: VpsServiceKind, onLine: (String) -> Unit): VpsCommandResult =
    run(server, listOf("remove", kind.wireId), onLine = onLine, timeoutMs = 5 * 60_000L)

  suspend fun listProfiles(server: VpsServer, kind: VpsServiceKind): List<VpsServiceProfile> {
    if (kind == VpsServiceKind.DNSCRYPT) return emptyList()
    val result = run(server, listOf("list-profiles", kind.wireId))
    check(result.successful) { result.output.ifBlank { "Unable to read profiles" } }
    return result.output.lineSequence().mapNotNull { line ->
      val p = line.split('|')
      if (p.size < 11 || p[0] != "ZDT_PROFILE") return@mapNotNull null
      val parsedKind = VpsServiceKind.fromWireId(p[1]) ?: return@mapNotNull null
      VpsServiceProfile(
        id = p[2],
        name = decode(p[3]),
        kind = parsedKind,
        mode = p[4],
        protocol = p[5],
        port = p[6].toIntOrNull() ?: 0,
        host = decode(p[7]),
        domain = decode(p[8]),
        clientCount = p[9].toIntOrNull() ?: 0,
        active = p[10] == "1",
        uploadBytes = p.getOrNull(11)?.toLongOrNull() ?: 0L,
        downloadBytes = p.getOrNull(12)?.toLongOrNull() ?: 0L,
        sniOptions = p.getOrNull(13)?.let(::decode)?.split(',')?.map(String::trim)?.filter(String::isNotBlank)?.distinct().orEmpty(),
      )
    }.toList()
  }

  suspend fun createProfile(
    server: VpsServer,
    kind: VpsServiceKind,
    id: String,
    name: String,
    port: Int,
    mode: String,
    domain: String,
    email: String,
    snis: List<String>,
    onLine: (String) -> Unit,
  ): VpsCommandResult = run(
    server,
    listOf("create-profile", kind.wireId, id, name, port.toString(), mode, server.host, domain, email, snis.joinToString(",")),
    onLine = onLine,
    timeoutMs = 10 * 60_000L,
  )

  suspend fun deleteProfile(
    server: VpsServer,
    kind: VpsServiceKind,
    profileId: String,
    onLine: ((String) -> Unit)? = null,
  ): VpsCommandResult =
    run(server, listOf("delete-profile", kind.wireId, profileId), timeoutMs = 120_000L, onLine = onLine)

  suspend fun listClients(server: VpsServer, kind: VpsServiceKind, profileId: String): List<VpsClientConfig> {
    val result = run(server, listOf("list-clients", kind.wireId, profileId))
    check(result.successful) { result.output.ifBlank { "Unable to read clients" } }
    return result.output.lineSequence().mapNotNull { line ->
      val p = line.split('|')
      if (p.size < 6 || p[0] != "ZDT_CLIENT") return@mapNotNull null
      val parsedKind = VpsServiceKind.fromWireId(p[1]) ?: return@mapNotNull null
      VpsClientConfig(
        id = p[3],
        name = decode(p[4]),
        profileId = p[2],
        kind = parsedKind,
        createdAt = p[5].toLongOrNull() ?: 0L,
        uploadBytes = p.getOrNull(6)?.toLongOrNull() ?: 0L,
        downloadBytes = p.getOrNull(7)?.toLongOrNull() ?: 0L,
      )
    }.toList()
  }

  suspend fun createClient(
    server: VpsServer,
    kind: VpsServiceKind,
    profileId: String,
    clientId: String,
    displayName: String,
    onLine: (String) -> Unit,
  ): VpsCommandResult = run(
    server,
    listOf("create-client", kind.wireId, profileId, clientId, displayName),
    onLine = onLine,
    timeoutMs = 5 * 60_000L,
  )

  suspend fun deleteClient(
    server: VpsServer,
    kind: VpsServiceKind,
    profileId: String,
    clientId: String,
    onLine: ((String) -> Unit)? = null,
  ): VpsCommandResult =
    run(server, listOf("delete-client", kind.wireId, profileId, clientId), timeoutMs = 3 * 60_000L, onLine = onLine)

  suspend fun getConfig(server: VpsServer, kind: VpsServiceKind, profileId: String, clientId: String): VpsConfigResult {
    val result = run(server, listOf("get-config", kind.wireId, profileId, clientId), timeoutMs = 60_000L)
    check(result.successful) { result.output.ifBlank { "Unable to generate configuration" } }
    val lines = result.output.lines()
    val begin = lines.indexOf("ZDT_CONFIG_BEGIN")
    val end = lines.indexOfLast { it == "ZDT_CONFIG_END" }
    check(begin >= 0 && end > begin) { "Server returned an invalid configuration" }
    val fileName = lines.firstOrNull { it.startsWith("ZDT_FILENAME=") }?.substringAfter('=')?.let(::decode).orEmpty().ifBlank { "$clientId.conf" }
    val mime = lines.firstOrNull { it.startsWith("ZDT_MIME=") }?.substringAfter('=')?.let(::decode).orEmpty().ifBlank { "text/plain" }
    val link = lines.firstOrNull { it.startsWith("ZDT_LINK=") }?.substringAfter('=')?.let(::decode)?.takeIf { it.isNotBlank() }
    val clientName = lines.firstOrNull { it.startsWith("ZDT_CLIENT_NAME=") }?.substringAfter('=')?.let(::decode).orEmpty().ifBlank { clientId }
    val sniOptions = lines.firstOrNull { it.startsWith("ZDT_SNI_OPTIONS=") }
      ?.substringAfter('=')
      ?.let(::decode)
      ?.split(',')
      ?.map(String::trim)
      ?.filter(String::isNotBlank)
      ?.distinct()
      .orEmpty()
    return VpsConfigResult(
      kind = kind,
      profileId = profileId,
      clientId = clientId,
      clientName = clientName,
      fileName = fileName,
      mimeType = mime,
      content = lines.subList(begin + 1, end).joinToString("\n").trimEnd(),
      shareLink = link,
      sniOptions = sniOptions,
    )
  }


  suspend fun reboot(server: VpsServer): VpsCommandResult =
    run(server, listOf("reboot"), timeoutMs = 30_000L)

  suspend fun restart(
    server: VpsServer,
    kind: VpsServiceKind,
    profileId: String?,
    onLine: ((String) -> Unit)? = null,
  ): VpsCommandResult {
    val args = mutableListOf("restart", kind.wireId)
    if (!profileId.isNullOrBlank()) args += profileId
    return run(server, args, timeoutMs = 90_000L, onLine = onLine)
  }

  suspend fun logs(server: VpsServer, kind: VpsServiceKind, profileId: String?): String {
    val args = mutableListOf("logs", kind.wireId)
    if (!profileId.isNullOrBlank()) args += profileId
    return run(server, args, timeoutMs = 60_000L).output
  }

  private suspend fun run(
    server: VpsServer,
    args: List<String>,
    timeoutMs: Long = 120_000L,
    onLine: ((String) -> Unit)? = null,
    env: Map<String, String> = emptyMap(),
  ): VpsCommandResult = withContext(Dispatchers.IO) {
    val upload = ssh.uploadText(server, remotePath, script)
    if (!upload.successful) return@withContext upload
    val command = buildString {
      append("bash ").append(VpsSshClient.shellQuote(remotePath))
      args.forEach { append(' ').append(VpsSshClient.shellQuote(it)) }
    }
    // The console/com log shows only the sanitized command: secrets are never
    // added to the arguments or written to the ZDT_CMD line.
    onLine?.invoke("ZDT_CMD=$command")
    val actualCommand = if (env.isEmpty()) command else buildString {
      env.forEach { (name, value) ->
        require(ENV_NAME.matches(name)) { "Invalid environment variable name" }
        append(name).append('=').append(VpsSshClient.shellQuote(value)).append(' ')
      }
      append(command)
    }
    ssh.execute(server, actualCommand, timeoutMs, onLine)
  }

  private fun checkPickerResult(result: VpsCommandResult, fallback: String) {
    if (result.successful) return
    val message = result.output.lineSequence()
      .firstOrNull { it.startsWith("ZDT_ERROR=") }
      ?.substringAfter('=')
      ?.trim()
      ?.takeIf { it.isNotBlank() }
      ?: result.output.lineSequence().firstOrNull { it.isNotBlank() }
      ?: fallback
    error(message)
  }

  private fun parseUrnetworkSettings(output: String): VpsUrnetworkSettings {
    val values = output.lineSequence().mapNotNull { line ->
      val body = line.removePrefix("ZDT_SETTING_")
      if (body == line) return@mapNotNull null
      val index = body.indexOf('=')
      if (index <= 0) null else body.substring(0, index).trim() to body.substring(index + 1).trim()
    }.toMap()
    fun text(key: String, default: String) = values[key]?.trim()?.takeIf { it.isNotEmpty() } ?: default
    return VpsUrnetworkSettings(
      share = boolSetting(values, KEY_SHARE, false),
      routing = boolSetting(values, KEY_ROUTING, false),
      country = text(KEY_COUNTRY, "Germany"),
      region = text(KEY_REGION, ""),
      city = text(KEY_CITY, ""),
      providerId = text(KEY_PROVIDER_ID, ""),
      socksAddr = text(KEY_SOCKS_ADDR, "127.0.0.1:9999"),
      maxMemory = text(KEY_MAX_MEMORY, "512mib"),
      noPortMapping = boolSetting(values, KEY_NO_PORT_MAPPING, true),
      apiUrl = text(KEY_API_URL, "https://api.bringyour.com"),
      platformUrl = text(KEY_PLATFORM_URL, "wss://connect.bringyour.com"),
      apiHosts = text(KEY_API_HOSTS, VpsUrnetworkSettings().apiHosts),
    )
  }

  private fun parseUrnetworkExitInfo(output: String): VpsUrnetworkExitInfo {
    val values = output.lineSequence().mapNotNull { line ->
      val index = line.indexOf('=')
      if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
    }.toMap()
    return VpsUrnetworkExitInfo(
      connected = values["ZDT_EXIT"] == "1" || values["ZDT_EXIT"]?.equals("true", ignoreCase = true) == true,
      ip = values["ZDT_EXIT_IP"].orEmpty(),
      country = values["ZDT_EXIT_COUNTRY"].orEmpty(),
      countryCode = values["ZDT_EXIT_COUNTRY_CODE"].orEmpty(),
    )
  }

  companion object {
    const val URNETWORK_JWT_ENV = "ZDT_URNETWORK_JWT"
    private const val KEY_SHARE = "SHARE"
    private const val KEY_ROUTING = "ROUTING"
    private const val KEY_COUNTRY = "COUNTRY"
    private const val KEY_REGION = "REGION"
    private const val KEY_CITY = "CITY"
    private const val KEY_PROVIDER_ID = "PROVIDER_ID"
    private const val KEY_SOCKS_ADDR = "SOCKS_ADDR"
    private const val KEY_MAX_MEMORY = "MAX_MEMORY"
    private const val KEY_NO_PORT_MAPPING = "NO_PORT_MAPPING"
    private const val KEY_API_URL = "API_URL"
    private const val KEY_PLATFORM_URL = "PLATFORM_URL"
    private const val KEY_API_HOSTS = "API_HOSTS"
    private val ENV_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    private fun settingBool(value: Boolean): String = if (value) "1" else "0"

    private fun boolSetting(values: Map<String, String>, key: String, default: Boolean): Boolean {
      val raw = values[key]?.trim()?.lowercase(Locale.ROOT) ?: return default
      return when (raw) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> default
      }
    }

    fun normalizeId(value: String): String {
      val normalized = value.trim().lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.', '_')
        .take(32)
      return normalized.ifBlank { "item-${System.currentTimeMillis().toString().takeLast(6)}" }
    }

    private fun decode(value: String): String = runCatching {
      Base64.decode(value, Base64.DEFAULT).toString(Charsets.UTF_8)
    }.getOrDefault("")
  }
}
