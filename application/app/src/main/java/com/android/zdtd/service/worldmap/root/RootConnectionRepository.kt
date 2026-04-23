package com.android.zdtd.service.worldmap.root

import com.android.zdtd.service.worldmap.WorldMapStrings
import com.android.zdtd.service.worldmap.model.ConnectionSample
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RootConnectionRepository(
    private val strings: WorldMapStrings,
) {

    private data class CommandSpec(
        val protocolBase: String,
        val command: String,
    )

    private data class SsBlock(
        val protocolBase: String,
        val block: String,
    )

    private var lastRootCheckAt = 0L
    private var cachedRootReady = false

    suspend fun checkRoot(): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastRootCheckAt < ROOT_CACHE_MS) {
            return@withContext cachedRootReady
        }

        cachedRootReady = runCatching {
            val result = Shell.cmd("id").exec()
            result.code == 0 && result.out.any { it.contains("uid=0") }
        }.getOrDefault(false)
        lastRootCheckAt = now
        cachedRootReady
    }

    suspend fun loadConnections(): Result<List<ConnectionSample>> = withContext(Dispatchers.IO) {
        runCatching {
            val blocks = executeSsBlocks()
            blocks
                .asSequence()
                .mapNotNull(::parseSsBlock)
                .filterNot { it.remoteIp == "*" }
                .filterNot { isLocalAddress(it.remoteIp) }
                .filter { looksLikeInternetAddress(it.remoteIp) }
                .distinctBy { "${it.protocol}:${it.remoteIp}:${it.remotePort}" }
                .take(MAX_CONNECTIONS)
                .toList()
        }
    }

    suspend fun loadTotalTrafficBytes(): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val commands = listOf(
                "cat /proc/net/dev 2>/dev/null",
                "cat /proc/self/net/dev 2>/dev/null",
            )

            commands.asSequence().mapNotNull { command ->
                val result = Shell.cmd(command).exec()
                if (result.code == 0 && result.out.isNotEmpty()) {
                    parseTotalTrafficBytes(result.out)
                } else {
                    null
                }
            }.firstOrNull()
        }.getOrNull()
    }

    private fun executeSsBlocks(): List<SsBlock> {
        val commands = listOf(
            CommandSpec("tcp", "ss -H -tinmep 2>/dev/null | head -n 256"),
            CommandSpec("udp", "ss -H -uanepm 2>/dev/null | head -n 192"),
            CommandSpec("tcp", "ss -H -tinep 2>/dev/null | head -n 256"),
            CommandSpec("udp", "ss -H -uanep 2>/dev/null | head -n 192"),
            CommandSpec("tcp", "ss -H -tuna 2>/dev/null | head -n 192"),
        )

        val collected = mutableListOf<SsBlock>()
        var lastError = strings.failedConnectionsCommand()
        commands.forEach { spec ->
            val result = Shell.cmd(spec.command).exec()
            if (result.code == 0 && result.out.isNotEmpty()) {
                collected += groupSsBlocks(result.out).map { block ->
                    SsBlock(protocolBase = spec.protocolBase, block = block)
                }
            } else {
                lastError = result.err.joinToString(separator = "\n").ifBlank { lastError }
            }
        }
        if (collected.isNotEmpty()) return collected
        error(lastError)
    }

    private fun groupSsBlocks(lines: List<String>): List<String> {
        val blocks = mutableListOf<String>()
        val current = StringBuilder()
        lines.forEach { raw ->
            if (raw.isBlank()) return@forEach
            val line = raw.trimEnd()
            val startsNew = raw.isNotBlank() && !raw.first().isWhitespace()
            if (startsNew && current.isNotEmpty()) {
                blocks += current.toString()
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
        }
        if (current.isNotEmpty()) blocks += current.toString()
        return blocks
    }

    private fun parseTotalTrafficBytes(lines: List<String>): Long {
        var total = 0L
        lines.forEach { rawLine ->
            val line = rawLine.trim()
            if (!line.contains(':')) return@forEach
            val name = line.substringBefore(':').trim()
            if (name == "lo") return@forEach

            val fields = line.substringAfter(':').trim().split(Regex("\\s+"))
            if (fields.size < 16) return@forEach

            val rxBytes = fields.getOrNull(0)?.toLongOrNull() ?: 0L
            val txBytes = fields.getOrNull(8)?.toLongOrNull() ?: 0L
            total += rxBytes + txBytes
        }
        return total
    }

    private fun parseSsBlock(ssBlock: SsBlock): ConnectionSample? {
        val lines = ssBlock.block.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        val firstLine = lines.first().trim()
        val tokens = firstLine.split(Regex("\\s+"))
        if (tokens.size < 5) return null

        val state = tokens.getOrNull(0).orEmpty()
        val recvQueue = tokens.getOrNull(1)?.toLongOrNull() ?: 0L
        val sendQueue = tokens.getOrNull(2)?.toLongOrNull() ?: 0L
        val remoteEndpoint = tokens.getOrNull(4).orEmpty()
        val remote = splitEndpoint(remoteEndpoint) ?: return null
        if (remote.first.isBlank()) return null

        val protocol = normalizeProtocol(ssBlock.protocolBase, remote.first)
        if (protocol !in supportedProtocols) return null

        // UDP without a real remote endpoint is not a world-map peer.
        if (protocol.startsWith("udp") && (remote.second == 0 || remote.first == "*")) return null

        val merged = lines.joinToString(separator = " ")
        val bytesAcked = extractMetric(merged, bytesAckedRegex)
        val bytesReceived = extractMetric(merged, bytesReceivedRegex)
        val segsOut = extractMetric(merged, segsOutRegex)
        val segsIn = extractMetric(merged, segsInRegex)

        val txCounter = when {
            bytesAcked > 0L -> bytesAcked
            segsOut > 0L -> segsOut * TCP_SEGMENT_FALLBACK_BYTES
            else -> 0L
        }
        val rxCounter = when {
            bytesReceived > 0L -> bytesReceived
            segsIn > 0L -> segsIn * TCP_SEGMENT_FALLBACK_BYTES
            else -> 0L
        }

        return ConnectionSample(
            protocol = protocol,
            state = state,
            remoteIp = remote.first,
            remotePort = remote.second,
            latencyMs = latencyFromIp(remote.first),
            recvQueue = recvQueue,
            sendQueue = sendQueue,
            txCounterBytes = txCounter,
            rxCounterBytes = rxCounter,
        )
    }

    private fun extractMetric(block: String, regex: Regex): Long {
        return regex.find(block)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
    }

    private fun normalizeProtocol(protocolBase: String, remoteIp: String): String {
        return if (':' in remoteIp && protocolBase in ipv6CapableProtocols && !protocolBase.endsWith("6")) {
            protocolBase + "6"
        } else {
            protocolBase
        }
    }

    private fun splitEndpoint(endpoint: String): Pair<String, Int>? {
        if (endpoint.isBlank() || endpoint == "*" || endpoint == "*:*" || endpoint.endsWith(":*")) {
            return "*" to 0
        }

        if (endpoint.startsWith("[")) {
            val end = endpoint.indexOf(']')
            if (end <= 0) return null
            val host = normalizeHost(endpoint.substring(1, end))
            val port = endpoint.substringAfter("]:", "0").toIntOrNull() ?: 0
            return host to port
        }

        val idx = endpoint.lastIndexOf(':')
        if (idx <= 0) {
            val host = normalizeHost(endpoint)
            return if (looksLikeInternetAddress(host)) host to 0 else null
        }

        val hostPart = normalizeHost(endpoint.substring(0, idx))
        val portPart = endpoint.substring(idx + 1)

        return if (portPart.toIntOrNull() != null) {
            hostPart to portPart.toInt()
        } else if (looksLikeInternetAddress(normalizeHost(endpoint))) {
            normalizeHost(endpoint) to 0
        } else {
            null
        }
    }

    private fun normalizeHost(host: String): String {
        val noZone = host.substringBefore('%')
        return if (noZone.startsWith("::ffff:")) {
            noZone.removePrefix("::ffff:")
        } else {
            noZone
        }
    }

    private fun looksLikeInternetAddress(value: String): Boolean {
        if (value.isBlank()) return false
        if (value == "*" || value == "0.0.0.0" || value == "::") return false
        if (value.any { it.isWhitespace() }) return false
        if (value.any { it !in "0123456789abcdefABCDEF:.[].%-" }) return false
        return value.any { it == '.' || it == ':' }
    }

    private fun isLocalAddress(ip: String): Boolean {
        val normalized = normalizeHost(ip)
        if (normalized == "0.0.0.0" || normalized == "::" || normalized == "::1" || normalized == "127.0.0.1") return true
        if (normalized.startsWith("10.") || normalized.startsWith("192.168.") || normalized.startsWith("169.254.")) return true
        if (normalized.startsWith("fc") || normalized.startsWith("fd") || normalized.startsWith("fe80:")) return true

        val secondOctet = normalized.split('.').getOrNull(1)?.toIntOrNull()
        if (normalized.startsWith("172.") && secondOctet != null && secondOctet in 16..31) return true

        return false
    }

    private fun latencyFromIp(ip: String): Int {
        val sum = ip.fold(0) { acc, c -> acc + c.code }
        return 40 + (sum % 220)
    }

    private companion object {
        private const val ROOT_CACHE_MS = 12_000L
        private const val MAX_CONNECTIONS = 32
        private const val TCP_SEGMENT_FALLBACK_BYTES = 1460L

        private val supportedProtocols = setOf(
            "tcp", "tcp6",
            "udp", "udp6",
            "raw", "raw6",
            "icmp", "icmp6",
            "udplite", "udplite6",
            "sctp", "sctp6",
            "dccp", "dccp6",
        )
        private val ipv6CapableProtocols = setOf("tcp", "udp", "raw", "icmp", "udplite", "sctp", "dccp")
        private val bytesAckedRegex = Regex("bytes_acked:(\\d+)")
        private val bytesReceivedRegex = Regex("bytes_received:(\\d+)")
        private val segsOutRegex = Regex("segs_out:(\\d+)")
        private val segsInRegex = Regex("segs_in:(\\d+)")
    }
}
