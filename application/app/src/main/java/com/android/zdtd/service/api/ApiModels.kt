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