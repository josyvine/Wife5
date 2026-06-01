package com.inout.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import androidx.core.app.NotificationCompat;

/**
 * BroadcastReceiver triggered by AlarmManager to notify employees
 * exactly one minute before their assigned shift check-in window opens [2].
 */
public class CheckInAlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "CheckInAlarmReceiver";
    private static final String CHANNEL_ID = "shift_reminder_channel";
    private static final int NOTIFICATION_ID = 1001;

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Alarm received: Preparing shift start notification");

        try {
            // 1. Create high-importance channel (Required for Android 8.0+)
            createNotificationChannel(context);

            // 2. Intent to launch the launcher splash activity when tapped
            Intent launchIntent = new Intent(context, SplashActivity.class);
            launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            // 3. Build notification properties with high priority for heads-up alert
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.inout) // Standard launcher drawable resource
                    .setContentTitle("Shift Starting Soon!")
                    .setContentText("Your check-in window opens in 1 minute. Please open the app and prepare to check in.")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true);

            // 4. Fire notification via the System Notification Service
            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID, builder.build());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to build or send near-time shift reminder", e);
        }
    }

    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Shift Reminders";
            String description = "Notifications triggered before work shifts start";
            int importance = NotificationManager.IMPORTANCE_HIGH;
            
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);

            NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }
}