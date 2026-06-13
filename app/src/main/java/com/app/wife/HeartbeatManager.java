package com.wife.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class HeartbeatManager {
    private static final String TAG = "HeartbeatManager";
    private static volatile HeartbeatManager instance;

    private final Context context;
    private final ScheduledExecutorService scheduler;
    private final Handler mainHandler;

    private ScheduledFuture<?> sendTask;
    private ScheduledFuture<?> checkTask;

    private long lastHeartbeatReceived = 0;
    private boolean isMonitoring = false;

    public static HeartbeatManager getInstance(Context context) {
        if (instance == null) {
            synchronized (HeartbeatManager.class) {
                if (instance == null) {
                    instance = new HeartbeatManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private HeartbeatManager(Context context) {
        this.context = context;
        this.scheduler = new ScheduledThreadPoolExecutor(1);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public synchronized void startMonitoring() {
        if (isMonitoring) return;
        isMonitoring = true;
        lastHeartbeatReceived = System.currentTimeMillis();

        // Send heartbeat packet every 5 seconds
        sendTask = scheduler.scheduleAtFixedRate(this::sendHeartbeat, 0, 5, TimeUnit.SECONDS);

        // Check for heartbeat failures every 5 seconds
        checkTask = scheduler.scheduleAtFixedRate(this::checkHeartbeatStatus, 5, 5, TimeUnit.SECONDS);
        Log.d(TAG, "Heartbeat monitor launched.");
    }

    public synchronized void stopMonitoring() {
        isMonitoring = false;

        // Cancel the individual running tasks instead of shutting down the scheduler
        if (sendTask != null) {
            sendTask.cancel(true);
            sendTask = null;
        }
        if (checkTask != null) {
            checkTask.cancel(true);
            checkTask = null;
        }

        Log.d(TAG, "Heartbeat monitoring stopped.");
    }

    private void sendHeartbeat() {
        String peerIp = ConnectionManager.getInstance(context).getPeerIpAddress();
        if (peerIp != null && !peerIp.isEmpty()) {
            CallSignalingManager.getInstance(context).sendSignal(peerIp, "heartbeat");
        }
    }

    private void checkHeartbeatStatus() {
        if (!isMonitoring) return;
        
        long diff = System.currentTimeMillis() - lastHeartbeatReceived;
        if (diff > 15000) { // No heartbeat received for over 15 seconds
            Log.e(TAG, "Heartbeat timeout! Peer disconnected.");
            mainHandler.post(() -> {
                // Terminate connections and trigger auto-reconnection
                ConnectionManager.getInstance(context).teardown();
                ReconnectManager.getInstance(context).triggerReconnect();
            });
        }
    }

    public synchronized void onHeartbeatReceived(String peerIp) {
        lastHeartbeatReceived = System.currentTimeMillis();
        Log.d(TAG, "Heartbeat received from " + peerIp);
    }
}