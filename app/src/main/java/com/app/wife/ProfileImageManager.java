package com.wife.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

public class ProfileImageManager {
    private static final String TAG = "ProfileImageManager";
    private static final String FILE_NAME = "profile_picture.jpg";
    private static final int TARGET_SIZE = 128; // 128x128 pixels for ultra-lightweight offline socket transfers

    private ProfileImageManager() {}

    public static boolean saveProfileImage(Context context, Uri sourceUri) {
        WifeLogger.log(TAG, "saveProfileImage() invoked. Sourcing URI: " + sourceUri.toString());
        InputStream is = null;
        FileOutputStream fos = null;
        Bitmap rawBitmap = null;
        Bitmap scaledBitmap = null;

        try {
            is = context.getContentResolver().openInputStream(sourceUri);
            rawBitmap = BitmapFactory.decodeStream(is);
            if (rawBitmap == null) {
                WifeLogger.log(TAG, "Failed to decode input stream into a valid Bitmap.");
                return false;
            }

            WifeLogger.log(TAG, "Successfully decoded raw bitmap. Dimensions: " + rawBitmap.getWidth() + "x" + rawBitmap.getHeight() + " | Scaling to " + TARGET_SIZE + "x" + TARGET_SIZE);
            scaledBitmap = Bitmap.createScaledBitmap(rawBitmap, TARGET_SIZE, TARGET_SIZE, true);

            File targetFile = new File(context.getFilesDir(), FILE_NAME);
            WifeLogger.log(TAG, "Writing compressed JPEG to private storage path: " + targetFile.getAbsolutePath());
            
            fos = new FileOutputStream(targetFile);
            boolean compressed = scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, fos);
            fos.flush();

            WifeLogger.log(TAG, "Compression process completed. Success: " + compressed + " | Written size: " + targetFile.length() + " bytes.");
            return compressed;
        } catch (Exception e) {
            WifeLogger.log(TAG, "Exception occurred during profile image saving: " + e.getMessage(), e);
            return false;
        } finally {
            try {
                if (is != null) is.close();
                if (fos != null) fos.close();
            } catch (Exception ignored) {}
            if (rawBitmap != null) rawBitmap.recycle();
            if (scaledBitmap != null) scaledBitmap.recycle();
        }
    }

    public static Bitmap getLocalProfileImage(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) {
            return null;
        }
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath());
        } catch (Exception e) {
            WifeLogger.log(TAG, "Error decoding local profile photo file: " + e.getMessage(), e);
            return null;
        }
    }

    public static String getLocalProfileImageBase64(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) {
            return null;
        }

        FileInputStream fis = null;
        ByteArrayOutputStream baos = null;
        try {
            fis = new FileInputStream(file);
            baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int readBytes;
            while ((readBytes = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, readBytes);
            }
            byte[] fileBytes = baos.toByteArray();
            
            // Encode using NO_WRAP to prevent linebreaks in the middle of our JSON signaling payload
            String base64String = Base64.encodeToString(fileBytes, Base64.NO_WRAP);
            WifeLogger.log(TAG, "Successfully read and converted profile picture file to Base64 string. String length: " + base64String.length());
            return base64String;
        } catch (Exception e) {
            WifeLogger.log(TAG, "Error converting local profile photo to Base64: " + e.getMessage(), e);
            return null;
        } finally {
            try {
                if (fis != null) fis.close();
                if (baos != null) baos.close();
            } catch (Exception ignored) {}
        }
    }

    public static void deleteProfileImage(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (file.exists()) {
            boolean deleted = file.delete();
            WifeLogger.log(TAG, "deleteProfileImage() executed. Target file purged: " + deleted);
        } else {
            WifeLogger.log(TAG, "deleteProfileImage() bypassed: No local file discovered.");
        }
    }
}