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
    val zdtd: ProcAgg = ProcAgg(),
    val zapret: ProcAgg = ProcAgg(),
    val zapret2: ProcAgg = ProcAgg(),
    val byedpi: ProcAgg = ProcAgg(),
    val dnscrypt: ProcAgg = ProcAgg(),
    val dpitunnel: ProcAgg = ProcAgg(),
    val singBox: ProcAgg = ProcAgg(),
    val t2s: ProcAgg = ProcAgg(),
    val opera: OperaAgg? = null,
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

  /** Prebuilt strategy variant metadata (optional sha256 for quick matching). */
  data class StrategyVariant(
    val name: String,
    val sha256: String? = null,
  )

  data class DaemonSettings(
    val protectorMode: String = "off",
    val hotspotT2sEnabled: Boolean = false,
    val hotspotT2sTarget: String = "",
    val hotspotT2sSingboxProfile: String = "",
  )

  data class SingBoxProfileChoice(
    val name: String,
    val enabled: Boolean = false,
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
    return StatusReport(
      zdtd = parseProcAgg(o.optJSONObject("zdtd")),
      zapret = parseProcAgg(o.optJSONObject("zapret")),
      zapret2 = parseProcAgg(o.optJSONObject("zapret2")),
      byedpi = parseProcAgg(o.optJSONObject("byedpi")),
      dnscrypt = parseProcAgg(o.optJSONObject("dnscrypt")),
      dpitunnel = parseProcAgg(o.optJSONObject("dpitunnel")),
      singBox = parseProcAgg(o.optJSONObject("sing_box")),
      t2s = parseProcAgg(o.optJSONObject("t2s")),
      opera = opera,
    )
  }

  fun isServiceOn(r: StatusReport?): Boolean {
    if (r == null) return false
    val opera = r.opera
    val sum = r.zapret.count + r.zapret2.count + r.byedpi.count + r.dnscrypt.count + r.dpitunnel.count + r.singBox.count +
      (opera?.opera?.count ?: 0) + r.t2s.count + (opera?.byedpi?.count ?: 0)
    return sum > 0
  }

  fun computeTotals(r: StatusReport?): ProcAgg {
    if (r == null) return ProcAgg()
    val parts = buildList {
      add(r.zdtd)
      add(r.zapret)
      add(r.zapret2)
      add(r.byedpi)
      add(r.dnscrypt)
      add(r.dpitunnel)
      add(r.singBox)
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
    val rawTarget = setting?.optString("hotspot_t2s_target", "")
      ?.trim()
      ?.lowercase(Locale.ROOT)
      .orEmpty()
    val safeTarget = when (rawTarget) {
      "operaproxy", "opera-proxy", "opera_proxy" -> "operaproxy"
      "singbox", "sing-box", "sing_box" -> "singbox"
      else -> ""
    }
    val hotspotProfile = setting?.optString("hotspot_t2s_singbox_profile", "")
      ?.trim()
      .orEmpty()
    return DaemonSettings(
      protectorMode = safeMode,
      hotspotT2sEnabled = hotspotEnabled,
      hotspotT2sTarget = safeTarget,
      hotspotT2sSingboxProfile = hotspotProfile,
    )
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
    val enabledRaw = when {
      wrapper.has("enabled") -> wrapper.optInt("enabled", if (wrapper.optBoolean("enabled", false)) 1 else 0)
      else -> 0
    }
    return ProxyInfoState(
      enabled = enabledRaw != 0,
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
      out.add(
        Program(
          id = id,
          name = o.optString("name").takeIf { it.isNotBlank() },
          enabled = o.optBoolean("enabled", false),
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
      out.add(Profile(name = name, enabled = o.optBoolean("enabled", false)))
    }
    return out.sortedBy { it.name.lowercase(Locale.ROOT) }
  }

  // SimpleDateFormat is NOT thread-safe; logs are updated from multiple coroutines.
  private val TS = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.US) }
  fun fmtTs(now: Long = System.currentTimeMillis()): String = TS.get().format(Date(now))
}