package com.wife.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class ConnectionForegroundService extends Service {
    private static final String TAG = "ConnectionService";
    private static final String CHANNEL_ID = "WifeConnectionChannel";
    private static final int NOTIF_ID = 1001;

    private WiFiDirectManager wifiDirectManager;
    private WiFiDirectBroadcastReceiver receiver;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        WifeLogger.log(TAG, "onCreate() invoked. Initializing global P2P BroadcastReceiver inside Service.");
        
        try {
            wifiDirectManager = WiFiDirectManager.getInstance(this);
            receiver = new WiFiDirectBroadcastReceiver(
                    wifiDirectManager.getP2pManager(), 
                    wifiDirectManager.getChannel(), 
                    wifiDirectManager
            );

            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

            registerReceiver(receiver, intentFilter);
            WifeLogger.log(TAG, "Global WiFiDirectBroadcastReceiver registered successfully inside the Foreground Service.");
        } catch (Exception e) {
            WifeLogger.log(TAG, "Failed registering global WiFiDirectBroadcastReceiver: " + e.getMessage(), e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        WifeLogger.log(TAG, "onStartCommand() invoked. Promoting service to FOREGROUND_SERVICE_TYPE_DATA_SYNC.");
        
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Wife Offline Messenger")
                .setContentText("Monitoring nearby P2P connections...")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, notification);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        WifeLogger.log(TAG, "onDestroy() invoked. Tearing down connection service.");
        try {
            if (receiver != null) {
                unregisterReceiver(receiver);
                WifeLogger.log(TAG, "Global WiFiDirectBroadcastReceiver unregistered cleanly.");
            }
        } catch (Exception e) {
            WifeLogger.log(TAG, "Error unregistering P2P BroadcastReceiver: " + e.getMessage(), e);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Wife Connection Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}