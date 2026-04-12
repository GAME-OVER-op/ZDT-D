package com.android.zdtd.service

import android.content.Intent

data class ProxyInfoProbeEvent(
  val eventType: String,
  val packageName: String,
  val packagesCsv: String,
  val uid: Int,
  val proto: String,
  val portsHint: String,
  val hitCount: Int,
  val windowSecs: Int,
  val source: String,
) {
  val isConfirmed: Boolean get() = eventType == ProxyInfoProbeContract.EVENT_CONFIRMED
  val notificationId: Int get() = 200000 + ((packageName + ":" + uid).hashCode() and 0x7fffffff)
}

object ProxyInfoProbeContract {
  const val ACTION_PROXYINFO_PROBE_DETECTED = "com.android.zdtd.service.ACTION_PROXYINFO_PROBE_DETECTED"
  const val EVENT_SUSPICION = "suspicion"
  const val EVENT_CONFIRMED = "confirmed"

  const val EXTRA_EVENT_TYPE = "event_type"
  const val EXTRA_PACKAGE = "package"
  const val EXTRA_PACKAGES_CSV = "packages_csv"
  const val EXTRA_UID = "uid"
  const val EXTRA_PROTO = "proto"
  const val EXTRA_PORTS_HINT = "ports_hint"
  const val EXTRA_HIT_COUNT = "hit_count"
  const val EXTRA_WINDOW_SECS = "window_secs"
  const val EXTRA_SOURCE = "source"

  fun fromIntent(intent: Intent): ProxyInfoProbeEvent? {
    val packageName = intent.getStringExtra(EXTRA_PACKAGE)?.trim().orEmpty()
    val eventType = intent.getStringExtra(EXTRA_EVENT_TYPE)?.trim().orEmpty()
    if (packageName.isEmpty()) return null
    val normalizedType = when (eventType) {
      EVENT_CONFIRMED -> EVENT_CONFIRMED
      else -> EVENT_SUSPICION
    }
    return ProxyInfoProbeEvent(
      eventType = normalizedType,
      packageName = packageName,
      packagesCsv = intent.getStringExtra(EXTRA_PACKAGES_CSV)?.trim().orEmpty(),
      uid = intent.getIntExtra(EXTRA_UID, -1),
      proto = intent.getStringExtra(EXTRA_PROTO)?.trim().orEmpty(),
      portsHint = intent.getStringExtra(EXTRA_PORTS_HINT)?.trim().orEmpty(),
      hitCount = intent.getIntExtra(EXTRA_HIT_COUNT, 0),
      windowSecs = intent.getIntExtra(EXTRA_WINDOW_SECS, 0),
      source = intent.getStringExtra(EXTRA_SOURCE)?.trim().orEmpty(),
    )
  }

  fun writeToIntent(intent: Intent, event: ProxyInfoProbeEvent): Intent = intent.apply {
    putExtra(EXTRA_EVENT_TYPE, event.eventType)
    putExtra(EXTRA_PACKAGE, event.packageName)
    putExtra(EXTRA_PACKAGES_CSV, event.packagesCsv)
    putExtra(EXTRA_UID, event.uid)
    putExtra(EXTRA_PROTO, event.proto)
    putExtra(EXTRA_PORTS_HINT, event.portsHint)
    putExtra(EXTRA_HIT_COUNT, event.hitCount)
    putExtra(EXTRA_WINDOW_SECS, event.windowSecs)
    putExtra(EXTRA_SOURCE, event.source)
  }
}
