package com.android.zdtd.service

import android.content.Intent

/**
 * Contract for the captive-portal "device is waiting for approval" flow.
 *
 * The root daemon fires a single broadcast (fire-once) carrying only the
 * minimal, non-sensitive fields (model + short id). The full device card
 * (IP / MAC / User-Agent) is loaded in-app over the token-authenticated local
 * API, never passed through the exported broadcast.
 */
data class CaptiveDeviceEvent(
  val shortId: String,
  val model: String,
) {
  /** Human-friendly "name (identifier)" label for the notification body. */
  val displayLabel: String
    get() = when {
      model.isNotBlank() && shortId.isNotBlank() && model != shortId -> "$model ($shortId)"
      model.isNotBlank() -> model
      else -> shortId
    }

  /** Stable, per-device notification id so multiple pending devices coexist. */
  val notificationId: Int
    get() = 300000 + ((shortId.ifBlank { model }).hashCode() and 0x7fffffff)
}

object CaptiveDeviceContract {
  /** Broadcast fired by the root daemon (exported receiver). */
  const val ACTION_CAPTIVE_DEVICE_PENDING = "com.android.zdtd.service.ACTION_CAPTIVE_DEVICE_PENDING"

  /** App-private actions used by the notification action buttons. */
  const val ACTION_CAPTIVE_ALLOW = "com.android.zdtd.service.ACTION_CAPTIVE_ALLOW"
  const val ACTION_CAPTIVE_DENY = "com.android.zdtd.service.ACTION_CAPTIVE_DENY"

  const val EXTRA_SHORT_ID = "short_id"
  const val EXTRA_MODEL = "model"
  const val EXTRA_NOTIFICATION_ID = "notification_id"

  fun fromIntent(intent: Intent): CaptiveDeviceEvent? {
    val shortId = intent.getStringExtra(EXTRA_SHORT_ID)?.trim().orEmpty()
    val model = intent.getStringExtra(EXTRA_MODEL)?.trim().orEmpty()
    if (shortId.isEmpty() && model.isEmpty()) return null
    return CaptiveDeviceEvent(shortId = shortId, model = model)
  }
}
