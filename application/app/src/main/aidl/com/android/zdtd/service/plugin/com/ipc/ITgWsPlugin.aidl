package com.android.zdtd.service.plugin.com.ipc;

import com.android.zdtd.service.plugin.com.ipc.ITgWsPluginCallback;

interface ITgWsPlugin {
    int getApiVersion();
    String getPluginVersion();
    boolean isRunning();
    void start(in String[] args, ITgWsPluginCallback callback);
    void stop();
}
