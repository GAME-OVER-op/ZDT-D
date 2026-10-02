package com.android.zdtd.service.plugin.com.ipc;

oneway interface ITgWsPluginCallback {
    void onLog(String line);
    void onStateChanged(int state, String message);
}
