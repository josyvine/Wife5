package com.wife.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.wife.app.databinding.ActivitySettingsBinding;

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";

    private ActivitySettingsBinding binding;
    private SharedPreferences prefs;

    // Modern register callback to launch local system image picker and process the output
    private final ActivityResultLauncher<String> imagePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            this::onProfileImageSelected
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        WifeLogger.log(TAG, "onCreate() invoked. Initializing Settings Activity.");

        prefs = getSharedPreferences("WifeSettings", MODE_PRIVATE);

        setupToolbar();
        displayCurrentSettings();
        setupProfilePhotoClickListeners();

        binding.btnClearChatHistory.setOnClickListener(v -> {
            WifeLogger.log(TAG, "User triggered local database wipe. Clearing SQLite tables.");
            try {
                RoomDatabaseManager.getInstance(SettingsActivity.this).clearAllTables();
                WifeLogger.log(TAG, "Room database wiped successfully.");
                Toast.makeText(SettingsActivity.this, "Local database wiped successfully.", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                WifeLogger.log(TAG, "Failed wiping Room database: " + e.getMessage(), e);
            }
        });
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbarSettings);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        binding.toolbarSettings.setNavigationOnClickListener(v -> {
            WifeLogger.log(TAG, "Toolbar back navigation clicked. Exiting Settings.");
            onBackPressed();
        });
    }

    private void setupProfilePhotoClickListeners() {
        binding.btnUploadProfilePhoto.setOnClickListener(v -> {
            WifeLogger.log(TAG, "User initiated local profile photo upload picker.");
            try {
                imagePickerLauncher.launch("image/*");
            } catch (Exception e) {
                WifeLogger.log(TAG, "Error launching local gallery image picker: " + e.getMessage(), e);
            }
        });

        binding.btnRemoveProfilePhoto.setOnClickListener(v -> {
            WifeLogger.log(TAG, "User requested profile photo removal.");
            try {
                ProfileImageManager.deleteProfileImage(this);
                binding.ivSettingsProfilePhoto.setImageResource(android.R.drawable.sym_def_app_icon);
                WifeLogger.log(TAG, "Profile photo deleted and default asset fallback restored.");
                Toast.makeText(this, "Profile photo removed.", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                WifeLogger.log(TAG, "Error removing profile photo file: " + e.getMessage(), e);
            }
        });

        binding.switchProfilePrivacy.setOnCheckedChangeListener((buttonView, isChecked) -> {
            WifeLogger.log(TAG, "Profile privacy toggle changed. Broadcast state set to: " + isChecked);
            prefs.edit().putBoolean("profile_privacy_public", isChecked).apply();
        });
    }

    private void displayCurrentSettings() {
        WifeLogger.log(TAG, "Reading stored configurations to display inside UI inputs...");
        
        String savedAlias = prefs.getString("custom_alias", Utils.getDeviceModel());
        binding.etDeviceName.setText(savedAlias);

        String secureId = Utils.getDeviceId(this);
        binding.tvSettingDeviceId.setText("Hardware Signature ID: " + secureId);

        // Load custom profile photo and privacy state
        boolean isPublic = prefs.getBoolean("profile_privacy_public", true);
        binding.switchProfilePrivacy.setChecked(isPublic);

        Bitmap bitmap = ProfileImageManager.getLocalProfileImage(this);
        if (bitmap != null) {
            binding.ivSettingsProfilePhoto.setImageBitmap(bitmap);
            WifeLogger.log(TAG, "Profile image successfully loaded from local private storage.");
        } else {
            binding.ivSettingsProfilePhoto.setImageResource(android.R.drawable.sym_def_app_icon);
            WifeLogger.log(TAG, "No profile image discovered in local storage. Using default fallback.");
        }
    }

    private void onProfileImageSelected(Uri uri) {
        if (uri == null) {
            WifeLogger.log(TAG, "Image selection cancelled. URI is null.");
            return;
        }
        WifeLogger.log(TAG, "Selected local image URI: " + uri.toString() + " | Registering with ProfileImageManager.");
        
        try {
            boolean success = ProfileImageManager.saveProfileImage(this, uri);
            if (success) {
                Bitmap bitmap = ProfileImageManager.getLocalProfileImage(this);
                if (bitmap != null) {
                    binding.ivSettingsProfilePhoto.setImageBitmap(bitmap);
                    WifeLogger.log(TAG, "Rooftop profile picture successfully updated on UI.");
                    Toast.makeText(this, "Profile photo updated successfully.", Toast.LENGTH_SHORT).show();
                }
            } else {
                WifeLogger.log(TAG, "Failed compressing or saving profile photo.");
                Toast.makeText(this, "Failed to update profile photo.", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            WifeLogger.log(TAG, "Error executing profile photo save task: " + e.getMessage(), e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Save current alias to settings sharePreferences
        String aliasEntry = binding.etDeviceName.getText().toString().trim();
        if (!aliasEntry.isEmpty()) {
            WifeLogger.log(TAG, "onPause() invoked. Auto-saving customized username alias: " + aliasEntry);
            prefs.edit().putString("custom_alias", aliasEntry).apply();
        } else {
            WifeLogger.log(TAG, "onPause() invoked, but username input field was empty. Auto-saving aborted.");
        }
    }
}