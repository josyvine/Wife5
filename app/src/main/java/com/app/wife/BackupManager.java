package com.wife.app;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class BackupManager {
    private static final String TAG = "BackupManager";
    private static final String BACKUP_DIR_NAME = "backups";

    private BackupManager() {
        // Prevent instantiation of utility class
    }

    /**
     * Resolves and creates the persistent public directory.
     * Survives Android system data clearance.
     */
    public static File getBackupDir() {
        File rootDir;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rootDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "wife shared");
        } else {
            rootDir = new File(Environment.getExternalStorageDirectory(), "wife shared");
        }
        File backupDir = new File(rootDir, BACKUP_DIR_NAME);
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }
        return backupDir;
    }

    /**
     * Resolves the target backup file name structured by peer hardware ID.
     */
    public static File getBackupFile(String peerDeviceId) {
        return new File(getBackupDir(), "backup_" + peerDeviceId + ".json");
    }

    /**
     * Backs up a single peer conversation log to public shared storage.
     */
    public static boolean backupChat(Context context, String peerDeviceId, String selfId) {
        WifeLogger.log(TAG, "Initiating chat backup for peer ID: " + peerDeviceId);
        try {
            RoomDatabaseManager db = RoomDatabaseManager.getInstance(context);
            List<MessageEntity> history = db.messageDao().getChatHistory(peerDeviceId, selfId);
            if (history == null || history.isEmpty()) {
                WifeLogger.log(TAG, "No chat history discovered to backup for peer: " + peerDeviceId);
                return false;
            }

            File backupFile = getBackupFile(peerDeviceId);
            Gson gson = new Gson();
            String json = gson.toJson(history);

            try (FileOutputStream fos = new FileOutputStream(backupFile);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(json);
                writer.flush();
            }

            WifeLogger.log(TAG, "Chat backup successfully written: " + backupFile.getAbsolutePath() + " | Messages: " + history.size());
            return true;
        } catch (Exception e) {
            WifeLogger.log(TAG, "Failed backing up chat logs: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Restores a single peer conversation log from public storage, avoiding duplicate database rows.
     */
    public static boolean restoreChat(Context context, String peerDeviceId, String selfId) {
        WifeLogger.log(TAG, "Initiating chat restoration for peer ID: " + peerDeviceId);
        File backupFile = getBackupFile(peerDeviceId);
        if (!backupFile.exists()) {
            WifeLogger.log(TAG, "Restoration skipped: No backup discovered for peer ID: " + peerDeviceId);
            return false;
        }

        try {
            Gson gson = new Gson();
            List<MessageEntity> backupList;
            try (FileInputStream fis = new FileInputStream(backupFile);
                 InputStreamReader reader = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
                Type listType = new TypeToken<ArrayList<MessageEntity>>() {}.getType();
                backupList = gson.fromJson(reader, listType);
            }

            if (backupList == null || backupList.isEmpty()) {
                WifeLogger.log(TAG, "Aborted: Decoded message backup array is empty.");
                return false;
            }

            RoomDatabaseManager db = RoomDatabaseManager.getInstance(context);
            List<MessageEntity> currentHistory = db.messageDao().getChatHistory(peerDeviceId, selfId);
            
            // Build composite unique validation keys (timestamp + sender ID) to prevent row duplication
            Set<String> uniqueKeys = new HashSet<>();
            if (currentHistory != null) {
                for (MessageEntity msg : currentHistory) {
                    uniqueKeys.add(msg.getTimestamp() + "_" + msg.getSender());
                }
            }

            int importCount = 0;
            for (MessageEntity msg : backupList) {
                String key = msg.getTimestamp() + "_" + msg.getSender();
                if (!uniqueKeys.contains(key)) {
                    // Reset primary key auto ID to allow Room to generate a clean local index
                    msg.setId(0);
                    db.messageDao().insert(msg);
                    uniqueKeys.add(key);
                    importCount++;
                }
            }

            WifeLogger.log(TAG, "Restoration complete. Merged " + importCount + " new messages out of " + backupList.size() + " total history records.");
            return true;
        } catch (Exception e) {
            WifeLogger.log(TAG, "Failed restoring chat thread: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Backs up all chat histories stored in database by grouping them by peer ID.
     */
    public static boolean backupAllChats(Context context) {
        WifeLogger.log(TAG, "Initiating global backup of all chat histories to public shared folder.");
        try {
            RoomDatabaseManager db = RoomDatabaseManager.getInstance(context);
            List<MessageEntity> allMessages = db.messageDao().getAllMessages();
            if (allMessages == null || allMessages.isEmpty()) {
                WifeLogger.log(TAG, "Global backup skipped: Local message table is empty.");
                return false;
            }

            String selfId = Utils.getDeviceId(context);
            java.util.Map<String, List<MessageEntity>> groupedMap = new java.util.HashMap<>();
            
            // Categorize historical records per peer
            for (MessageEntity msg : allMessages) {
                String peerId = msg.getSender().equals(selfId) ? msg.getReceiver() : msg.getSender();
                if (!groupedMap.containsKey(peerId)) {
                    groupedMap.put(peerId, new ArrayList<>());
                }
                groupedMap.get(peerId).add(msg);
            }

            int successCount = 0;
            Gson gson = new Gson();
            for (java.util.Map.Entry<String, List<MessageEntity>> entry : groupedMap.entrySet()) {
                String peerId = entry.getKey();
                List<MessageEntity> peerHistory = entry.getValue();
                
                File backupFile = getBackupFile(peerId);
                String json = gson.toJson(peerHistory);

                try (FileOutputStream fos = new FileOutputStream(backupFile);
                     OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                    writer.write(json);
                    writer.flush();
                    successCount++;
                }
            }

            WifeLogger.log(TAG, "Global backup completed. Created " + successCount + " independent backup files.");
            return successCount > 0;
        } catch (Exception e) {
            WifeLogger.log(TAG, "Error executing global backups: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Scans and restores all peer conversation backup logs found in public folder.
     */
    public static boolean restoreAllChats(Context context) {
        WifeLogger.log(TAG, "Initiating global restoration of all chat history files from public storage.");
        try {
            File backupDir = getBackupDir();
            File[] files = backupDir.listFiles();
            if (files == null || files.length == 0) {
                WifeLogger.log(TAG, "Global restoration aborted: No backups discovered in path: " + backupDir.getAbsolutePath());
                return false;
            }

            String selfId = Utils.getDeviceId(context);
            int restoreCount = 0;

            for (File f : files) {
                String name = f.getName();
                if (name.startsWith("backup_") && name.endsWith(".json")) {
                    // Extract peer signature ID from file name: "backup_[ID].json"
                    String peerId = name.substring(7, name.length() - 5);
                    if (!peerId.isEmpty()) {
                        boolean success = restoreChat(context, peerId, selfId);
                        if (success) {
                            restoreCount++;
                        }
                    }
                }
            }

            WifeLogger.log(TAG, "Global restoration finished. Restored " + restoreCount + " chat threads successfully.");
            return restoreCount > 0;
        } catch (Exception e) {
            WifeLogger.log(TAG, "Error executing global restorations: " + e.getMessage(), e);
            return false;
        }
    }
}