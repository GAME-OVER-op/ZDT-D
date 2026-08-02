package com.android.zdtd.service.vps

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.concurrent.TimeUnit

class VpsSshClient {
  private val connectTimeoutMs = 12_000
  private val commandTimeoutMs = 120_000L

  suspend fun probe(host: String, port: Int, username: String, password: String): VpsProbeResult = withContext(Dispatchers.IO) {
    require(host.isNotBlank()) { "Host is empty" }
    require(username.isNotBlank()) { "Username is empty" }
    require(password.isNotEmpty()) { "Password is empty" }
    val session = openSession(host, port, username, password, pinnedHostKey = null)
    try {
      val hostKey = session.hostKey ?: error("SSH server did not provide a host key")
      val keyBase64 = hostKey.key
      val keyBytes = runCatching { Base64.getDecoder().decode(keyBase64) }
        .getOrElse { error("SSH server returned an invalid host key") }
      val fingerprint = sha256Fingerprint(keyBytes)
      val result = executeOnSession(session, probeCommand(), timeoutMs = 30_000L)
      check(result.successful) { result.output.ifBlank { "Server probe failed" } }
      val map = parseKeyValue(result.output)
      val privilege = map["privilege"].orEmpty()
      check(privilege == "root") {
        "The SSH account must log in with uid 0 (root)"
      }
      val metrics = metricsFromMap(map).copy(reachability = VpsReachability.ONLINE, lastUpdatedAt = System.currentTimeMillis())
      VpsProbeResult(keyBase64, fingerprint, metrics, privilege)
    } finally {
      session.disconnect()
    }
  }

  suspend fun collectMetrics(server: VpsServer): VpsMetrics = withContext(Dispatchers.IO) {
    val session = openSession(server.host, server.port, server.username, server.password, server.pinnedHostKey)
    try {
      val result = executeOnSession(session, metricsCommand(), timeoutMs = 20_000L)
      check(result.successful) { result.output.ifBlank { "Unable to read server state" } }
      metricsFromMap(parseKeyValue(result.output)).copy(
        reachability = VpsReachability.ONLINE,
        lastUpdatedAt = System.currentTimeMillis(),
      )
    } finally {
      session.disconnect()
    }
  }

  suspend fun execute(
    server: VpsServer,
    command: String,
    timeoutMs: Long = commandTimeoutMs,
    onLine: ((String) -> Unit)? = null,
  ): VpsCommandResult = withContext(Dispatchers.IO) {
    val session = openSession(server.host, server.port, server.username, server.password, server.pinnedHostKey)
    try {
      executeOnSession(session, command, timeoutMs, onLine)
    } finally {
      session.disconnect()
    }
  }

  suspend fun uploadText(server: VpsServer, remotePath: String, content: String): VpsCommandResult = withContext(Dispatchers.IO) {
    val session = openSession(server.host, server.port, server.username, server.password, server.pinnedHostKey)
    try {
      val channel = session.openChannel("exec") as ChannelExec
      channel.setCommand("umask 077; cat > ${shellQuote(remotePath)} && chmod 700 ${shellQuote(remotePath)}")
      channel.setInputStream(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)))
      val stdout = channel.inputStream
      val stderr = ByteArrayOutputStream()
      channel.setErrStream(stderr)
      channel.connect(connectTimeoutMs)
      val started = System.nanoTime()
      while (!channel.isClosed) {
        if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) > commandTimeoutMs) {
          channel.disconnect()
          return@withContext VpsCommandResult(124, "Upload timeout")
        }
        Thread.sleep(25)
      }
      val out = buildString {
        append(stdout.readBytes().toString(Charsets.UTF_8))
        val err = stderr.toString(Charsets.UTF_8.name())
        if (err.isNotBlank()) {
          if (isNotEmpty() && !endsWith('\n')) append('\n')
          append(err)
        }
      }.trim()
      VpsCommandResult(channel.exitStatus, out)
    } finally {
      session.disconnect()
    }
  }

  private fun openSession(
    host: String,
    port: Int,
    username: String,
    password: String,
    pinnedHostKey: String?,
  ): Session {
    val jsch = JSch()
    if (!pinnedHostKey.isNullOrBlank()) {
      jsch.hostKeyRepository = PinnedHostKeyRepository(host, pinnedHostKey)
    }
    val session = jsch.getSession(username, host, port)
    session.setPassword(password)
    val config = Properties().apply {
      put("PreferredAuthentications", "password,keyboard-interactive")
      put("StrictHostKeyChecking", if (pinnedHostKey.isNullOrBlank()) "no" else "yes")
      put("ServerAliveInterval", "10000")
      put("ServerAliveCountMax", "2")
    }
    session.setConfig(config)
    session.timeout = connectTimeoutMs
    session.connect(connectTimeoutMs)
    return session
  }

  private fun executeOnSession(
    session: Session,
    command: String,
    timeoutMs: Long,
    onLine: ((String) -> Unit)? = null,
  ): VpsCommandResult {
    val channel = session.openChannel("exec") as ChannelExec
    channel.setCommand("LC_ALL=C LANG=C bash -lc ${shellQuote("$command 2>&1")}")
    channel.setInputStream(null)
    val input = channel.inputStream
    channel.connect(connectTimeoutMs)
    val started = System.nanoTime()
    val output = ByteArrayOutputStream()
    val lineBuffer = StringBuilder()
    val buffer = ByteArray(8 * 1024)

    fun drainAvailable() {
      while (input.available() > 0) {
        val count = input.read(buffer, 0, minOf(buffer.size, input.available()))
        if (count <= 0) break
        output.write(buffer, 0, count)
        if (onLine != null) {
          lineBuffer.append(buffer.copyOfRange(0, count).toString(Charsets.UTF_8))
          while (true) {
            val newline = lineBuffer.indexOf("\n")
            if (newline < 0) break
            onLine(lineBuffer.substring(0, newline).trimEnd('\r'))
            lineBuffer.delete(0, newline + 1)
          }
        }
      }
    }

    while (!channel.isClosed) {
      drainAvailable()
      if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) > timeoutMs) {
        channel.disconnect()
        val partial = output.toByteArray().toString(Charsets.UTF_8).trim()
        return VpsCommandResult(124, listOf(partial, "Command timeout").filter { it.isNotBlank() }.joinToString("\n"))
      }
      Thread.sleep(20)
    }
    drainAvailable()
    if (onLine != null && lineBuffer.isNotEmpty()) onLine(lineBuffer.toString().trimEnd('\r'))
    val code = channel.exitStatus
    channel.disconnect()
    return VpsCommandResult(code, output.toByteArray().toString(Charsets.UTF_8).trim())
  }

  private fun parseKeyValue(text: String): Map<String, String> = buildMap {
    text.lineSequence().forEach { raw ->
      val index = raw.indexOf('=')
      if (index <= 0) return@forEach
      put(raw.substring(0, index).trim(), raw.substring(index + 1).trim())
    }
  }

  private fun metricsFromMap(map: Map<String, String>): VpsMetrics = VpsMetrics(
    osName = map["os_name"].orEmpty(),
    osId = map["os_id"].orEmpty().lowercase(),
    osVersion = map["os_version"].orEmpty(),
    architecture = map["arch"].orEmpty().lowercase(),
    cpuModel = map["cpu_model"].orEmpty(),
    cpuCores = map["cpu_cores"]?.toIntOrNull() ?: 0,
    cpuPercent = map["cpu_percent"]?.toDoubleOrNull() ?: 0.0,
    ramUsedBytes = map["ram_used"]?.toLongOrNull() ?: 0L,
    ramTotalBytes = map["ram_total"]?.toLongOrNull() ?: 0L,
    diskUsedBytes = map["disk_used"]?.toLongOrNull() ?: 0L,
    diskTotalBytes = map["disk_total"]?.toLongOrNull() ?: 0L,
    uptimeSeconds = map["uptime"]?.toDoubleOrNull()?.toLong() ?: 0L,
  )

  private fun metricsCommand(): String = """
    set -u
    . /etc/os-release 2>/dev/null || true
    read cpu user nice system idle iowait irq softirq steal guest guest_nice < /proc/stat
    total1=§((user+nice+system+idle+iowait+irq+softirq+steal))
    idle1=§((idle+iowait))
    sleep 0.25
    read cpu user nice system idle iowait irq softirq steal guest guest_nice < /proc/stat
    total2=§((user+nice+system+idle+iowait+irq+softirq+steal))
    idle2=§((idle+iowait))
    dt=§((total2-total1)); di=§((idle2-idle1))
    cpu_pct=§(awk -v dt="§dt" -v di="§di" 'BEGIN { if (dt<=0) print "0.0"; else printf "%.1f", (dt-di)*100/dt }')
    mem_total=§(awk '/^MemTotal:/ {print §2*1024}' /proc/meminfo)
    mem_avail=§(awk '/^MemAvailable:/ {print §2*1024}' /proc/meminfo)
    disk=§(df -B1 / | awk 'NR==2 {print §3" "§2}')
    printf 'os_name=%s\n' "§{PRETTY_NAME:-unknown}"
    printf 'os_id=%s\n' "§{ID:-unknown}"
    printf 'os_version=%s\n' "§{VERSION_ID:-unknown}"
    printf 'arch=%s\n' "§(uname -m)"
    printf 'cpu_model=%s\n' "§(awk -F: '/model name|Hardware/ {gsub(/^[ \t]+/,"",§2); print §2; exit}' /proc/cpuinfo)"
    printf 'cpu_cores=%s\n' "§(getconf _NPROCESSORS_ONLN 2>/dev/null || nproc)"
    printf 'cpu_percent=%s\n' "§cpu_pct"
    printf 'ram_total=%s\n' "§mem_total"
    printf 'ram_used=%s\n' "§((mem_total-mem_avail))"
    printf 'disk_used=%s\n' "§{disk%% *}"
    printf 'disk_total=%s\n' "§{disk##* }"
    printf 'uptime=%s\n' "§(cut -d. -f1 /proc/uptime)"
  """.trimIndent().replace('§', '$')

  private fun probeCommand(): String = metricsCommand() + "\n" + """
    if [ "§(id -u)" = "0" ]; then
      echo privilege=root
    else
      echo privilege=none
    fi
  """.trimIndent().replace('§', '$')

  companion object {
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun sha256Fingerprint(key: ByteArray): String {
      val digest = MessageDigest.getInstance("SHA-256").digest(key)
      return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }
  }

  private class PinnedHostKeyRepository(
    private val host: String,
    private val expectedBase64: String,
  ) : HostKeyRepository {
    private val expected = runCatching { Base64.getDecoder().decode(expectedBase64) }.getOrDefault(ByteArray(0))

    override fun check(host: String?, key: ByteArray?): Int {
      if (key == null) return HostKeyRepository.NOT_INCLUDED
      return if (key.contentEquals(expected)) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }

    override fun add(hostkey: HostKey?, ui: com.jcraft.jsch.UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "ZDT-D pinned VPS host key"
    override fun getHostKey(): Array<HostKey> = buildHostKeys()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = buildHostKeys()

    private fun buildHostKeys(): Array<HostKey> {
      if (expected.isEmpty()) return emptyArray()
      return try {
        arrayOf(HostKey(host, expected))
      } catch (_: JSchException) {
        emptyArray()
      }
    }
  }
}
