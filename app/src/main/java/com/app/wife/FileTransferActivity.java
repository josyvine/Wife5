package com.wife.app;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher; 
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.wife.app.databinding.ActivityFileTransferBinding;

import java.util.ArrayList;
import java.util.List;

public class FileTransferActivity extends AppCompatActivity implements 
        FileSender.FileTransferListener, 
        FileReceiver.FileReceiveListener,
        FileAdapter.OnFileDeleteListener {

    private static final String TAG = "FileTransferActivity";

    private ActivityFileTransferBinding binding;
    private FileAdapter adapter;
    private final List<FileEntity> historyList = new ArrayList<>();
    private RoomDatabaseManager db;

    private final ActivityResultLauncher<String> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            this::onFileSelected
    );

    // --- High-Speed Real-time Broadcast Receiver ---
    private final BroadcastReceiver transferReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case Constants.ACTION_TRANSFER_PROGRESS:
                    String filename = intent.getStringExtra(Constants.EXTRA_FILE_NAME);
                    long transferred = intent.getLongExtra(Constants.EXTRA_BYTES_TRANSFERRED, 0);
                    long total = intent.getLongExtra(Constants.EXTRA_TOTAL_BYTES, 0);
                    int percent = (total > 0) ? (int) ((transferred * 100) / total) : 0;

                    binding.layoutTransferProgress.setVisibility(View.VISIBLE);
                    
                    if (FileTransferForegroundService.isPaused) {
                        binding.tvActiveFileName.setText("Paused: " + filename);
                    } else {
                        binding.tvActiveFileName.setText("Processing: " + filename);
                    }
                    
                    binding.pbTransferPercentage.setProgress(percent);
                    binding.tvTransferPercentText.setText(percent + "%");
                    break;

                case Constants.ACTION_TRANSFER_COMPLETE:
                    Toast.makeText(FileTransferActivity.this, "Transfer completed successfully!", Toast.LENGTH_SHORT).show();
                    binding.layoutTransferProgress.setVisibility(View.GONE);
                    loadHistory();
                    break;

                case Constants.ACTION_TRANSFER_ERROR:
                    String error = intent.getStringExtra(Constants.EXTRA_ERROR_MESSAGE);
                    Toast.makeText(FileTransferActivity.this, "Transfer failed: " + error, Toast.LENGTH_SHORT).show();
                    binding.layoutTransferProgress.setVisibility(View.GONE);
                    loadHistory();
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityFileTransferBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = RoomDatabaseManager.getInstance(this);

        setupToolbar();
        setupRecyclerView();

        binding.btnPickFile.setOnClickListener(v -> {
            filePickerLauncher.launch("*/*"); // Let user pick any file type
        });

        // Interactive transfer dialog control hook
        binding.layoutTransferProgress.setOnClickListener(v -> showTransferOptionsDialog());

        loadHistory();
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbarFileTransfer);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        binding.toolbarFileTransfer.setNavigationOnClickListener(v -> onBackPressed());
    }

    private void setupRecyclerView() {
        adapter = new FileAdapter(historyList, this);
        binding.rvFileHistory.setLayoutManager(new LinearLayoutManager(this));
        binding.rvFileHistory.setAdapter(adapter);
    }

    private void loadHistory() {
        List<FileEntity> logs = db.fileDao().getAllFiles();
        historyList.clear();
        historyList.addAll(logs);
        adapter.notifyDataSetChanged();
    }

    private void onFileSelected(Uri uri) {
        if (uri == null) return;

        String filename = "Unknown_File";
        long size = 0;

        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                
                if (nameIdx != -1) filename = cursor.getString(nameIdx);
                if (sizeIdx != -1) size = cursor.getLong(sizeIdx);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        binding.layoutTransferProgress.setVisibility(View.VISIBLE);
        binding.tvActiveFileName.setText("Uploading: " + filename);
        binding.pbTransferPercentage.setProgress(0);
        binding.tvTransferPercentText.setText("0%");

        FileSender.getInstance(this).sendFile(uri, filename, size, this);
    }

    /**
     * Shows a popup dialog allowing the user to Pause, Resume, or Cancel 
     * the background task without modifying your layout XML coordinates.
     */
    private void showTransferOptionsDialog() {
        String[] options = FileTransferForegroundService.isPaused ? 
                new String[]{"Resume Transfer", "Cancel Transfer"} : 
                new String[]{"Pause Transfer", "Cancel Transfer"};

        new AlertDialog.Builder(this)
                .setTitle("Transfer Controls")
                .setItems(options, (dialog, which) -> {
                    Intent intent = new Intent(this, FileTransferForegroundService.class);
                    if (which == 0) {
                        if (FileTransferForegroundService.isPaused) {
                            intent.setAction(Constants.ACTION_RESUME_TRANSFER);
                            Toast.makeText(this, "Resuming...", Toast.LENGTH_SHORT).show();
                        } else {
                            intent.setAction(Constants.ACTION_PAUSE_TRANSFER);
                            Toast.makeText(this, "Pausing...", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        intent.setAction(Constants.ACTION_CANCEL_TRANSFER);
                        Toast.makeText(this, "Cancelling...", Toast.LENGTH_SHORT).show();
                    }
                    startService(intent);
                })
                .show();
    }

    // --- Outgoing Callbacks (FileSender.FileTransferListener) ---

    @Override
    public void onProgress(int percent) {
        // Maintained for backward compatibility
    }

    @Override
    public void onComplete(String path) {
        // Maintained for backward compatibility
    }

    @Override
    public void onError(String error) {
        // Maintained for backward compatibility
    }

    // --- Incoming Callbacks (FileReceiver.FileReceiveListener) ---

    @Override
    public void onProgress(String filename, int percent) {
        // Maintained for backward compatibility
    }

    @Override
    public void onComplete(String filename, String localPath) {
        // Maintained for backward compatibility
    }

    // Callback invoked when delete button is tapped inside RecyclerView row item
    @Override
    public void onFileDelete(FileEntity file, int position) {
        WifeLogger.log("FileTransferActivity", "User requested deletion of file log entity: " + file.getFilename() + " at index: " + position);
        new Thread(() -> {
            try {
                // 1. Delete entity record from SQLite database using the DAO
                db.fileDao().deleteById(file.getId());
                WifeLogger.log("FileTransferActivity", "Successfully deleted file transfer entry from Room Database.");

                // 2. Refresh active list elements on Main UI Thread safely
                runOnUiThread(() -> {
                    try {
                        if (position < historyList.size()) {
                            historyList.remove(position);
                            adapter.notifyItemRemoved(position);
                            adapter.notifyItemRangeChanged(position, historyList.size());
                            Toast.makeText(this, "File transfer entry removed.", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        WifeLogger.log("FileTransferActivity", "Failed updating active adapters during UI update: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                WifeLogger.log("FileTransferActivity", "Error executing file transfer log deletion background thread: " + e.getMessage());
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        FileReceiver.registerListener(this);

        // Register local broadcast receiver for high-speed updates
        IntentFilter filter = new IntentFilter();
        filter.addAction(Constants.ACTION_TRANSFER_PROGRESS);
        filter.addAction(Constants.ACTION_TRANSFER_COMPLETE);
        filter.addAction(Constants.ACTION_TRANSFER_ERROR);
        LocalBroadcastManager.getInstance(this).registerReceiver(transferReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        FileReceiver.unregisterListener(this);

        // Unregister local broadcast receiver cleanly
        LocalBroadcastManager.getInstance(this).unregisterReceiver(transferReceiver);
    }
}