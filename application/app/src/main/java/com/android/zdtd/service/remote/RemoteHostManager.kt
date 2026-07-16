package com.android.zdtd.service.remote

import android.content.Context
import com.android.zdtd.service.RootConfigManager

object RemoteHostManager {
  @Volatile private var server: RemoteHostServer? = null

  fun get(context: Context): RemoteHostServer {
    return server ?: synchronized(this) {
      server ?: RemoteHostServer(
        context.applicationContext,
        RootConfigManager(context.applicationContext),
      ).also { server = it }
    }
  }
}
