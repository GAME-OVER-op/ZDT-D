package com.android.zdtd.service.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class RemoteDiscovery(private val context: Context) {
  fun discoverUdp(timeoutMs: Long = 4_200): Flow<RemoteDeviceInfo> = flow {
    val socket = runCatching {
      DatagramSocket().apply {
        broadcast = true
        soTimeout = 450
      }
    }.getOrNull() ?: return@flow

    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val lock = runCatching {
      wifi?.createMulticastLock("zdtd_remote_discovery")?.apply {
        setReferenceCounted(false)
        acquire()
      }
    }.getOrNull()

    socket.use { s ->
      try {
        val msg = RemoteProtocol.DISCOVER.toByteArray(Charsets.UTF_8)
        val targets = buildList {
          add(InetAddress.getByName("255.255.255.255"))
          subnetBroadcastAddress()?.let { add(it) }
        }.distinctBy { it.hostAddress }

        fun sendProbe() {
          targets.forEach { addr ->
            runCatching { s.send(DatagramPacket(msg, msg.size, addr, RemoteProtocol.UDP_PORT)) }
          }
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        var nextSendAt = 0L
        val buf = ByteArray(2048)
        while (System.currentTimeMillis() < deadline) {
          val now = System.currentTimeMillis()
          if (now >= nextSendAt) {
            sendProbe()
            nextSendAt = now + 650L
          }

          val p = DatagramPacket(buf, buf.size)
          val got = runCatching { s.receive(p); p }.getOrNull() ?: continue
          val json = runCatching { JSONObject(String(got.data, 0, got.length, Charsets.UTF_8)) }.getOrNull() ?: continue
          if (json.optString("type") == RemoteProtocol.SERVICE) {
            emit(RemoteProtocol.parseDevice(json, got.address.hostAddress ?: ""))
          }
        }
      } finally {
        runCatching { lock?.release() }
      }
    }
  }

  fun discoverNsd(): Flow<RemoteDeviceInfo> = callbackFlow {
    val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    if (nsd == null) {
      close()
      return@callbackFlow
    }

    // Resolve every discovered service, not just the first one. NsdManager only
    // allows a single active resolveService() call at a time on most Android
    // versions, so we serialize resolves through a queue and retry when the
    // platform reports FAILURE_ALREADY_ACTIVE.
    val lock = Any()
    val pending = ArrayDeque<NsdServiceInfo>()
    val queued = HashSet<String>()
    val attempts = HashMap<String, Int>()
    var resolving = false

    fun keyOf(si: NsdServiceInfo): String = si.serviceName ?: "anon-${System.identityHashCode(si)}"

    fun resolveNext() {
      val next: NsdServiceInfo
      synchronized(lock) {
        if (resolving) return
        next = pending.removeFirstOrNull() ?: return
        resolving = true
      }
      val advance: () -> Unit = {
        synchronized(lock) { resolving = false }
        resolveNext()
      }
      val started = runCatching {
        nsd.resolveService(next, object : NsdManager.ResolveListener {
          override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
            val k = keyOf(next)
            if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
              val n = synchronized(lock) {
                val v = (attempts[k] ?: 0) + 1
                attempts[k] = v
                if (v <= 5) pending.addLast(next) else queued.remove(k)
                v
              }
              if (n > 5) { /* give up on this one */ }
            } else {
              synchronized(lock) { queued.remove(k); attempts.remove(k) }
            }
            advance()
          }

          override fun onServiceResolved(resolved: NsdServiceInfo?) {
            val info = resolved
            val host = info?.host?.hostAddress
            if (info != null && host != null) {
              val attrs = info.attributes.orEmpty().mapValues { (_, v) -> if (v != null) String(v, Charsets.UTF_8) else "" }
              trySend(RemoteDeviceInfo(
                deviceId = attrs["deviceId"].orEmpty().ifBlank { info.serviceName ?: "$host:${info.port}" },
                deviceName = attrs["deviceName"].orEmpty().ifBlank { info.serviceName ?: "ZDT-D" },
                manufacturer = attrs["manufacturer"].orEmpty(),
                model = attrs["model"].orEmpty(),
                host = host,
                port = info.port,
                appVersionCode = attrs["appVersionCode"]?.toIntOrNull() ?: 0,
                protocolVersion = attrs["protocolVersion"]?.toIntOrNull() ?: RemoteProtocol.PROTOCOL_VERSION,
                online = true,
                lastSeenMs = System.currentTimeMillis(),
              ))
            }
            advance()
          }
        })
      }.isSuccess
      if (!started) advance()
    }

    val listener = object : NsdManager.DiscoveryListener {
      override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) { close() }
      override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
      override fun onDiscoveryStarted(serviceType: String?) = Unit
      override fun onDiscoveryStopped(serviceType: String?) = Unit
      override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
        if (serviceInfo == null) return
        val k = keyOf(serviceInfo)
        synchronized(lock) { queued.remove(k); attempts.remove(k) }
      }
      override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
        if (serviceInfo == null) return
        val k = keyOf(serviceInfo)
        val enqueue = synchronized(lock) {
          if (!queued.add(k)) false else { pending.addLast(serviceInfo); true }
        }
        if (enqueue) resolveNext()
      }
    }
    runCatching { nsd.discoverServices("_zdtd-remote._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
      .onFailure { close(it) }
    awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
  }

  private fun subnetBroadcastAddress(): InetAddress? {
    return runCatching {
      val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
      val d = wm.dhcpInfo ?: return null
      if (d.ipAddress == 0 || d.netmask == 0) return null
      val broadcast = (d.ipAddress and d.netmask) or d.netmask.inv()
      val bytes = ByteArray(4) { k -> ((broadcast shr (k * 8)) and 0xff).toByte() }
      InetAddress.getByAddress(bytes)
    }.getOrNull()
  }
}
