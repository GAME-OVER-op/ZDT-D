package com.android.zdtd.service

/** One-shot events that must be handled by Activity (runtime permission requests). */
sealed class NotificationEvent {
  object RequestPostNotificationsPermission : NotificationEvent()
}
