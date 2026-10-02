package com.android.zdtd.service.plugin.com;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import com.android.zdtd.service.plugin.com.ipc.ITgWsPlugin;
import com.android.zdtd.service.plugin.com.ipc.ITgWsPluginCallback;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Headless TGWS runtime. The main ZDT-D app owns all UI/notification state. */
public final class TgWsPluginService extends Service {
  private static final int API_VERSION = 1;
  private static final String MAIN_PACKAGE = "com.android.zdtd.service";
  private static final int STATE_STOPPED = 0;
  private static final int STATE_STARTING = 1;
  private static final int STATE_RUNNING = 2;
  private static final int STATE_ERROR = 3;

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Object processLock = new Object();
  private volatile Process process;
  private volatile ITgWsPluginCallback callback;
  private volatile boolean stopRequested = false;

  private final ITgWsPlugin.Stub binder = new ITgWsPlugin.Stub() {
    @Override public int getApiVersion() { enforceMainCaller(); return API_VERSION; }
    @Override public String getPluginVersion() { enforceMainCaller(); return BuildConfig.VERSION_NAME; }
    @Override public boolean isRunning() {
      enforceMainCaller();
      Process child = process;
      return child != null && child.isAlive();
    }
    @Override public void start(String[] args, ITgWsPluginCallback cb) {
      enforceMainCaller();
      callback = cb;
      executor.execute(() -> startInternal(args == null ? new String[0] : args));
    }
    @Override public void stop() {
      enforceMainCaller();
      TgWsPluginService.this.stopInternal();
    }
  };

  @Override public IBinder onBind(Intent intent) {
    return binder;
  }

  @Override public void onDestroy() {
    stopInternal();
    executor.shutdownNow();
    super.onDestroy();
  }

  private void enforceMainCaller() {
    String[] packages = getPackageManager().getPackagesForUid(Binder.getCallingUid());
    if (packages != null) {
      for (String name : packages) {
        if (MAIN_PACKAGE.equals(name)) return;
      }
    }
    throw new SecurityException("TGWS plugin accepts commands only from ZDT-D");
  }

  private void startInternal(String[] args) {
    stopInternal();
    stopRequested = false;
    notifyState(STATE_STARTING, "starting");
    File executable = new File(getApplicationInfo().nativeLibraryDir, "libzdt_tgwsproxy.so");
    if (!executable.isFile()) {
      notifyState(STATE_ERROR, "TGWS executable is missing from plugin");
      return;
    }
    try {
      List<String> command = new ArrayList<>();
      command.add(executable.getAbsolutePath());
      command.addAll(Arrays.asList(args));
      Process child = new ProcessBuilder(command)
          .directory(getFilesDir())
          .redirectErrorStream(true)
          .start();
      synchronized (processLock) {
        process = child;
      }
      notifyState(STATE_RUNNING, "running");
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          notifyLog(line);
        }
      }
      int exitCode = child.waitFor();
      synchronized (processLock) {
        if (process == child) process = null;
      }
      notifyState((stopRequested || exitCode == 0) ? STATE_STOPPED : STATE_ERROR, "exit=" + exitCode);
    } catch (Throwable t) {
      synchronized (processLock) { process = null; }
      if (stopRequested) {
        if (t instanceof InterruptedException) Thread.currentThread().interrupt();
        notifyState(STATE_STOPPED, "stopped");
      } else {
        notifyLog("ERROR " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
        notifyState(STATE_ERROR, t.getMessage() == null ? "start failed" : t.getMessage());
      }
    }
  }

  private void stopInternal() {
    stopRequested = true;
    Process child;
    synchronized (processLock) {
      child = process;
      process = null;
    }
    if (child != null) {
      try {
        child.destroy();
        if (!child.waitFor(2, TimeUnit.SECONDS)) {
          child.destroyForcibly();
          child.waitFor(1, TimeUnit.SECONDS);
        }
      } catch (Throwable ignored) {
        child.destroyForcibly();
      }
    }
    notifyState(STATE_STOPPED, "stopped");
  }

  private void notifyLog(String line) {
    ITgWsPluginCallback cb = callback;
    if (cb == null) return;
    try { cb.onLog(line == null ? "" : line); } catch (RemoteException ignored) { }
  }

  private void notifyState(int state, String message) {
    ITgWsPluginCallback cb = callback;
    if (cb == null) return;
    try { cb.onStateChanged(state, message == null ? "" : message); } catch (RemoteException ignored) { }
  }
}
