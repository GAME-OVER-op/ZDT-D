package com.android.zdtd.service.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Base64
import com.android.zdtd.service.BuildConfig
import com.android.zdtd.service.RootConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread

class RemoteHostServer(
  private val context: Context,
  private val root: RootConfigManager,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val random = SecureRandom()
  private var serverSocket: ServerSocket? = null
  private var udpJob: Job? = null
  private var nsdManager: NsdManager? = null
  private var nsdRegistration: NsdManager.RegistrationListener? = null
  private var acceptThread: Thread? = null
  private var sessionToken: String = ""

  private val _state = MutableStateFlow(RemoteHostState())
  val state: StateFlow<RemoteHostState> = _state.asStateFlow()

  fun start(): RemoteHostState {
    stop()
    val host = localIpAddress().ifBlank { "127.0.0.1" }
    val socket = (10320..10340).firstNotNullOfOrNull { port ->
      runCatching { ServerSocket(port).apply { reuseAddress = true } }.getOrNull()
    } ?: run {
      val failed = RemoteHostState(error = "Не удалось открыть порт 10320–10340")
      _state.value = failed
      return failed
    }
    val code = generateCode()
    sessionToken = UUID.randomUUID().toString().replace("-", "")
    serverSocket = socket
    val running = RemoteHostState(
      running = true,
      host = host,
      port = socket.localPort,
      code = code,
      qrPayload = RemoteProtocol.qrPayload(host, socket.localPort, code),
    )
    _state.value = running
    acceptThread = thread(name = "ZDTD-RemoteHost", isDaemon = true) { acceptLoop(socket, code) }
    registerNsd(socket.localPort)
    udpJob = scope.launch { udpDiscoveryLoop(host, socket.localPort) }
    return running
  }

  fun stop() {
    unregisterNsd()
    runCatching { serverSocket?.close() }
    serverSocket = null
    udpJob?.cancel()
    udpJob = null
    acceptThread = null
    sessionToken = ""
    _state.value = RemoteHostState()
  }


  private fun registerNsd(port: Int) {
    val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
    val info = NsdServiceInfo().apply {
      serviceName = "ZDT-D ${RemoteProtocol.localDeviceName()}"
      serviceType = "_zdtd-remote._tcp."
      setPort(port)
    }
    val listener = object : NsdManager.RegistrationListener {
      override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) = Unit
      override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
        logWarn("NSD registration failed: $errorCode")
      }
      override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) = Unit
      override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit
    }
    nsdManager = manager
    nsdRegistration = listener
    runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
  }

  private fun unregisterNsd() {
    val manager = nsdManager
    val listener = nsdRegistration
    if (manager != null && listener != null) {
      runCatching { manager.unregisterService(listener) }
    }
    nsdManager = null
    nsdRegistration = null
  }

  private fun logWarn(message: String) {
    android.util.Log.w("ZDTD-RemoteHost", message)
  }

  private fun acceptLoop(socket: ServerSocket, code: String) {
    while (!socket.isClosed) {
      val client = runCatching { socket.accept() }.getOrNull() ?: break
      thread(name = "ZDTD-RemoteClient", isDaemon = true) { handleClient(client, code) }
    }
  }

  private fun handleClient(socket: Socket, code: String) {
    socket.use { s ->
      val reader = BufferedReader(InputStreamReader(s.getInputStream()))
      val requestLine = reader.readLine() ?: return
      val parts = requestLine.split(" ")
      if (parts.size < 2) return
      val method = parts[0].uppercase()
      val rawPath = parts[1]
      var contentLength = 0
      var auth = ""
      while (true) {
        val line = reader.readLine() ?: return
        if (line.isEmpty()) break
        val idx = line.indexOf(':')
        if (idx > 0) {
          val name = line.substring(0, idx).trim().lowercase()
          val value = line.substring(idx + 1).trim()
          if (name == "content-length") contentLength = value.toIntOrNull() ?: 0
          if (name == "authorization" || name == "x-api-key") auth = value.removePrefix("Bearer ").trim()
        }
      }
      val body = if (contentLength > 0) CharArray(contentLength).let { buf ->
        reader.read(buf, 0, contentLength)
        String(buf)
      } else ""
      val path = rawPath.substringBefore('?')
      val query = rawPath.substringAfter('?', "")
      when {
        path == "/remote/info" -> writeJson(s.getOutputStream(), 200, RemoteProtocol.infoJson(_state.value.host, _state.value.port, _state.value.running))
        path == "/remote/ping" -> writeJson(s.getOutputStream(), 200, RemoteProtocol.infoJson(_state.value.host, _state.value.port, _state.value.running).put("ok", true))
        path == "/remote/pair" && method == "POST" -> handlePair(s.getOutputStream(), body, code)
        path.startsWith("/remote/root/") -> handleRootProxy(s.getOutputStream(), path, body, auth)
        path.startsWith("/api/") -> handleApiProxy(s.getOutputStream(), method, rawPath, body, auth)
        else -> writeJson(s.getOutputStream(), 404, JSONObject().put("ok", false).put("error", "not_found"))
      }
    }
  }

  private fun handlePair(out: OutputStream, body: String, code: String) {
    val obj = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrDefault(JSONObject())
    val gotCode = obj.optString("code", "").replace("-", "").uppercase()
    val remoteVersion = obj.optInt("appVersionCode", 0)
    if (remoteVersion != BuildConfig.VERSION_CODE) {
      writeJson(out, 409, JSONObject().put("ok", false).put("error", "version_mismatch").put("hostVersionCode", BuildConfig.VERSION_CODE).put("clientVersionCode", remoteVersion))
      return
    }
    if (gotCode != code) {
      writeJson(out, 403, JSONObject().put("ok", false).put("error", "bad_code"))
      return
    }
    _state.value = _state.value.copy(pairedDevice = obj.optString("deviceName", "Телефон"))
    writeJson(out, 200, JSONObject()
      .put("ok", true)
      .put("sessionToken", sessionToken)
      .put("device", RemoteProtocol.infoJson(_state.value.host, _state.value.port, true).put("sessionToken", sessionToken)))
  }


  private fun handleRootProxy(out: OutputStream, path: String, body: String, auth: String) {
    if (sessionToken.isBlank() || auth != sessionToken) {
      writeJson(out, 401, JSONObject().put("ok", false).put("error", "unauthorized"))
      return
    }
    val obj = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrDefault(JSONObject())
    when (path) {
      "/remote/root/read" -> {
        val file = obj.optString("path", "")
        if (file.isBlank()) {
          writeJson(out, 400, JSONObject().put("ok", false).put("error", "path_required"))
        } else {
          val content = runCatching { root.readTextFile(file) }.getOrElse { "" }
          writeJson(out, 200, JSONObject().put("ok", true).put("content", content))
        }
      }
      "/remote/root/write" -> {
        val file = obj.optString("path", "")
        val content = obj.optString("content", "")
        if (file.isBlank()) {
          writeJson(out, 400, JSONObject().put("ok", false).put("error", "path_required"))
        } else {
          val ok = runCatching { root.writeTextFile(file, content) }.getOrDefault(false)
          writeJson(out, if (ok) 200 else 500, JSONObject().put("ok", ok))
        }
      }
      "/remote/root/exec" -> {
        val script = obj.optString("script", "")
        if (script.isBlank()) {
          writeJson(out, 400, JSONObject().put("ok", false).put("error", "script_required"))
        } else {
          val result = runCatching { root.execRootSh(script) }.getOrNull()
          writeJson(out, 200, JSONObject()
            .put("ok", result?.isSuccess == true)
            .put("code", runCatching { result?.code ?: -1 }.getOrDefault(-1))
            .put("out", result?.out?.joinToString("\n") ?: "")
            .put("err", result?.err?.joinToString("\n") ?: ""))
        }
      }
      "/remote/root/writeBytes" -> {
        val file = obj.optString("path", "")
        val data64 = obj.optString("base64", "")
        if (file.isBlank()) {
          writeJson(out, 400, JSONObject().put("ok", false).put("error", "path_required"))
        } else {
          val tmp = File(context.cacheDir, "remote_write_${System.currentTimeMillis()}.bin")
          val ok = runCatching {
            tmp.writeBytes(Base64.decode(data64, Base64.DEFAULT))
            val parent = File(file).parent ?: "/"
            val r = root.execRootSh("mkdir -p ${shellQuote(parent)} 2>/dev/null || true; cp -f ${shellQuote(tmp.absolutePath)} ${shellQuote(file)} 2>/dev/null || cat ${shellQuote(tmp.absolutePath)} > ${shellQuote(file)}; chmod 0644 ${shellQuote(file)} 2>/dev/null || true")
            r.isSuccess
          }.getOrDefault(false)
          runCatching { tmp.delete() }
          writeJson(out, if (ok) 200 else 500, JSONObject().put("ok", ok))
        }
      }
      "/remote/root/readBytes" -> {
        val file = obj.optString("path", "")
        if (file.isBlank()) {
          writeJson(out, 400, JSONObject().put("ok", false).put("error", "path_required"))
        } else {
          val tmp = File(context.cacheDir, "remote_read_${System.currentTimeMillis()}.bin")
          val ok = runCatching {
            val r = root.execRootSh("rm -f ${shellQuote(tmp.absolutePath)} 2>/dev/null || true; cp -f ${shellQuote(file)} ${shellQuote(tmp.absolutePath)} 2>/dev/null || cat ${shellQuote(file)} > ${shellQuote(tmp.absolutePath)}; chmod 0644 ${shellQuote(tmp.absolutePath)} 2>/dev/null || true")
            r.isSuccess && tmp.exists()
          }.getOrDefault(false)
          if (ok) {
            val data64 = Base64.encodeToString(tmp.readBytes(), Base64.NO_WRAP)
            runCatching { tmp.delete() }
            writeJson(out, 200, JSONObject().put("ok", true).put("base64", data64))
          } else {
            runCatching { tmp.delete() }
            writeJson(out, 500, JSONObject().put("ok", false))
          }
        }
      }
      else -> writeJson(out, 404, JSONObject().put("ok", false).put("error", "not_found"))
    }
  }

  private fun handleApiProxy(out: OutputStream, method: String, rawPath: String, body: String, auth: String) {
    if (sessionToken.isBlank() || auth != sessionToken) {
      writeJson(out, 401, JSONObject().put("ok", false).put("error", "unauthorized"))
      return
    }
    val path = rawPath
    val wrapped = when (method) {
      "GET" -> root.proxyGet(path)
      "POST" -> root.proxyPost(path, body.ifBlank { "{}" })
      "PUT" -> root.proxyPut(path, body.ifBlank { "{}" })
      "DELETE" -> root.proxyDelete(path, body.ifBlank { "{}" })
      else -> root.proxyPost(path, body.ifBlank { "{}" })
    }
    val obj = runCatching { JSONObject(wrapped) }.getOrDefault(JSONObject().put("code", 500).put("body", "{}").put("error", "bad_proxy"))
    val code = obj.optInt("code", 500).let { if (it == 0) 500 else it }
    val responseBody = obj.optString("body", "{}").ifBlank { "{}" }
    writeRaw(out, code, responseBody, "application/json; charset=utf-8")
  }

  private suspend fun udpDiscoveryLoop(host: String, port: Int) {
    val socket = runCatching {
      DatagramSocket(RemoteProtocol.UDP_PORT, InetAddress.getByName("0.0.0.0")).apply {
        broadcast = true
        soTimeout = 1000
      }
    }.getOrElse {
      logWarn("UDP discovery unavailable on ${RemoteProtocol.UDP_PORT}: ${it.message ?: it}")
      return
    }
    socket.use { ds ->
      val buf = ByteArray(1024)
      while (scope.isActive && _state.value.running) {
        val packet = DatagramPacket(buf, buf.size)
        val got = runCatching { ds.receive(packet); packet }.getOrNull()
        if (got != null) {
          val text = String(got.data, 0, got.length).trim()
          if (text == RemoteProtocol.DISCOVER) {
            val json = RemoteProtocol.infoJson(host, port, true).toString().toByteArray()
            runCatching { ds.send(DatagramPacket(json, json.size, got.address, got.port)) }
          }
        }
        delay(20)
      }
    }
  }

  private fun writeJson(out: OutputStream, code: Int, obj: JSONObject) = writeRaw(out, code, obj.toString(), "application/json; charset=utf-8")

  private fun writeRaw(out: OutputStream, code: Int, body: String, type: String) {
    val status = when (code) { 200 -> "OK"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; else -> "Error" }
    val bytes = body.toByteArray(Charsets.UTF_8)
    out.write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
    out.write(bytes)
    out.flush()
  }

  private fun generateCode(): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
  }

  private fun shellQuote(value: String): String = "'" + value.replace("'", "'\''") + "'"

  private fun localIpAddress(): String {
    val fromIfaces = runCatching {
      Collections.list(NetworkInterface.getNetworkInterfaces())
        .flatMap { Collections.list(it.inetAddresses) }
        .firstOrNull { a ->
          val addr = a.hostAddress.orEmpty()
          !a.isLoopbackAddress && !addr.contains(':') && (
            addr.startsWith("192.168.") ||
              addr.startsWith("10.") ||
              Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\.").containsMatchIn(addr)
            )
        }
        ?.hostAddress
        .orEmpty()
    }.getOrDefault("")
    if (fromIfaces.isNotBlank()) return fromIfaces
    return runCatching {
      val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      @Suppress("DEPRECATION") Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
    }.getOrDefault("").takeIf { it != "0.0.0.0" } ?: ""
  }

}
