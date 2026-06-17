package com.wife.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;

public class MediaViewerActivity extends AppCompatActivity {
    private static final String TAG = "MediaViewerActivity";

    private ImageView ivPhoto;
    private VideoView vvVideo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Enable immersive full-screen mode to hide status and action bars
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        
        setContentView(R.layout.activity_media_viewer);

        ivPhoto = findViewById(R.id.ivViewerPhoto);
        vvVideo = findViewById(R.id.vvViewerVideo);

        String filePath = getIntent().getStringExtra("FILE_PATH");
        String fileType = getIntent().getStringExtra("FILE_TYPE");

        WifeLogger.log(TAG, "onCreate() initiated. File Path: " + filePath + " | Type: " + fileType);

        if (filePath == null || filePath.isEmpty()) {
            Toast.makeText(this, "Media path is unresolved.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            Toast.makeText(this, "Media file does not exist on storage.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if ("image".equalsIgnoreCase(fileType)) {
            displayImage(file);
        } else if ("video".equalsIgnoreCase(fileType)) {
            playVideo(file);
        } else {
            Toast.makeText(this, "Unsupported media format.", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void displayImage(File file) {
        ivPhoto.setVisibility(View.VISIBLE);
        vvVideo.setVisibility(View.GONE);

        try {
            // Decode image memory-safely using standard local factory options
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1; // Decode full resolution for detailed viewing
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap != null) {
                ivPhoto.setImageBitmap(bitmap);
                WifeLogger.log(TAG, "Successfully loaded full-screen image preview: " + file.getName());
            } else {
                Toast.makeText(this, "Failed to decode image.", Toast.LENGTH_SHORT).show();
                finish();
            }
        } catch (Exception e) {
            WifeLogger.log(TAG, "Exception decoding image for full-screen viewer: " + e.getMessage(), e);
            Toast.makeText(this, "Error loading image file.", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void playVideo(File file) {
        ivPhoto.setVisibility(View.GONE);
        vvVideo.setVisibility(View.VISIBLE);

        try {
            MediaController mediaController = new MediaController(this);
            mediaController.setAnchorView(vvVideo);
            vvVideo.setMediaController(mediaController);
            vvVideo.setVideoURI(Uri.fromFile(file));
            
            vvVideo.setOnPreparedListener(mp -> {
                WifeLogger.log(TAG, "Video player prepared. Auto-starting playback loop.");
                vvVideo.start();
            });

            vvVideo.setOnErrorListener((mp, what, extra) -> {
                WifeLogger.log(TAG, "Video playback failed. Error Code: " + what + " Extra: " + extra);
                Toast.makeText(MediaViewerActivity.this, "Failed to play video file.", Toast.LENGTH_SHORT).show();
                finish();
                return true;
            });

            vvVideo.setOnCompletionListener(mp -> {
                WifeLogger.log(TAG, "Video playback completed.");
                Toast.makeText(MediaViewerActivity.this, "Video finished playing.", Toast.LENGTH_SHORT).show();
            });

        } catch (Exception e) {
            WifeLogger.log(TAG, "Exception setting up video viewer pipeline: " + e.getMessage(), e);
            Toast.makeText(this, "Error initializing video playback.", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        WifeLogger.log(TAG, "onDestroy() called. Releasing media players.");
        if (vvVideo != null) {
            vvVideo.stopPlayback();
        }
    }
}