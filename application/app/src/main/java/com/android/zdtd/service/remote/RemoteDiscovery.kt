package com.android.zdtd.service.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class RemoteDiscovery(private val context: Context) {
  fun discoverUdp(timeoutMs: Long = 1400): Flow<RemoteDeviceInfo> = flow {
    val socket = DatagramSocket().apply { broadcast = true; soTimeout = 350 }
    socket.use { s ->
      val msg = RemoteProtocol.DISCOVER.toByteArray()
      runCatching { s.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), RemoteProtocol.UDP_PORT)) }
      val deadline = System.currentTimeMillis() + timeoutMs
      val buf = ByteArray(2048)
      while (System.currentTimeMillis() < deadline) {
        val p = DatagramPacket(buf, buf.size)
        val got = runCatching { s.receive(p); p }.getOrNull() ?: continue
        val json = runCatching { JSONObject(String(got.data, 0, got.length)) }.getOrNull() ?: continue
        if (json.optString("type") == RemoteProtocol.SERVICE) emit(RemoteProtocol.parseDevice(json, got.address.hostAddress ?: ""))
      }
    }
  }

  fun discoverNsd(): Flow<RemoteDeviceInfo> = callbackFlow {
    val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    val listener = object : NsdManager.DiscoveryListener {
      override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) { close() }
      override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
      override fun onDiscoveryStarted(serviceType: String?) = Unit
      override fun onDiscoveryStopped(serviceType: String?) = Unit
      override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit
      override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
        if (serviceInfo == null) return
        runCatching {
          nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit
            override fun onServiceResolved(resolved: NsdServiceInfo?) {
              val info = resolved ?: return
              val host = info.host?.hostAddress ?: return
              val port = info.port
              trySend(RemoteDeviceInfo(
                deviceId = info.serviceName ?: "$host:$port",
                deviceName = info.serviceName ?: "ZDT-D",
                manufacturer = "",
                model = "",
                host = host,
                port = port,
                appVersionCode = 0,
                online = true,
                lastSeenMs = System.currentTimeMillis(),
              ))
            }
          })
        }
      }
    }
    runCatching { nsd.discoverServices("_zdtd-remote._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
    awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
  }
}
