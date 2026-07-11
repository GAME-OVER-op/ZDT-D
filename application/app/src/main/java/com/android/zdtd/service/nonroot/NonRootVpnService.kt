package com.android.zdtd.service.nonroot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.android.zdtd.service.MainActivity
import com.android.zdtd.service.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class NonRootVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var tunFd: Int = -1
    private var manager: NonRootProcessManager? = null
    private lateinit var modeStore: RuntimeModeStore

    override fun onCreate() {
        super.onCreate()
        modeStore = RuntimeModeStore(applicationContext)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRuntime()
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification("Starting non-root VPN"))
                scope.launch { startRuntime() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRuntime()
        scope.cancel()
        super.onDestroy()
    }

    private fun startRuntime() {
        try {
            val paths = NonRootPaths(applicationContext)
            paths.ensureBase()
            val binaries = NonRootBinaryInstaller(applicationContext).ensureBaseBinaries()
            val pfd = Builder()
                .setSession("ZDT-D Non-root")
                .setMtu(1500)
                .addAddress("10.111.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .apply { runCatching { addDisallowedApplication(packageName) } }
                .establish() ?: error("VpnService establish() returned null")
            tun = pfd
            val fd = pfd.detachFd()
            tun = null
            tunFd = fd

            val pm = NonRootProcessManager(paths.logs)
            manager = pm
            val apiDir = paths.api.absolutePath
            pm.start(
                name = "t2s",
                binary = binaries.t2s,
                args = listOf(
                    "--non-root-mode",
                    "--listen-addr", "127.0.0.1",
                    "--listen-port", DEFAULT_T2S_PORT.toString(),
                    "--socks-port", "0",
                    "--backend-mode", "priority",
                    "--web-socket",
                    "--web-addr", "127.0.0.1",
                    "--web-port", DEFAULT_T2S_WEB_PORT.toString(),
                    "--api-dir", apiDir,
                    "--instance-id", "nonroot-base",
                    "--program", "nonroot",
                    "--profile", "base",
                    "--scope", "nonroot/base",
                ),
                cwd = paths.root,
            )
            Thread.sleep(450)
            pm.start(
                name = "tun2socks",
                binary = binaries.tun2socks,
                args = listOf(
                    "-device", "fd://$fd",
                    "-proxy", "socks5://127.0.0.1:$DEFAULT_T2S_PORT",
                    "-loglevel", "info",
                ),
                cwd = paths.root,
            )
            closeDetachedTunFd()
            modeStore.setNonRootRunning(true)
            startForeground(NOTIFICATION_ID, buildNotification("Non-root VPN is running"))
        } catch (t: Throwable) {
            File(NonRootPaths(applicationContext).logs, "service-error.log").appendText((t.stackTraceToString()) + "\n")
            stopRuntime()
        }
    }

    private fun stopRuntime() {
        runCatching { manager?.stopAll() }
        manager = null
        runCatching { tun?.close() }
        tun = null
        closeDetachedTunFd()
        if (::modeStore.isInitialized) modeStore.setNonRootRunning(false)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun closeDetachedTunFd() {
        val fd = tunFd
        if (fd < 0) return
        tunFd = -1
        runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setContentTitle("ZDT-D Non-root")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "ZDT-D Non-root", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        const val ACTION_START = "com.android.zdtd.service.nonroot.START"
        const val ACTION_STOP = "com.android.zdtd.service.nonroot.STOP"
        const val DEFAULT_T2S_PORT = 11290
        const val DEFAULT_T2S_WEB_PORT = 18080
        private const val CHANNEL_ID = "zdtd_nonroot_vpn"
        private const val NOTIFICATION_ID = 4106

        fun startIntent(context: Context): Intent = Intent(context, NonRootVpnService::class.java).setAction(ACTION_START)
        fun stopIntent(context: Context): Intent = Intent(context, NonRootVpnService::class.java).setAction(ACTION_STOP)
    }
}
