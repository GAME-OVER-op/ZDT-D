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

  suspend fun install(server: VpsServer, kind: VpsServiceKind, onLine: (String) -> Unit): VpsCommandResult =
    run(server, listOf("install", kind.wireId), onLine = onLine, timeoutMs = 12 * 60_000L)

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
    sni: String,
    onLine: (String) -> Unit,
  ): VpsCommandResult = run(
    server,
    listOf("create-profile", kind.wireId, id, name, port.toString(), mode, server.host, domain, email, sni),
    onLine = onLine,
    timeoutMs = 10 * 60_000L,
  )

  suspend fun deleteProfile(server: VpsServer, kind: VpsServiceKind, profileId: String): VpsCommandResult =
    run(server, listOf("delete-profile", kind.wireId, profileId), timeoutMs = 120_000L)

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

  suspend fun deleteClient(server: VpsServer, kind: VpsServiceKind, profileId: String, clientId: String): VpsCommandResult =
    run(server, listOf("delete-client", kind.wireId, profileId, clientId), timeoutMs = 3 * 60_000L)

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
    return VpsConfigResult(
      kind = kind,
      profileId = profileId,
      clientId = clientId,
      clientName = clientName,
      fileName = fileName,
      mimeType = mime,
      content = lines.subList(begin + 1, end).joinToString("\n").trimEnd(),
      shareLink = link,
    )
  }

  suspend fun restart(server: VpsServer, kind: VpsServiceKind, profileId: String?): VpsCommandResult {
    val args = mutableListOf("restart", kind.wireId)
    if (!profileId.isNullOrBlank()) args += profileId
    return run(server, args, timeoutMs = 90_000L)
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
  ): VpsCommandResult = withContext(Dispatchers.IO) {
    val upload = ssh.uploadText(server, remotePath, script)
    if (!upload.successful) return@withContext upload
    val command = buildString {
      append("bash ").append(VpsSshClient.shellQuote(remotePath))
      args.forEach { append(' ').append(VpsSshClient.shellQuote(it)) }
    }
    ssh.execute(server, command, timeoutMs, onLine)
  }

  companion object {
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
