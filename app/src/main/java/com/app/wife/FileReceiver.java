package com.wife.app;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FileReceiver implements Runnable {
    private static final String TAG = "FileReceiver";

    private final Context context;
    private final Socket socket;
    private final Handler mainHandler;

    public interface FileReceiveListener {
        void onProgress(String filename, int percent);
        void onComplete(String filename, String localPath);
        void onError(String error);
    }

    private static final List<FileReceiveListener> listeners = new ArrayList<>();

    public static synchronized void registerListener(FileReceiveListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static synchronized void unregisterListener(FileReceiveListener listener) {
        listeners.remove(listener);
    }

    public FileReceiver(Context context, Socket socket) {
        this.context = context;
        this.socket = socket;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void run() {
        try {
            InputStream is = socket.getInputStream();

            // 1. Read metadata stream byte-by-byte up to '\n' to prevent buffering pollution
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int b;
            while ((b = is.read()) != -1) {
                if (b == '\n') {
                    break;
                }
                baos.write(b);
            }
            
            String metaLine = baos.toString("UTF-8");
            if (metaLine.isEmpty()) {
                throw new Exception("Socket stream ended too soon, no file meta available.");
            }
            
            JsonObject meta = JsonParser.parseString(metaLine).getAsJsonObject();
            final String filename = meta.get("name").getAsString();
            final long size = meta.get("size").getAsLong();
            Log.d(TAG, "Incoming file: " + filename + " of size: " + size);

            // 2. Resolve target directories based on extension
            File outputDir = getTargetDirectory(filename);
            File fileDest = new File(outputDir, filename);
            
            // 3. Receive the raw binary payload directly from stream
            receiveFileStream(is, filename, size, fileDest);

        } catch (Exception e) {
            Log.e(TAG, "File receive failed: " + e.getMessage());
            notifyError(e.getMessage());
        } finally {
            try {
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    private File getTargetDirectory(String filename) {
        File rootDir;
        // On Android 11 (API 30) and above, use public Download/ directory to bypass Scoped Storage write blocks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rootDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "wife shared");
        } else {
            rootDir = new File(Environment.getExternalStorageDirectory(), "wife shared");
        }

        String ext = "";
        int idx = filename.lastIndexOf('.');
        if (idx > 0) {
            ext = filename.substring(idx + 1).toLowerCase(Locale.US);
        }

        String subFolder;
        switch (ext) {
            case "mp3":
            case "emv":
            case "wav":
            case "ogg":
            case "m4a":
            case "aac":
                subFolder = "music";
                break;
            case "jpg":
            case "jpeg":
            case "png":
            case "gif":
            case "bmp":
            case "webp":
                subFolder = "images";
                break;
            case "mp4":
            case "mkv":
            case "avi":
            case "mov":
            case "3gp":
            case "webm":
                subFolder = "videos";
                break;
            case "pdf":
            case "txt":
            case "doc":
            case "docx":
            case "xls":
            case "xlsx":
            case "ppt":
            case "pptx":
                subFolder = "document";
                break;
            default:
                subFolder = "misc";
                break;
        }

        File targetDir = new File(rootDir, subFolder);
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        return targetDir;
    }

    private void receiveFileStream(InputStream is, final String filename, final long fileSize, File fileDest) throws Exception {
        byte[] buffer = new byte[8192];
        long totalRead = 0;
        
        try (FileOutputStream fos = new FileOutputStream(fileDest)) {
            int readBytes;
            while (totalRead < fileSize && (readBytes = is.read(buffer)) != -1) {
                fos.write(buffer, 0, readBytes);
                totalRead += readBytes;

                final int progress = (int) ((totalRead * 100) / fileSize);
                notifyProgress(filename, progress);
            }
            fos.flush();
        }

        Log.d(TAG, "File received successfully cached to: " + fileDest.getAbsolutePath());
        
        // Save history in Room
        FileEntity entity = new FileEntity(filename, fileSize, fileDest.getAbsolutePath(), System.currentTimeMillis());
        RoomDatabaseManager.getInstance(context).fileDao().insert(entity);

        notifyComplete(filename, fileDest.getAbsolutePath());
    }

    private void notifyProgress(final String filename, final int percent) {
        mainHandler.post(() -> {
            synchronized (FileReceiver.class) {
                for (FileReceiveListener l : listeners) {
                    l.onProgress(filename, percent);
                }
            }
        });
    }

    private void notifyComplete(final String filename, final String path) {
        mainHandler.post(() -> {
            synchronized (FileReceiver.class) {
                for (FileReceiveListener l : listeners) {
                    l.onComplete(filename, path);
                }
            }
        });
    }

    private void notifyError(final String error) {
        mainHandler.post(() -> {
            synchronized (FileReceiver.class) {
                for (FileReceiveListener l : listeners) {
                    l.onError(error);
                }
            }
        });
    }
}