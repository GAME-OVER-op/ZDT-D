package com.android.zdtd.service.widgets

import com.android.zdtd.service.api.ApiModels

internal data class ZdtdWidgetSnapshot(
  val serviceOn: Boolean = false,
  val testerActive: Boolean = false,
  val processes: Int = 0,
  val cpuPercent: Double = 0.0,
  val ramMb: Double = 0.0,
  val unavailable: Boolean = false,
)

internal fun ApiModels.StatusReport?.toWidgetSnapshot(testerActive: Boolean = false): ZdtdWidgetSnapshot {
  val report = this
  if (report == null) return ZdtdWidgetSnapshot(testerActive = testerActive, unavailable = true)
  val totals = ApiModels.computeTotals(report)
  return ZdtdWidgetSnapshot(
    serviceOn = ApiModels.isServiceOn(report),
    testerActive = testerActive,
    processes = report.widgetProcessCount(),
    cpuPercent = totals.cpuPercent.coerceAtLeast(0.0),
    ramMb = totals.rssMb.coerceAtLeast(0.0),
    unavailable = false,
  )
}

private fun ApiModels.StatusReport.widgetProcessCount(): Int {
  val operaCount = (opera?.opera?.count ?: 0) + (opera?.byedpi?.count ?: 0)
  return listOf(
    zdtd.count,
    zapret.count,
    zapret2.count,
    byedpi.count,
    dnscrypt.count,
    dpitunnel.count,
    singBox.count,
    hysteria2.count,
    wireProxy.count,
    tor.count,
    openVpn.count,
    mihomo.count,
    mieru.count,
    tun2Proxy.count,
    amneziaWg.count,
    t2s.count,
    operaCount,
  ).sum().coerceAtLeast(0)
}
