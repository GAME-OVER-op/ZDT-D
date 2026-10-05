package com.android.zdtd.service

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Non-root counterpart of the existing root Opera API-proxy resolver.
 *
 * The UI accepts either concrete proxy URLs, comma/whitespace separated proxy
 * URLs, an http(s) URL to a plain-text list, or an absolute local list file.
 * Only one checked candidate is handed to the single Opera process.
 */
internal object NonRootOperaApiProxyResolver {
  private const val CHECK_HOST = "api2.sec-tunnel.com"
  private const val CHECK_PORT = 443
  private const val CHECK_TIMEOUT_MS = 4_000
  private const val DOWNLOAD_TIMEOUT_MS = 15_000
  private const val MAX_LIST_BYTES = 1024 * 1024

  fun resolve(raw: String, cacheFile: File, logFile: File): String? {
    logFile.parentFile?.mkdirs()
    logFile.writeText("")
    val candidates = collectCandidates(raw, cacheFile, logFile)
    if (candidates.isEmpty()) {
      appendLog(logFile, if (raw.isBlank()) "api_proxy is empty -> start without -api-proxy" else "no valid api_proxy candidates -> start without -api-proxy")
      return null
    }

    appendLog(logFile, "checking ${candidates.size} api_proxy candidate(s)")
    candidates.forEach { candidate ->
      val ok = runCatching { check(candidate) }.getOrDefault(false)
      appendLog(logFile, "${if (ok) "OK" else "FAIL"} ${mask(candidate.original)}")
      if (ok) {
        appendLog(logFile, "selected ${mask(candidate.original)}")
        return candidate.original
      }
    }
    appendLog(logFile, "no working api_proxy found -> start without -api-proxy")
    return null
  }

  private fun collectCandidates(raw: String, cacheFile: File, logFile: File): List<Candidate> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return emptyList()

    val tokens = splitTokens(trimmed)
    val files = tokens.filter(::isAbsoluteListPath)
    if (files.isNotEmpty()) {
      appendLog(logFile, "api_proxy list file mode: ${files.size} file(s)")
      val combined = buildString {
        files.forEach { path ->
          val text = runCatching { readLimited(File(path)) }.getOrElse {
            appendLog(logFile, "failed to read api_proxy list file ${path}: ${it.message}")
            ""
          }
          if (text.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append(text)
          }
        }
      }
      writeCache(cacheFile, combined, logFile)
      return parseCandidates(combined, logFile)
    }

    val listUrls = tokens.filter(::isListUrl)
    if (listUrls.isNotEmpty()) {
      appendLog(logFile, "api_proxy list url mode: ${listUrls.size} URL(s)")
      val combined = buildString {
        listUrls.forEach { url ->
          val text = runCatching { downloadLimited(url) }.getOrElse {
            appendLog(logFile, "failed to download api_proxy list from ${mask(url)}: ${it.message}")
            ""
          }
          if (text.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append(text)
          }
        }
      }
      writeCache(cacheFile, combined, logFile)
      return parseCandidates(combined, logFile)
    }

    return parseCandidates(trimmed, logFile)
  }

  private fun parseCandidates(raw: String, logFile: File): List<Candidate> {
    val seen = linkedSetOf<String>()
    val out = mutableListOf<Candidate>()
    splitTokens(raw).forEach { token ->
      if (isListUrl(token)) {
        appendLog(logFile, "skip nested api_proxy list URL: ${mask(token)}")
        return@forEach
      }
      val candidate = parseCandidate(token)
      if (candidate == null) {
        appendLog(logFile, "skip invalid api_proxy candidate: ${mask(token)}")
      } else if (seen.add(candidate.original)) {
        out += candidate
      }
    }
    return out
  }

  private fun parseCandidate(token: String): Candidate? {
    val separator = token.indexOf("://")
    if (separator <= 0) return null
    val scheme = token.substring(0, separator).lowercase()
    if (scheme !in setOf("http", "https", "socks5", "socks5h")) return null
    val rest = token.substring(separator + 3).trim()
    if (rest.isEmpty() || rest.contains('/') || rest.contains('?') || rest.contains('#')) return null

    val at = rest.lastIndexOf('@')
    val userInfo = if (at >= 0) rest.substring(0, at) else null
    val hostPort = if (at >= 0) rest.substring(at + 1) else rest
    val (username, password) = if (userInfo != null) {
      if (userInfo.isEmpty()) return null
      val colon = userInfo.indexOf(':')
      val user = if (colon >= 0) userInfo.substring(0, colon) else userInfo
      val pass = if (colon >= 0) userInfo.substring(colon + 1) else ""
      if (user.isEmpty()) return null
      user to pass
    } else null to null

    val parsed = parseHostPort(hostPort) ?: return null
    return Candidate(token, scheme, parsed.first, parsed.second, username, password)
  }

  private fun parseHostPort(value: String): Pair<String, Int>? {
    val text = value.trim()
    if (text.startsWith('[')) {
      val end = text.indexOf(']')
      if (end <= 1 || end + 2 > text.length || text.getOrNull(end + 1) != ':') return null
      val host = text.substring(1, end).trim()
      val port = text.substring(end + 2).toIntOrNull() ?: return null
      return if (host.isNotEmpty() && port in 1..65535) host to port else null
    }
    val colon = text.lastIndexOf(':')
    if (colon <= 0) return null
    val host = text.substring(0, colon).trim()
    val port = text.substring(colon + 1).toIntOrNull() ?: return null
    return if (host.isNotEmpty() && port in 1..65535) host to port else null
  }

  private fun check(candidate: Candidate): Boolean = when (candidate.scheme) {
    "socks5", "socks5h" -> checkSocks5(candidate)
    "http" -> checkHttp(candidate)
    "https" -> connect(candidate).use { true }
    else -> false
  }

  private fun connect(candidate: Candidate): Socket = Socket().apply {
    soTimeout = CHECK_TIMEOUT_MS
    connect(InetSocketAddress(candidate.host, candidate.port), CHECK_TIMEOUT_MS)
  }

  private fun checkHttp(candidate: Candidate): Boolean = runCatching {
    connect(candidate).use { socket ->
      val target = "$CHECK_HOST:$CHECK_PORT"
      val request = buildString {
        append("CONNECT $target HTTP/1.1\r\n")
        append("Host: $target\r\n")
        append("Proxy-Connection: close\r\n")
        append("User-Agent: ZDT-D/1\r\n")
        candidate.username?.takeIf(String::isNotEmpty)?.let { user ->
          val pass = candidate.password.orEmpty()
          val encoded = Base64.getEncoder().encodeToString("$user:$pass".toByteArray(StandardCharsets.UTF_8))
          append("Proxy-Authorization: Basic $encoded\r\n")
        }
        append("\r\n")
      }
      socket.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
      socket.getOutputStream().flush()
      val data = ByteArray(192)
      val count = socket.getInputStream().read(data)
      if (count <= 0) return@use false
      val status = String(data, 0, count, StandardCharsets.US_ASCII)
      status.startsWith("HTTP/1.1 200") || status.startsWith("HTTP/1.0 200")
    }
  }.getOrDefault(false)

  private fun checkSocks5(candidate: Candidate): Boolean = runCatching {
    connect(candidate).use { socket ->
      val input = socket.getInputStream()
      val output = socket.getOutputStream()
      val useAuth = !candidate.username.isNullOrEmpty()
      output.write(if (useAuth) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00))
      output.flush()
      val method = ByteArray(2)
      if (!readExactly(input, method) || method[0].toInt() != 0x05 || (method[1].toInt() and 0xff) == 0xff) return@use false

      if (method[1].toInt() == 0x02) {
        val user = candidate.username.orEmpty().toByteArray(StandardCharsets.UTF_8)
        val pass = candidate.password.orEmpty().toByteArray(StandardCharsets.UTF_8)
        if (user.isEmpty() || user.size > 255 || pass.size > 255) return@use false
        output.write(byteArrayOf(0x01, user.size.toByte()))
        output.write(user)
        output.write(byteArrayOf(pass.size.toByte()))
        output.write(pass)
        output.flush()
        val reply = ByteArray(2)
        if (!readExactly(input, reply) || reply[1].toInt() != 0x00) return@use false
      } else if (method[1].toInt() != 0x00) {
        return@use false
      }

      val host = CHECK_HOST.toByteArray(StandardCharsets.US_ASCII)
      if (host.size > 255) return@use false
      output.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, host.size.toByte()))
      output.write(host)
      output.write(byteArrayOf((CHECK_PORT ushr 8).toByte(), CHECK_PORT.toByte()))
      output.flush()

      val head = ByteArray(4)
      if (!readExactly(input, head) || head[0].toInt() != 0x05 || head[1].toInt() != 0x00) return@use false
      val tail = when (head[3].toInt() and 0xff) {
        0x01 -> 6
        0x03 -> {
          val length = input.read()
          if (length < 0) return@use false
          length + 2
        }
        0x04 -> 18
        else -> return@use false
      }
      discardExactly(input, tail)
    }
  }.getOrDefault(false)

  private fun readExactly(input: InputStream, buffer: ByteArray): Boolean {
    var offset = 0
    while (offset < buffer.size) {
      val read = input.read(buffer, offset, buffer.size - offset)
      if (read <= 0) return false
      offset += read
    }
    return true
  }

  private fun discardExactly(input: InputStream, count: Int): Boolean {
    var left = count
    val buffer = ByteArray(minOf(256, count.coerceAtLeast(1)))
    while (left > 0) {
      val read = input.read(buffer, 0, minOf(buffer.size, left))
      if (read <= 0) return false
      left -= read
    }
    return true
  }

  private fun splitTokens(raw: String): List<String> = buildList {
    raw.lineSequence().forEach { line ->
      val withoutComment = line.substringBefore('#')
      withoutComment.split(Regex("[,\\s]+"))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .forEach(::add)
    }
  }

  private fun isListUrl(token: String): Boolean {
    val lower = token.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    val schemeEnd = token.indexOf("://")
    val rest = token.substring(schemeEnd + 3)
    val firstTail = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    if (firstTail < 0) return false
    val tail = rest.substring(firstTail)
    return tail !in setOf("", "/", "?", "#")
  }

  private fun isAbsoluteListPath(token: String): Boolean = File(token).isAbsolute

  private fun downloadLimited(rawUrl: String): String {
    var current = URI(rawUrl).toURL()
    repeat(6) { redirectCount ->
      val connection = (current.openConnection() as HttpURLConnection).apply {
        connectTimeout = DOWNLOAD_TIMEOUT_MS
        readTimeout = DOWNLOAD_TIMEOUT_MS
        instanceFollowRedirects = false
        requestMethod = "GET"
        setRequestProperty("User-Agent", "ZDT-D/1")
      }
      connection.connect()
      val code = connection.responseCode
      if (code in 300..399) {
        val next = connection.getHeaderField("Location") ?: error("redirect without Location")
        connection.disconnect()
        if (redirectCount == 5) error("too many redirects")
        current = current.toURI().resolve(next).toURL()
        return@repeat
      }
      if (code !in 200..299) {
        connection.disconnect()
        error("HTTP status $code")
      }
      val text = connection.inputStream.use(::readLimited)
      connection.disconnect()
      return text
    }
    error("too many redirects")
  }

  private fun readLimited(file: File): String = file.inputStream().use(::readLimited)

  private fun readLimited(input: InputStream): String {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      total += read
      if (total > MAX_LIST_BYTES) error("api_proxy list is larger than $MAX_LIST_BYTES byte(s)")
      out.write(buffer, 0, read)
    }
    return out.toString(StandardCharsets.UTF_8.name())
  }

  private fun writeCache(cacheFile: File, text: String, logFile: File) {
    runCatching {
      cacheFile.parentFile?.mkdirs()
      cacheFile.writeText(text)
    }.onFailure { appendLog(logFile, "failed to save api_proxy list ${cacheFile.absolutePath}: ${it.message}") }
  }

  private fun appendLog(file: File, line: String) {
    runCatching { BoundedLogWriter.appendLine(file, line) }
  }

  private fun mask(value: String): String {
    val scheme = value.substringBefore("://", missingDelimiterValue = "")
    val rest = value.substringAfter("://", missingDelimiterValue = "")
    if (scheme.isEmpty() || rest.isEmpty()) return value
    val at = rest.lastIndexOf('@')
    return if (at >= 0) "$scheme://***@${rest.substring(at + 1)}" else value
  }

  private data class Candidate(
    val original: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
  )
}
