package com.wife.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

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

        if (holder instanceof SentViewHolder) {
            SentViewHolder h = (SentViewHolder) holder;
            h.tvText.setText(msg.getText());
            h.tvTime.setText(formattedTime);
        } else if (holder instanceof ReceivedViewHolder) {
            ReceivedViewHolder h = (ReceivedViewHolder) holder;
            h.tvText.setText(msg.getText());
            h.tvTime.setText(formattedTime);
        }

        // Attach long-press gesture listener to the message bubble cells
        holder.itemView.setOnLongClickListener(v -> {
            WifeLogger.log("ChatAdapter", "Long-press captured on message cell at position index: " + position);
            showContextMenu(v, msg, position);
            return true;
        });
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
                copyToClipboard(context, msg.getText());
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

        public ReceivedViewHolder(@NonNull View itemView) {
            super(itemView);
            tvText = itemView.findViewById(R.id.tvMessageText);
            tvTime = itemView.findViewById(R.id.tvMessageTime);
        }
    }
}