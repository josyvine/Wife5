package com.tradeanalyst.app;

import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoiceAssistantBottomSheet extends BottomSheetDialogFragment {

    public interface AssistantListener {
        void onAutomaticOrderExecuted(PaperTradeTransaction trade);
        void onCustomIndicatorGenerated(String label, double price, int color);
        void onRefreshRequired();
    }

    private AssistantListener mListener;
    private ChatAdapter mAdapter;
    private AppDatabase mDb;
    private TradingPreferences mPrefs;
    private ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private RecyclerView mChatRecycler;
    private TextView mStatusText;
    private FloatingActionButton mMicBtn;
    private TextView mMicLabel;

    private List<Candlestick> mCurrentCandles = new ArrayList<>();
    private double mCurrentPrice = 64821.50;
    private boolean mIsRecordingLive = false;

    public static VoiceAssistantBottomSheet newInstance() {
        return new VoiceAssistantBottomSheet();
    }

    public void setListener(AssistantListener listener) {
        mListener = listener;
    }

    public void setChartMetrics(List<Candlestick> candles, double currentPrice) {
        if (candles != null) {
            mCurrentCandles = new ArrayList<>(candles);
        }
        mCurrentPrice = currentPrice;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_voice_assistant, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mChatRecycler = view.findViewById(R.id.assistant_chat_recycler);
        mStatusText = view.findViewById(R.id.voice_status_text);
        mMicBtn = view.findViewById(R.id.btn_sheet_mic_record);
        mMicLabel = view.findViewById(R.id.lbl_tap_mic);

        mDb = AppDatabase.getDatabase(requireContext());
        mPrefs = new TradingPreferences(requireContext());

        mChatRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        mAdapter = new ChatAdapter();
        mChatRecycler.setAdapter(mAdapter);

        // Load conversation logs from database
        loadConversationLogs();

        // Sync UI state dynamically with the persistent background service status [1]
        if (getActivity() instanceof MainActivity) {
            MainActivity mainActivity = (MainActivity) getActivity();
            if (mainActivity.isLiveSessionActive()) {
                mIsRecordingLive = true;
                mStatusText.setText("Connected to Gemini Live Model");
                mMicBtn.setImageResource(android.R.drawable.ic_media_pause);
                mMicBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#EF4444"))); // Red
                mMicLabel.setText("TAP TO PAUSE");
            } else {
                resetMicButton();
            }
        }

        // Bind Microphone button action
        mMicBtn.setOnClickListener(v -> handleMicButtonClick());

        // Bind Clear Action button to drop table and insert default welcome statement
        view.findViewById(R.id.btn_clear_voice_logs).setOnClickListener(v -> mExecutor.execute(() -> {
            try {
                // Actually clear previous conversation rows from local SQLite database
                mDb.tradeDao().deleteAllConversations();

                // Clear and insert default welcome message
                ConversationEntity welcome = new ConversationEntity("AI Agent", "Logs cleared. Ask me anything via voice!", System.currentTimeMillis());
                mDb.tradeDao().insertConversation(welcome);
                loadConversationLogs();
                if (mListener != null) {
                    mListener.onRefreshRequired();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }));
    }

    private void handleMicButtonClick() {
        if (!(getActivity() instanceof MainActivity)) return;
        MainActivity mainActivity = (MainActivity) getActivity();

        if (mainActivity.getLiveAgentWebView() == null) {
            Toast.makeText(getContext(), "Hardware Live Assistant was not initialized.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!mIsRecordingLive) {
            // Initiate low-latency WebSockets live peer session
            mIsRecordingLive = true;
            mStatusText.setText("Connecting to live model stream...");
            mMicBtn.setImageResource(android.R.drawable.ic_media_pause);
            mMicBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#EF4444"))); // Red
            mMicLabel.setText("TAP TO PAUSE");

            // Evaluate JS call in headless WebView inside MainActivity to connect and capture audio
            mainActivity.getLiveAgentWebView().evaluateJavascript("if (window.switchToLiveMode) { window.switchToLiveMode(); }", null);
        } else {
            // Disconnect and safely release microphone hardware
            resetMicButton();
            mStatusText.setText("Live voice stream disconnected.");
            
            // Evaluate JS call in headless WebView inside MainActivity to close websocket and audio threads
            mainActivity.getLiveAgentWebView().evaluateJavascript("if (window.disconnectWebSocketLive) { window.disconnectWebSocketLive(); }", null);
        }
    }

    public void resetMicButton() {
        mIsRecordingLive = false;
        mStatusText.setText("Tap microphone below to chat.");
        mMicBtn.setImageResource(android.R.drawable.ic_btn_speak_now);
        mMicBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#10B981"))); // Primary Emerald
        mMicLabel.setText("HOLD TO TALK");
    }

    public void updateStatusText(String status) {
        if (mStatusText != null) {
            mStatusText.setText(status);
        }
    }

    public void appendChatMessage(String sender, String message) {
        mExecutor.execute(() -> {
            ConversationEntity entity = new ConversationEntity(sender, message, System.currentTimeMillis());
            mDb.tradeDao().insertConversation(entity);
            loadConversationLogs();
        });
    }

    private void loadConversationLogs() {
        mExecutor.execute(() -> {
            List<ConversationEntity> logs = mDb.tradeDao().getAllConversations();
            if (logs.isEmpty()) {
                ConversationEntity welcome = new ConversationEntity("AI Agent", "Connected. Say something like 'Should I BUY now?' or 'write custom indicator for Support at $64,200'", System.currentTimeMillis());
                mDb.tradeDao().insertConversation(welcome);
                logs.add(welcome);
            }
            final List<ConversationEntity> finalLogs = logs;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    mAdapter.setMessages(finalLogs);
                    mChatRecycler.scrollToPosition(finalLogs.size() - 1);
                });
            }
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        // REMOVED evaluateJavascript(window.disconnectWebSocketLive) to allow foreground mic persistence [1]
        // Handled manually via explicit button clicks to allow persistent background capture.
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mExecutor.shutdown();
    }
}