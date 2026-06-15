package com.android.zdtd.service.api

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ApiModels {

  data class ProcAgg(
    val count: Int = 0,
    val cpuPercent: Double = 0.0,
    val rssMb: Double = 0.0,
  )

  data class OperaAgg(
    val opera: ProcAgg = ProcAgg(),
    val t2s: ProcAgg = ProcAgg(),
    val byedpi: ProcAgg = ProcAgg(),
  )

  data class StatusReport(
    val total: ProcAgg = ProcAgg(),
    val zdtd: ProcAgg = ProcAgg(),
    val zapret: ProcAgg = ProcAgg(),
    val zapret2: ProcAgg = ProcAgg(),
    val byedpi: ProcAgg = ProcAgg(),
    val dnscrypt: ProcAgg = ProcAgg(),
    val dpitunnel: ProcAgg = ProcAgg(),
    val singBox: ProcAgg = ProcAgg(),
    val wireProxy: ProcAgg = ProcAgg(),
    val tor: ProcAgg = ProcAgg(),
    val openVpn: ProcAgg = ProcAgg(),
    val mihomo: ProcAgg = ProcAgg(),
    val mieru: ProcAgg = ProcAgg(),
    val tun2Proxy: ProcAgg = ProcAgg(),
    val amneziaWg: ProcAgg = ProcAgg(),
    val t2s: ProcAgg = ProcAgg(),
    val opera: OperaAgg? = null,
    val runtimeState: String = "unknown",
    val actualRuntimeState: String = "unknown",
    val uiState: String = "unknown",
    val uiRunning: Boolean? = null,
    val startInProgress: Boolean = false,
    val stopInProgress: Boolean = false,
    val servicesPartial: Boolean = false,
    val daemonPid: Int = 0,
    val statusUpdatedAtUnix: Long = 0L,
    val lastError: String = "",
  )

  data class Profile(
    val name: String,
    val enabled: Boolean = false,
  )

  data class Program(
    val id: String,
    val name: String? = null,
    val enabled: Boolean = false,
    val type: String? = null,
    val profiles: List<Profile> = emptyList(),
  )

  data class TrafficReport(
    val ok: Boolean = true,
    val busy: Boolean = false,
    val preparing: Boolean = false,
    val message: String = "",
    val error: String = "",
    val updatedAtUnix: Long = 0L,
    val source: String = "",
    val rules: List<TrafficRuleCounter> = emptyList(),
    val chains: List<TrafficChainSummary> = emptyList(),
    val vpn: List<VpnTraffic> = emptyList(),
    val interfaces: List<InterfaceTraffic> = emptyList(),
    val warnings: List<String> = emptyList(),
    val proxyEndpoints: List<TrafficBackendPort> = emptyList(),
    val t2sInstances: List<TrafficT2sInstance> = emptyList(),
  )

  data class TrafficRuleCounter(
    val family: String = "",
    val table: String = "",
    val chain: String = "",
    val semantic: String = "",
    val target: String = "",
    val programId: String? = null,
    val profile: String? = null,
    val slot: String? = null,
    val uidFile: String? = null,
    val uid: Int? = null,
    val packageName: String? = null,
    val packages: List<String> = emptyList(),
    val proto: String? = null,
    val destPorts: List<String> = emptyList(),
    val redirectPort: Int? = null,
    val queue: Int? = null,
    val backendPorts: List<TrafficBackendPort> = emptyList(),
    val packets: Long = 0L,
    val bytes: Long = 0L,
    val active: Boolean = false,
    val actionCounter: Boolean = false,
    val raw: String = "",
  )

  data class TrafficBackendPort(
    val port: Int = 0,
    val label: String = "",
    val programId: String? = null,
    val profile: String? = null,
    val server: String? = null,
  )

  data class TrafficT2sInstance(
    val instanceId: String = "",
    val program: String = "",
    val profile: String = "",
    val scope: String = "",
    val pid: Int = 0,
    val webAddr: String = "127.0.0.1",
    val webPort: Int = 0,
    val listenAddr: String = "127.0.0.1",
    val listenPort: Int = 0,
    val backendMode: String = "",
    val prioritySpeedAware: Boolean = false,
    val updatedAt: Long = 0L,
  )

  data class TrafficChainSummary(
    val family: String = "",
    val table: String = "",
    val chain: String = "",
    val kind: String = "",
    val ruleCount: Long = 0L,
    val actionPackets: Long = 0L,
    val actionBytes: Long = 0L,
    val returnPackets: Long = 0L,
    val returnBytes: Long = 0L,
    val passPackets: Long = 0L,
    val passBytes: Long = 0L,
  )

  data class InterfaceTraffic(
    val iface: String = "",
    val rxBytes: Long = 0L,
    val rxPackets: Long = 0L,
    val txBytes: Long = 0L,
    val txPackets: Long = 0L,
    val totalBytes: Long = 0L,
  )

  data class VpnTraffic(
    val ownerProgram: String = "",
    val profile: String = "",
    val netid: Int = 0,
    val tun: String = "",
    val rxBytes: Long = 0L,
    val rxPackets: Long = 0L,
    val txBytes: Long = 0L,
    val txPackets: Long = 0L,
    val totalBytes: Long = 0L,
    val uidRanges: List<String> = emptyList(),
    val apps: List<VpnApp> = emptyList(),
  )

  data class VpnApp(
    val uid: Int = 0,
    val packageName: String? = null,
    val packages: List<String> = emptyList(),
  )

  /** Prebuilt strategy variant metadata (optional sha256 for quick matching). */
  data class StrategyVariant(
    val name: String,
    val sha256: String? = null,
  )

  data class DaemonSettings(
    val protectorMode: String = "off",
    val hotspotT2sEnabled: Boolean = false,
    val hotspotMode: String = "proxy",
    val hotspotProgram: String = "",
    val hotspotProfile: String = "",
    val hotspotT2sTarget: String = "",
    val hotspotT2sSingboxProfile: String = "",
    val hotspotT2sWireproxyProfile: String = "",
    val hotspotT2sCaptureAll: Boolean = false,
    val selinuxPermissiveEnabled: Boolean = false,
    val ipForwardEnabled: Boolean = false,
  )

  data class SingBoxProfileChoice(
    val name: String,
    val enabled: Boolean = false,
  )


  data class EnergySaverProgramSetting(
    val freezeOnScreenOff: Boolean = false,
    val cpuAffinityEnabled: Boolean = false,
    val cpuCores: List<Int> = listOf(0, 1, 2),
  )

  data class EnergySaverConfig(
    val enabled: Boolean = false,
    val freezeDelaySeconds: Long = 300L,
    val programs: Map<String, EnergySaverProgramSetting> = emptyMap(),
  )

  data class EnergySaverProgram(
    val id: String,
    val displayName: String,
    val binary: String,
    val binaryPath: String,
    val exists: Boolean = true,
    val allowFreeze: Boolean = true,
    val allowAffinity: Boolean = true,
    val runningPids: List<Int> = emptyList(),
  )

  data class EnergySaverState(
    val exists: Boolean = false,
    val active: Boolean = false,
    val onlineCpuCount: Int = 0,
    val settings: EnergySaverConfig = EnergySaverConfig(),
    val programs: List<EnergySaverProgram> = emptyList(),
  )

  data class ProxyInfoState(
    val enabled: Boolean = false,
    val appsContent: String = "",
    val active: Boolean = false,
  )

  data class AppAssignmentEntry(
    val programId: String,
    val profile: String? = null,
    val slot: String,
    val path: String,
    val packages: Set<String> = emptySet(),
  )

  data class AppAssignmentsState(
    val lists: List<AppAssignmentEntry> = emptyList(),
    val proxyInfoPackages: Set<String> = emptySet(),
  )

  data class ConstructionProxyEndpointCandidate(
    val key: String = "",
    val programId: String = "",
    val profile: String? = null,
    val server: String? = null,
    val slot: String = "common",
    val host: String = "127.0.0.1",
    val port: Int = 0,
    val label: String = "",
    val kind: String = "socks5",
    val enabled: Boolean = false,
    val running: Boolean = false,
    val appListPath: String? = null,
    val appListEmpty: Boolean = false,
    val hasTrigger: Boolean = false,
    val canStart: Boolean = false,
  )

  data class ConstructionStartEndpointResult(
    val ok: Boolean = false,
    val started: Boolean = false,
    val triggerAdded: Boolean = false,
    val endpoint: ConstructionProxyEndpointCandidate? = null,
    val error: String = "",
  )


  private fun jsonBool(obj: JSONObject?, key: String, default: Boolean = false): Boolean {
    if (obj == null || !obj.has(key)) return default
    return when (val raw = obj.opt(key)) {
      is Boolean -> raw
      is Number -> raw.toInt() != 0
      is String -> when (raw.trim().lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off", "" -> false
        else -> default
      }
      else -> default
    }
  }

  private fun jsonStringList(obj: JSONObject?, key: String): List<String> {
    val arr = obj?.optJSONArray(key) ?: return emptyList()
    val out = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
      val value = arr.optString(i, "").trim()
      if (value.isNotEmpty()) out += value
    }
    return out
  }


  fun parseProcAgg(o: JSONObject?): ProcAgg {
    if (o == null) return ProcAgg()
    return ProcAgg(
      count = o.optInt("count", 0),
      cpuPercent = o.optDouble("cpu_percent", 0.0),
      rssMb = o.optDouble("rss_mb", 0.0),
    )
  }

  fun parseStatusReport(o: JSONObject?): StatusReport? {
    if (o == null) return null
    val operaObj = o.optJSONObject("opera")
    val opera = if (operaObj != null) {
      OperaAgg(
        opera = parseProcAgg(operaObj.optJSONObject("opera")),
        t2s = parseProcAgg(operaObj.optJSONObject("t2s")),
        byedpi = parseProcAgg(operaObj.optJSONObject("byedpi")),
      )
    } else {
      null
    }
    val ui = o.optJSONObject("ui_status") ?: o
    val uiState = normalizeRuntimeState(ui.optString("state", ""))
    val apiRuntimeState = normalizeRuntimeState(o.optString("runtime_state", ""))
    val apiActualRuntimeState = normalizeRuntimeState(o.optString("actual_runtime_state", ""))
    return StatusReport(
      total = parseProcAgg(o.optJSONObject("total")),
      zdtd = parseProcAgg(o.optJSONObject("zdtd")),
      zapret = parseProcAgg(o.optJSONObject("zapret")),
      zapret2 = parseProcAgg(o.optJSONObject("zapret2")),
      byedpi = parseProcAgg(o.optJSONObject("byedpi")),
      dnscrypt = parseProcAgg(o.optJSONObject("dnscrypt")),
      dpitunnel = parseProcAgg(o.optJSONObject("dpitunnel")),
      singBox = parseProcAgg(o.optJSONObject("sing_box")),
      wireProxy = parseProcAgg(o.optJSONObject("wireproxy")),
      tor = parseProcAgg(o.optJSONObject("tor")),
      openVpn = parseProcAgg(o.optJSONObject("openvpn")),
      mihomo = parseProcAgg(o.optJSONObject("mihomo")),
      mieru = parseProcAgg(o.optJSONObject("mieru")),
      tun2Proxy = parseProcAgg(o.optJSONObject("tun2proxy")),
      amneziaWg = parseProcAgg(o.optJSONObject("amneziawg")),
      t2s = parseProcAgg(o.optJSONObject("t2s")),
      opera = opera,
      runtimeState = apiRuntimeState,
      actualRuntimeState = apiActualRuntimeState,
      uiState = uiState,
      uiRunning = if (ui.has("running")) jsonBool(ui, "running", false) else null,
      startInProgress = jsonBool(ui, "start_in_progress", jsonBool(o, "start_in_progress", false)),
      stopInProgress = jsonBool(ui, "stop_in_progress", jsonBool(o, "stop_in_progress", false)),
      servicesPartial = jsonBool(ui, "services_partial", jsonBool(ui, "partial", jsonBool(o, "services_partial", false))),
      daemonPid = ui.optInt("daemon_pid", 0),
      statusUpdatedAtUnix = ui.optLong("updated_at_unix", 0L),
      lastError = ui.optString("last_error", ""),
    )
  }

  fun parseStatusFile(raw: String?): JSONObject? {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return null
    return runCatching { JSONObject(text) }.getOrNull()
  }

  fun applyStatusFile(report: StatusReport?, fileObj: JSONObject?): StatusReport? {
    if (fileObj == null) return report
    val base = report ?: StatusReport()
    val state = normalizeRuntimeState(fileObj.optString("state", ""))
    return base.copy(
      uiState = state,
      uiRunning = if (fileObj.has("running")) jsonBool(fileObj, "running", false) else base.uiRunning,
      startInProgress = jsonBool(fileObj, "start_in_progress", base.startInProgress),
      stopInProgress = jsonBool(fileObj, "stop_in_progress", base.stopInProgress),
      servicesPartial = jsonBool(fileObj, "services_partial", jsonBool(fileObj, "partial", base.servicesPartial)),
      daemonPid = fileObj.optInt("daemon_pid", base.daemonPid),
      statusUpdatedAtUnix = fileObj.optLong("updated_at_unix", base.statusUpdatedAtUnix),
      lastError = fileObj.optString("last_error", base.lastError),
    )
  }

  fun isServiceOn(r: StatusReport?): Boolean {
    if (r == null) return false
    r.uiRunning?.let { return it || r.startInProgress || r.stopInProgress }
    when (r.uiState) {
      "on", "partial", "starting", "stopping", "busy" -> return true
      "off", "error" -> return false
    }
    when (r.actualRuntimeState) {
      "on", "partial", "starting", "stopping", "busy" -> return true
      "off", "error" -> return false
    }
    when (r.runtimeState) {
      "on", "partial", "starting", "stopping", "busy" -> return true
      "off", "error" -> return false
    }
    val opera = r.opera
    val sum = r.zapret.count + r.zapret2.count + r.byedpi.count + r.dnscrypt.count + r.dpitunnel.count + r.singBox.count + r.wireProxy.count + r.tor.count + r.openVpn.count + r.mihomo.count + r.mieru.count + r.tun2Proxy.count + r.amneziaWg.count +
      (opera?.opera?.count ?: 0) + r.t2s.count + (opera?.byedpi?.count ?: 0)
    return sum > 0
  }

  fun isServiceStopped(r: StatusReport?): Boolean = !isServiceOn(r)

  private fun normalizeRuntimeState(raw: String): String {
    return when (raw.trim().lowercase(Locale.ROOT)) {
      "on", "running", "run", "started" -> "on"
      "partial", "partially_running" -> "partial"
      "starting", "start", "busy_start" -> "starting"
      "stopping", "stop", "busy_stop" -> "stopping"
      "busy" -> "busy"
      "off", "stopped", "down", "disabled" -> "off"
      "error", "failed", "fail" -> "error"
      else -> "unknown"
    }
  }

  fun computeTotals(r: StatusReport?): ProcAgg {
    if (r == null) return ProcAgg()
    // Newer daemon versions provide a unique-process total calculated in Rust.
    // Prefer it to avoid double-counting helpers exposed in multiple buckets,
    // e.g. t2s as both myproxy/t2s and the global t2s bucket.
    if (r.total.count > 0 || r.total.cpuPercent > 0.0 || r.total.rssMb > 0.0) {
      return r.total
    }
    val parts = buildList {
      add(r.zdtd)
      add(r.zapret)
      add(r.zapret2)
      add(r.byedpi)
      add(r.dnscrypt)
      add(r.dpitunnel)
      add(r.singBox)
      add(r.wireProxy)
      add(r.tor)
      add(r.openVpn)
      add(r.mihomo)
      add(r.mieru)
      add(r.tun2Proxy)
      add(r.amneziaWg)
      add(r.t2s)
      r.opera?.let { o ->
        add(o.opera)
        add(o.byedpi)
      }
    }
    var cpu = 0.0
    var ram = 0.0
    for (p in parts) {
      cpu += p.cpuPercent
      ram += p.rssMb
    }
    return ProcAgg(count = 0, cpuPercent = cpu, rssMb = ram)
  }

  fun parseDaemonSettings(wrapper: JSONObject?): DaemonSettings {
    val setting = wrapper?.optJSONObject("setting")
    val mode = setting?.optString("protector_mode", "off")
      ?.trim()
      ?.lowercase(Locale.ROOT)
      .takeUnless { it.isNullOrBlank() }
      ?: "off"
    val safeMode = when (mode) {
      "on", "off", "auto" -> mode
      else -> "off"
    }
    val hotspotEnabled = setting?.optBoolean("hotspot_t2s_enabled", false) ?: false
    val rawMode = setting?.optString("hotspot_mode", "proxy")
      ?.trim()
      ?.lowercase(Locale.ROOT)
      .orEmpty()
    val hotspotMode = if (rawMode == "vpn") "vpn" else "proxy"
    val rawProgram = setting?.optString("hotspot_program", "")
      ?.trim()
      ?.lowercase(Locale.ROOT)
      .orEmpty()
    val hotspotProgram = when (rawProgram) {
      "operaproxy", "opera-proxy", "opera_proxy" -> "operaproxy"
      "singbox", "sing-box", "sing_box" -> "singbox"
      "wireproxy", "wire-proxy", "wire_proxy" -> "wireproxy"
      "openvpn", "open-vpn", "open_vpn" -> "openvpn"
      "amneziawg", "amnezia-wg", "amnezia_wg", "awg" -> "amneziawg"
      "mihomo" -> "mihomo"
      "mieru" -> "mieru"
      else -> ""
    }
    val hotspotProfile = setting?.optString("hotspot_profile", "")
      ?.trim()
      .orEmpty()
    val rawTarget = setting?.optString("hotspot_t2s_target", "")
      ?.trim()
      ?.lowercase(Locale.ROOT)
      .orEmpty()
    val safeTarget = when (rawTarget) {
      "operaproxy", "opera-proxy", "opera_proxy" -> "operaproxy"
      "singbox", "sing-box", "sing_box" -> "singbox"
      "wireproxy", "wire-proxy", "wire_proxy" -> "wireproxy"
      else -> ""
    }
    val hotspotSingboxProfile = setting?.optString("hotspot_t2s_singbox_profile", "")
      ?.trim()
      .orEmpty()
    val hotspotWireproxyProfile = setting?.optString("hotspot_t2s_wireproxy_profile", "")
      ?.trim()
      .orEmpty()
    val hotspotCaptureAll = setting?.optBoolean("hotspot_t2s_capture_all", false) ?: false
    return DaemonSettings(
      protectorMode = safeMode,
      hotspotT2sEnabled = hotspotEnabled,
      hotspotMode = hotspotMode,
      hotspotProgram = hotspotProgram,
      hotspotProfile = hotspotProfile,
      hotspotT2sTarget = safeTarget,
      hotspotT2sSingboxProfile = hotspotSingboxProfile,
      hotspotT2sWireproxyProfile = hotspotWireproxyProfile,
      hotspotT2sCaptureAll = hotspotCaptureAll,
      selinuxPermissiveEnabled = setting?.optBoolean("selinux_permissive_enabled", false) ?: false,
      ipForwardEnabled = setting?.optBoolean("ip_forward_enabled", false) ?: false,
    )
  }


  fun parseEnergySaver(wrapper: JSONObject?): EnergySaverState {
    if (wrapper == null) return EnergySaverState()
    val settingsObj = wrapper.optJSONObject("settings") ?: JSONObject()
    val programsObj = settingsObj.optJSONObject("programs") ?: JSONObject()
    val programSettings = linkedMapOf<String, EnergySaverProgramSetting>()
    val keys = programsObj.keys()
    while (keys.hasNext()) {
      val id = keys.next()
      val o = programsObj.optJSONObject(id) ?: continue
      val coresArr = o.optJSONArray("cpu_cores") ?: JSONArray()
      val cores = buildList {
        for (i in 0 until coresArr.length()) {
          val core = coresArr.optInt(i, -1)
          if (core >= 0 && !contains(core)) add(core)
        }
      }.ifEmpty { listOf(0, 1, 2) }
      programSettings[id] = EnergySaverProgramSetting(
        freezeOnScreenOff = jsonBool(o, "freeze_on_screen_off", false),
        cpuAffinityEnabled = jsonBool(o, "cpu_affinity_enabled", false),
        cpuCores = cores,
      )
    }
    val programsArr = wrapper.optJSONArray("programs") ?: JSONArray()
    val programs = buildList {
      for (i in 0 until programsArr.length()) {
        val o = programsArr.optJSONObject(i) ?: continue
        val id = o.optString("id", "").trim()
        if (id.isBlank()) continue
        val pidsArr = o.optJSONArray("running_pids") ?: JSONArray()
        val pids = buildList {
          for (j in 0 until pidsArr.length()) {
            val pid = pidsArr.optInt(j, 0)
            if (pid > 0) add(pid)
          }
        }
        add(
          EnergySaverProgram(
            id = id,
            displayName = o.optString("display_name", id),
            binary = o.optString("binary", id),
            binaryPath = o.optString("binary_path", ""),
            exists = jsonBool(o, "exists", true),
            allowFreeze = jsonBool(o, "allow_freeze", true),
            allowAffinity = jsonBool(o, "allow_affinity", true),
            runningPids = pids,
          )
        )
      }
    }
    return EnergySaverState(
      exists = jsonBool(wrapper, "exists", false),
      active = jsonBool(wrapper, "active", false),
      onlineCpuCount = wrapper.optInt("online_cpu_count", 0),
      settings = EnergySaverConfig(
        enabled = jsonBool(settingsObj, "enabled", false),
        freezeDelaySeconds = settingsObj.optLong("freeze_delay_seconds", 300L).coerceAtLeast(10L),
        programs = programSettings,
      ),
      programs = programs,
    )
  }

  fun energySaverConfigToJson(config: EnergySaverConfig): JSONObject {
    val programs = JSONObject()
    config.programs.forEach { (id, setting) ->
      val cores = JSONArray()
      setting.cpuCores.distinct().sorted().forEach { cores.put(it) }
      programs.put(
        id,
        JSONObject()
          .put("freeze_on_screen_off", setting.freezeOnScreenOff)
          .put("cpu_affinity_enabled", setting.cpuAffinityEnabled)
          .put("cpu_cores", cores),
      )
    }
    return JSONObject()
      .put("enabled", config.enabled)
      .put("freeze_delay_seconds", config.freezeDelaySeconds)
      .put("programs", programs)
  }

  fun parseSingBoxProfiles(wrapper: JSONObject?): List<SingBoxProfileChoice> {
    if (wrapper == null) return emptyList()
    val arr = wrapper.optJSONArray("profiles") ?: JSONArray()
    val out = ArrayList<SingBoxProfileChoice>(arr.length())
    for (i in 0 until arr.length()) {
      val item = arr.optJSONObject(i) ?: continue
      val name = item.optString("name", "").trim()
      if (name.isEmpty()) continue
      out += SingBoxProfileChoice(
        name = name,
        enabled = item.optBoolean("enabled", false),
      )
    }
    out.sortBy { it.name.lowercase(Locale.ROOT) }
    return out
  }

  fun parseProxyInfo(wrapper: JSONObject?): ProxyInfoState {
    if (wrapper == null) return ProxyInfoState()
    return ProxyInfoState(
      enabled = jsonBool(wrapper, "enabled", false),
      appsContent = wrapper.optString("apps", ""),
      active = wrapper.optBoolean("active", false),
    )
  }


  fun parseAppAssignments(wrapper: JSONObject?): AppAssignmentsState {
    if (wrapper == null) return AppAssignmentsState()
    val listsArr = wrapper.optJSONArray("lists")
    val lists = buildList {
      if (listsArr != null) {
        for (i in 0 until listsArr.length()) {
          val o = listsArr.optJSONObject(i) ?: continue
          val programId = o.optString("program_id", "").trim()
          val slot = o.optString("slot", "").trim().lowercase(Locale.ROOT)
          val path = o.optString("path", "").trim()
          if (programId.isEmpty() || slot.isEmpty() || path.isEmpty()) continue
          val pkgs = linkedSetOf<String>()
          val arr = o.optJSONArray("packages")
          if (arr != null) {
            for (j in 0 until arr.length()) {
              val pkg = arr.optString(j, "").trim()
              if (pkg.isNotEmpty()) pkgs.add(pkg)
            }
          }
          add(
            AppAssignmentEntry(
              programId = programId,
              profile = o.optString("profile", "").trim().takeIf { it.isNotEmpty() },
              slot = slot,
              path = path,
              packages = pkgs,
            )
          )
        }
      }
    }
    val proxyPkgs = linkedSetOf<String>()
    val proxyArr = wrapper.optJSONArray("proxyinfo_packages")
    if (proxyArr != null) {
      for (i in 0 until proxyArr.length()) {
        val pkg = proxyArr.optString(i, "").trim()
        if (pkg.isNotEmpty()) proxyPkgs.add(pkg)
      }
    }
    return AppAssignmentsState(lists = lists, proxyInfoPackages = proxyPkgs)
  }

  fun parseTrafficReport(wrapper: JSONObject?): TrafficReport {
    if (wrapper == null) return TrafficReport(ok = false, error = "empty response")
    val wrapperOk = wrapper.optBoolean("ok", true)
    val wrapperBusy = jsonBool(wrapper, "busy", false)
    val wrapperPreparing = jsonBool(wrapper, "preparing", false)
    val wrapperMessage = wrapper.optString("message", "")
    val wrapperError = wrapper.optString("error", "")
    if (wrapperBusy || wrapperPreparing) {
      return TrafficReport(ok = wrapperOk, busy = true, preparing = true, message = wrapperMessage.ifBlank { "Traffic snapshot is still preparing. Please wait." }, error = wrapperError)
    }
    val data = wrapper.optJSONObject("traffic") ?: wrapper

    val rulesArr = data.optJSONArray("rules") ?: JSONArray()
    val rules = ArrayList<TrafficRuleCounter>(rulesArr.length())
    for (i in 0 until rulesArr.length()) {
      val o = rulesArr.optJSONObject(i) ?: continue
      rules += TrafficRuleCounter(
        family = o.optString("family", ""),
        table = o.optString("table", ""),
        chain = o.optString("chain", ""),
        semantic = o.optString("semantic", ""),
        target = o.optString("target", ""),
        programId = o.optString("program_id", "").trim().takeIf { it.isNotEmpty() },
        profile = o.optString("profile", "").trim().takeIf { it.isNotEmpty() },
        slot = o.optString("slot", "").trim().takeIf { it.isNotEmpty() },
        uidFile = o.optString("uid_file", "").trim().takeIf { it.isNotEmpty() },
        uid = if (o.has("uid") && !o.isNull("uid")) o.optInt("uid") else null,
        packageName = o.optString("package", "").trim().takeIf { it.isNotEmpty() },
        packages = jsonStringList(o, "packages"),
        proto = o.optString("proto", "").trim().takeIf { it.isNotEmpty() },
        destPorts = jsonStringList(o, "dest_ports"),
        redirectPort = if (o.has("redirect_port") && !o.isNull("redirect_port")) o.optInt("redirect_port") else null,
        queue = if (o.has("queue") && !o.isNull("queue")) o.optInt("queue") else null,
        backendPorts = parseTrafficBackendPorts(o.optJSONArray("backend_ports")),
        packets = o.optLong("packets", 0L),
        bytes = o.optLong("bytes", 0L),
        active = o.optBoolean("active", false),
        actionCounter = o.optBoolean("action_counter", false),
        raw = o.optString("raw_rule", o.optString("raw", "")),
      )
    }

    val chainsArr = data.optJSONArray("chains") ?: JSONArray()
    val chains = ArrayList<TrafficChainSummary>(chainsArr.length())
    for (i in 0 until chainsArr.length()) {
      val o = chainsArr.optJSONObject(i) ?: continue
      chains += TrafficChainSummary(
        family = o.optString("family", ""),
        table = o.optString("table", ""),
        chain = o.optString("chain", ""),
        kind = o.optString("kind", ""),
        ruleCount = o.optLong("rule_count", 0L),
        actionPackets = o.optLong("action_packets", 0L),
        actionBytes = o.optLong("action_bytes", 0L),
        returnPackets = o.optLong("return_packets", 0L),
        returnBytes = o.optLong("return_bytes", 0L),
        passPackets = o.optLong("pass_packets", 0L),
        passBytes = o.optLong("pass_bytes", 0L),
      )
    }

    val ifacesArr = data.optJSONArray("interfaces") ?: JSONArray()
    val interfaces = ArrayList<InterfaceTraffic>(ifacesArr.length())
    for (i in 0 until ifacesArr.length()) {
      val o = ifacesArr.optJSONObject(i) ?: continue
      interfaces += InterfaceTraffic(
        iface = o.optString("iface", ""),
        rxBytes = o.optLong("rx_bytes", 0L),
        rxPackets = o.optLong("rx_packets", 0L),
        txBytes = o.optLong("tx_bytes", 0L),
        txPackets = o.optLong("tx_packets", 0L),
        totalBytes = o.optLong("total_bytes", 0L),
      )
    }

    val vpnArr = data.optJSONArray("vpn") ?: JSONArray()
    val vpn = ArrayList<VpnTraffic>(vpnArr.length())
    for (i in 0 until vpnArr.length()) {
      val o = vpnArr.optJSONObject(i) ?: continue
      val appsArr = o.optJSONArray("apps") ?: JSONArray()
      val apps = ArrayList<VpnApp>(appsArr.length())
      for (j in 0 until appsArr.length()) {
        val app = appsArr.optJSONObject(j) ?: continue
        apps += VpnApp(
          uid = app.optInt("uid", 0),
          packageName = app.optString("package", "").trim().takeIf { it.isNotEmpty() },
          packages = jsonStringList(app, "packages"),
        )
      }
      vpn += VpnTraffic(
        ownerProgram = o.optString("owner_program", ""),
        profile = o.optString("profile", ""),
        netid = o.optInt("netid", 0),
        tun = o.optString("tun", ""),
        rxBytes = o.optLong("rx_bytes", 0L),
        rxPackets = o.optLong("rx_packets", 0L),
        txBytes = o.optLong("tx_bytes", 0L),
        txPackets = o.optLong("tx_packets", 0L),
        totalBytes = o.optLong("total_bytes", 0L),
        uidRanges = jsonStringList(o, "uid_ranges"),
        apps = apps,
      )
    }

    return TrafficReport(
      ok = wrapperOk,
      busy = wrapperBusy,
      preparing = wrapperPreparing,
      message = wrapperMessage,
      error = wrapperError,
      updatedAtUnix = data.optLong("updated_at_unix", 0L),
      source = data.optString("source", ""),
      rules = rules,
      chains = chains,
      vpn = vpn,
      interfaces = interfaces,
      warnings = jsonStringList(data, "warnings"),
      proxyEndpoints = parseTrafficBackendPorts(data.optJSONArray("proxy_endpoints")),
      t2sInstances = parseTrafficT2sInstances(data.optJSONArray("t2s_instances")),
    )
  }


  private fun parseTrafficT2sInstances(arr: JSONArray?): List<TrafficT2sInstance> {
    if (arr == null) return emptyList()
    val out = ArrayList<TrafficT2sInstance>(arr.length())
    for (i in 0 until arr.length()) {
      val o = arr.optJSONObject(i) ?: continue
      out += TrafficT2sInstance(
        instanceId = o.optString("instance_id", ""),
        program = o.optString("program", ""),
        profile = o.optString("profile", ""),
        scope = o.optString("scope", ""),
        pid = o.optInt("pid", 0),
        webAddr = o.optString("web_addr", "127.0.0.1"),
        webPort = o.optInt("web_port", 0),
        listenAddr = o.optString("listen_addr", "127.0.0.1"),
        listenPort = o.optInt("listen_port", 0),
        backendMode = o.optString("backend_mode", ""),
        prioritySpeedAware = o.optBoolean("priority_speed_aware", false),
        updatedAt = o.optLong("updated_at", 0L),
      )
    }
    return out.filter { it.webPort > 0 && it.listenPort > 0 }
  }


  private fun parseTrafficBackendPorts(arr: JSONArray?): List<TrafficBackendPort> {
    if (arr == null) return emptyList()
    val out = ArrayList<TrafficBackendPort>(arr.length())
    for (i in 0 until arr.length()) {
      val o = arr.optJSONObject(i) ?: continue
      out += TrafficBackendPort(
        port = o.optInt("port", 0),
        label = o.optString("label", ""),
        programId = o.optString("program_id", "").trim().takeIf { it.isNotEmpty() },
        profile = o.optString("profile", "").trim().takeIf { it.isNotEmpty() },
        server = o.optString("server", "").trim().takeIf { it.isNotEmpty() },
      )
    }
    return out
  }

  fun parseConstructionProxyEndpoints(wrapper: JSONObject?): List<ConstructionProxyEndpointCandidate> {
    if (wrapper == null || !wrapper.optBoolean("ok", false)) return emptyList()
    val arr = wrapper.optJSONArray("candidates") ?: return emptyList()
    val out = ArrayList<ConstructionProxyEndpointCandidate>(arr.length())
    for (i in 0 until arr.length()) {
      val o = arr.optJSONObject(i) ?: continue
      val port = o.optInt("port", 0)
      val programId = o.optString("program_id", "").trim()
      if (port <= 0 || programId.isEmpty()) continue
      out += ConstructionProxyEndpointCandidate(
        key = o.optString("key", ""),
        programId = programId,
        profile = o.optString("profile", "").trim().takeIf { it.isNotEmpty() },
        server = o.optString("server", "").trim().takeIf { it.isNotEmpty() },
        slot = o.optString("slot", "common").ifBlank { "common" },
        host = o.optString("host", "127.0.0.1").ifBlank { "127.0.0.1" },
        port = port,
        label = o.optString("label", ""),
        kind = o.optString("kind", "socks5").ifBlank { "socks5" },
        enabled = jsonBool(o, "enabled", false),
        running = jsonBool(o, "running", false),
        appListPath = o.optString("app_list_path", "").trim().takeIf { it.isNotEmpty() },
        appListEmpty = jsonBool(o, "app_list_empty", false),
        hasTrigger = jsonBool(o, "has_trigger", false),
        canStart = jsonBool(o, "can_start", false),
      )
    }
    return out
  }

  fun parseConstructionStartEndpointResult(wrapper: JSONObject?): ConstructionStartEndpointResult {
    if (wrapper == null) return ConstructionStartEndpointResult(error = "empty response")
    val endpoint = wrapper.optJSONObject("endpoint")?.let { obj ->
      parseConstructionProxyEndpoints(JSONObject().put("ok", true).put("candidates", JSONArray().put(obj))).firstOrNull()
    }
    return ConstructionStartEndpointResult(
      ok = jsonBool(wrapper, "ok", false),
      started = jsonBool(wrapper, "started", false),
      triggerAdded = jsonBool(wrapper, "trigger_added", false),
      endpoint = endpoint,
      error = wrapper.optString("error", ""),
    )
  }

  fun parsePrograms(wrapper: JSONObject?): List<Program> {
    if (wrapper == null) return emptyList()
    if (!wrapper.optBoolean("ok", false)) return emptyList()
    val arr = wrapper.optJSONArray("data") ?: return emptyList()
    val out = ArrayList<Program>(arr.length())
    for (i in 0 until arr.length()) {
      val o = arr.optJSONObject(i) ?: continue
      val id = o.optString("id", "").trim()
      if (id.isEmpty()) continue
      val profilesArr = o.optJSONArray("profiles")
      val profiles = if (profilesArr != null) parseProfiles(profilesArr) else emptyList()
      val rawName = o.optString("name").takeIf { it.isNotBlank() }
      val displayName = when (id) {
        "dnscrypt" -> rawName?.takeUnless { it.equals("dnscrypt", ignoreCase = true) } ?: "DNSCrypt"
        "dpitunnel" -> rawName?.takeUnless { it.equals("dpitunnel", ignoreCase = true) } ?: "DPITunnel"
        "openvpn" -> rawName?.takeUnless { it.equals("openvpn", ignoreCase = true) } ?: "OpenVPN"
        "nfqws" -> rawName?.takeUnless { it.equals("nfqws", ignoreCase = true) || it.equals("zapret", ignoreCase = true) } ?: "Zapret"
        "nfqws2" -> rawName?.takeUnless { it.equals("nfqws2", ignoreCase = true) || it.equals("zapret2", ignoreCase = true) || it.equals("zapret 2", ignoreCase = true) } ?: "Zapret 2"
        "byedpi" -> rawName?.takeUnless { it.equals("byedpi", ignoreCase = true) } ?: "ByeDPI"
        "wireproxy" -> rawName?.takeUnless { it.equals("wireproxy", ignoreCase = true) } ?: "WireProxy"
        "tor" -> rawName?.takeUnless { it.equals("tor", ignoreCase = true) } ?: "Tor"
        "myproxy" -> rawName ?: "myproxy"
        "myprogram" -> rawName ?: "myprogram"
        "tun2socks" -> rawName ?: "tun2socks"
        "myvpn" -> rawName ?: "myvpn"
        "mihomo" -> rawName?.takeUnless { it.equals("mihomo", ignoreCase = true) } ?: "Mihomo"
        "mieru" -> rawName?.takeUnless { it.equals("mieru", ignoreCase = true) } ?: "mieru"
        "amneziawg" -> rawName?.takeUnless { it.equals("amneziawg", ignoreCase = true) } ?: "AmneziaWG"
        else -> rawName
      }
      out.add(
        Program(
          id = id,
          name = displayName,
          enabled = jsonBool(o, "enabled", false),
          type = o.optString("type").takeIf { it.isNotBlank() },
          profiles = profiles,
        )
      )
    }
    return out
  }

  private fun parseProfiles(arr: JSONArray): List<Profile> {
    val out = ArrayList<Profile>(arr.length())
    for (i in 0 until arr.length()) {
      val o = arr.optJSONObject(i) ?: continue
      val name = o.optString("name", "").trim()
      if (name.isEmpty()) continue
      out.add(Profile(name = name, enabled = jsonBool(o, "enabled", false)))
    }
    return out.sortedBy { it.name.lowercase(Locale.ROOT) }
  }

  // SimpleDateFormat is NOT thread-safe; logs are updated from multiple coroutines.
  private val TS = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.US) }
  fun fmtTs(now: Long = System.currentTimeMillis()): String = TS.get().format(Date(now))
}
