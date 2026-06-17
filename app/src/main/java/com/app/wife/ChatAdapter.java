package com.wife.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_SENT = 1;
    private static final int TYPE_RECEIVED = 2;

    private final List<MessageEntity> messages;
    private final String selfDeviceId;

    public ChatAdapter(Context context, List<MessageEntity> messages) {
        this.messages = messages;
        this.selfDeviceId = Utils.getDeviceId(context);
    }

    @Override
    public int getItemViewType(int position) {
        MessageEntity msg = messages.get(position);
        if (msg.getSender().equals(selfDeviceId)) {
            return TYPE_SENT;
        } else {
            return TYPE_RECEIVED;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_SENT) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message_sent, parent, false);
            return new SentViewHolder(v);
        } else {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message_received, parent, false);
            return new ReceivedViewHolder(v);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        MessageEntity msg = messages.get(position);
        String formattedTime = formatTime(msg.getTimestamp());
        String rawText = msg.getText();

        boolean isAttachment = rawText.startsWith("[FILE]:") || rawText.startsWith("[IMAGE]:") || 
                               rawText.startsWith("[VIDEO]:") || rawText.startsWith("[AUDIO]:");

        String filename = "Attachment";
        String fileSize = "";
        if (isAttachment) {
            try {
                int firstColon = rawText.indexOf(':');
                String payload = rawText.substring(firstColon + 1);
                String[] parts = payload.split("\\|");
                if (parts.length > 0) filename = parts[0];
                if (parts.length > 1) {
                    long bytes = Long.parseLong(parts[1]);
                    fileSize = " (" + Utils.formatFileSize(bytes) + ")";
                }
            } catch (Exception e) {
                WifeLogger.log("ChatAdapter", "Error parsing attachment: " + e.getMessage());
            }
        }

        if (holder instanceof SentViewHolder) {
            SentViewHolder h = (SentViewHolder) holder;
            if (isAttachment) {
                if (rawText.startsWith("[FILE]:")) {
                    h.tvText.setText("📁 Document: " + filename + fileSize);
                } else if (rawText.startsWith("[IMAGE]:")) {
                    h.tvText.setText("📷 Image: " + filename + fileSize);
                } else if (rawText.startsWith("[VIDEO]:")) {
                    h.tvText.setText("🎥 Video: " + filename + fileSize);
                } else if (rawText.startsWith("[AUDIO]:")) {
                    h.tvText.setText("🎤 Voice Note: " + filename + fileSize);
                }
            } else {
                h.tvText.setText(rawText);
            }
            h.tvTime.setText(formattedTime);
        } else if (holder instanceof ReceivedViewHolder) {
            ReceivedViewHolder h = (ReceivedViewHolder) holder;
            if (isAttachment) {
                final String finalFilename = filename;
                h.ivSave.setVisibility(View.VISIBLE);
                h.ivSave.setOnClickListener(v -> saveReceivedFileToPublic(v.getContext(), finalFilename));

                if (rawText.startsWith("[FILE]:")) {
                    h.tvText.setText("📁 Document: " + filename + fileSize);
                } else if (rawText.startsWith("[IMAGE]:")) {
                    h.tvText.setText("📷 Image: " + filename + fileSize);
                } else if (rawText.startsWith("[VIDEO]:")) {
                    h.tvText.setText("🎥 Video: " + filename + fileSize);
                } else if (rawText.startsWith("[AUDIO]:")) {
                    h.tvText.setText("🎤 Voice Note: " + filename + fileSize);
                }
            } else {
                h.ivSave.setVisibility(View.GONE);
                h.tvText.setText(rawText);
            }
            h.tvTime.setText(formattedTime);
        }

        // Attach long-press gesture listener to the message bubble cells
        holder.itemView.setOnLongClickListener(v -> {
            WifeLogger.log("ChatAdapter", "Long-press captured on message cell at position index: " + position);
            showContextMenu(v, msg, position);
            return true;
        });
    }

    private void saveReceivedFileToPublic(Context context, String filename) {
        WifeLogger.log("ChatAdapter", "User triggered save action for file: " + filename);
        
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

        File targetFile = new File(new File(new File(Environment.getExternalStorageDirectory(), "wife shared"), subFolder), filename);
        
        if (targetFile.exists()) {
            WifeLogger.log("ChatAdapter", "Verified file existence inside public folder: " + targetFile.getAbsolutePath());
            Toast.makeText(context, "Saved to: wife shared/" + subFolder + "/" + filename, Toast.LENGTH_LONG).show();
        } else {
            WifeLogger.log("ChatAdapter", "File not found at targeted public path. It may still be transferring.");
            Toast.makeText(context, "File is still downloading or transfer failed.", Toast.LENGTH_SHORT).show();
        }
    }

    private void showContextMenu(View anchorView, final MessageEntity msg, final int position) {
        final Context context = anchorView.getContext();
        PopupMenu popup = new PopupMenu(context, anchorView);
        
        popup.getMenu().add("Copy");
        popup.getMenu().add("Delete locally");

        // Allow unsending globally only if the message originated from the local device
        boolean isSentByMe = msg.getSender().equals(selfDeviceId);
        if (isSentByMe) {
            popup.getMenu().add("Unsend globally");
        }

        popup.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if ("Copy".equals(title)) {
                WifeLogger.log("ChatAdapter", "User clicked: 'Copy'");
                String copyText = msg.getText();
                if (copyText.startsWith("[FILE]:") || copyText.startsWith("[IMAGE]:") || 
                    copyText.startsWith("[VIDEO]:") || copyText.startsWith("[AUDIO]:")) {
                    try {
                        int firstColon = copyText.indexOf(':');
                        String payload = copyText.substring(firstColon + 1);
                        String[] parts = payload.split("\\|");
                        if (parts.length > 0) {
                            copyText = parts[0]; // Copy only the filename
                        }
                    } catch (Exception e) {
                        WifeLogger.log("ChatAdapter", "Error cleaning clipboard text: " + e.getMessage());
                    }
                }
                copyToClipboard(context, copyText);
            } else if ("Delete locally".equals(title)) {
                WifeLogger.log("ChatAdapter", "User clicked: 'Delete locally'");
                deleteLocally(context, msg, position);
            } else if ("Unsend globally".equals(title)) {
                WifeLogger.log("ChatAdapter", "User clicked: 'Unsend globally'");
                unsendGlobally(context, msg, position);
            }
            return true;
        });
        popup.show();
    }

    private void copyToClipboard(Context context, String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("WifeChat", text);
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(context, "Text copied to clipboard.", Toast.LENGTH_SHORT).show();
                WifeLogger.log("ChatAdapter", "Successfully copied message text payload to system ClipboardManager.");
            }
        } catch (Exception e) {
            WifeLogger.log("ChatAdapter", "Failed copying text payload to clipboard: " + e.getMessage(), e);
        }
    }

    private void deleteLocally(Context context, MessageEntity msg, int position) {
        WifeLogger.log("ChatAdapter", "Initiating local message deletion task. Local ID: " + msg.getId());
        new Thread(() -> {
            try {
                // 1. Purge from local SQLite DB using the long primary key ID
                RoomDatabaseManager.getInstance(context).messageDao().deleteById(msg.getId());
                WifeLogger.log("ChatAdapter", "Message row successfully purged from local Room database.");

                // 2. Refresh current list elements on Main UI Thread
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        if (position < messages.size()) {
                            messages.remove(position);
                            notifyDataSetChanged();
                            WifeLogger.log("ChatAdapter", "Dataset updated. Removed index: " + position + " | Remaining cells: " + messages.size());
                        }
                    } catch (Exception e) {
                        WifeLogger.log("ChatAdapter", "Failed updating active adapter lists: " + e.getMessage(), e);
                    }
                });
            } catch (Exception e) {
                WifeLogger.log("ChatAdapter", "Error executing local message deletion thread: " + e.getMessage(), e);
            }
        }).start();
    }

    private void unsendGlobally(Context context, MessageEntity msg, int position) {
        WifeLogger.log("ChatAdapter", "Initiating global unsend request. Shared timestamp key: " + msg.getTimestamp());
        
        // 1. Clear locally first
        deleteLocally(context, msg, position);

        // 2. Dispatch "unsend" control signal to the connected peer over Port 8888
        String peerIp = ConnectionManager.getInstance(context).getPeerIpAddress();
        if (peerIp != null && !peerIp.isEmpty()) {
            WifeLogger.log("ChatAdapter", "Broadcasting unsend signal to Peer: " + peerIp + " with timestamp: " + msg.getTimestamp());
            CallSignalingManager.getInstance(context).sendSignal(peerIp, "unsend", msg.getTimestamp());
        } else {
            WifeLogger.log("ChatAdapter", "Aborted: Peer is disconnected. Cannot transmit global unsend packet.");
            Toast.makeText(context, "Failed to unsend globally: Peer disconnected.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    private String formatTime(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    public static class SentViewHolder extends RecyclerView.ViewHolder {
        TextView tvText;
        TextView tvTime;

        public SentViewHolder(@NonNull View itemView) {
            super(itemView);
            tvText = itemView.findViewById(R.id.tvMessageText);
            tvTime = itemView.findViewById(R.id.tvMessageTime);
        }
    }

    public static class ReceivedViewHolder extends RecyclerView.ViewHolder {
        TextView tvText;
        TextView tvTime;
        ImageView ivSave; // Tiny save icon for received files

        public ReceivedViewHolder(@NonNull View itemView) {
            super(itemView);
            tvText = itemView.findViewById(R.id.tvMessageText);
            tvTime = itemView.findViewById(R.id.tvMessageTime);
            ivSave = itemView.findViewById(R.id.ivSaveAttachment);
        }
    }
}