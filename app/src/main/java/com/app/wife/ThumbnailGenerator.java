package com.wife.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.util.Log;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;

public final class ThumbnailGenerator {
    private static final String TAG = "ThumbnailGenerator";

    private ThumbnailGenerator() {}

    /**
     * Decodes and downsamples a local image file safely using file descriptors to bypass Scoped Storage sandbox restrictions.
     *
     * @param file      The local image file.
     * @param reqWidth  The target layout width in pixels.
     * @param reqHeight The target layout height in pixels.
     * @return An optimized Bitmap preview, or null if decoding fails.
     */
    public static Bitmap getImageThumbnail(File file, int reqWidth, int reqHeight) {
        if (file == null || !file.exists()) {
            return null;
        }
        // Use FileInputStream and FileDescriptor to bypass Android 10+ file path isolation
        try (FileInputStream fis = new FileInputStream(file)) {
            FileDescriptor fd = fis.getFD();
            
            BitmapFactory.Options options = new BitmapFactory.Options();
            // Read image dimensions only, without allocating memory for pixels
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(fd, null, options);

            // Calculate the downsampling scale factor
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

            // Allocate memory and decode the optimized downsampled bitmap
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeFileDescriptor(fd, null, options);
        } catch (Exception e) {
            WifeLogger.log(TAG, "Failed generating image thumbnail for file: " + file.getName() + " | Error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Extracts a frame from a local video file using MediaMetadataRetriever and FileDescriptors to bypass sandbox security blocks.
     *
     * @param file The local video file.
     * @return A video frame Bitmap, or null if retrieval fails.
     */
    public static Bitmap getVideoThumbnail(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        // Use FileInputStream to pass a FileDescriptor to prevent permission failures on modern API levels
        try (FileInputStream fis = new FileInputStream(file)) {
            retriever.setDataSource(fis.getFD());
            // Extract a frame at the 1-second timestamp (1,000,000 microseconds)
            return retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Exception e) {
            WifeLogger.log(TAG, "Failed generating video thumbnail for file: " + file.getName() + " | Error: " + e.getMessage());
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {}
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }
}