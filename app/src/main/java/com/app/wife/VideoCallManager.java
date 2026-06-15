package com.wife.app;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LifecycleOwner;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoCallManager {
    private static final String TAG = "VideoCallManager";
    private static volatile VideoCallManager instance;

    private final Context context;
    private final VideoCaptureManager captureManager;
    private final VideoDecoderManager decoderManager;
    private final ExecutorService executorService;

    private ServerSocket serverSocket;
    private Socket activeSocket;
    private boolean isCallActive = false;

    public static VideoCallManager getInstance(Context context) {
        if (instance == null) {
            synchronized (VideoCallManager.class) {
                if (instance == null) {
                    instance = new VideoCallManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private VideoCallManager(Context context) {
        this.context = context;
        this.captureManager = new VideoCaptureManager(context);
        this.decoderManager = new VideoDecoderManager();
        this.executorService = Executors.newSingleThreadExecutor();
    }

    public synchronized void startCall(final LifecycleOwner lifecycleOwner, final String peerIp, final VideoDecoderManager.VideoFrameListener frameListener) {
        WifeLogger.log(TAG, "startCall() invoked. Target Peer IP: " + peerIp + " | Checking active status...");
        if (isCallActive) {
            WifeLogger.log(TAG, "startCall() aborted: A video call session is already active.");
            return;
        }
        isCallActive = true;

        executorService.execute(() -> {
            WifeLogger.log(TAG, "Outbound video call thread launched. Attempting socket connection to " + peerIp + " on Port: " + Constants.OFF_PORT_VIDEO);
            try {
                activeSocket = new Socket(peerIp, Constants.OFF_PORT_VIDEO);
                WifeLogger.log(TAG, "Outbound Video Socket connected successfully with " + peerIp + " on port " + Constants.OFF_PORT_VIDEO);
                
                // Start capture and decoding
                WifeLogger.log(TAG, "Initializing CameraX capture stream on socket OutputStream.");
                captureManager.startCapture(lifecycleOwner, activeSocket.getOutputStream());

                WifeLogger.log(TAG, "Initializing VideoDecoderManager frame reader on socket InputStream.");
                decoderManager.startDecoding(activeSocket.getInputStream(), frameListener);
                
                Log.d(TAG, "Outbound video calling pipeline active.");
                WifeLogger.log(TAG, "Outbound video calling pipeline active and executing frames.");
            } catch (Exception e) {
                Log.e(TAG, "Outbound video calling socket failed: " + e.getMessage());
                WifeLogger.log(TAG, "Failed establishing outbound Video Socket connection to " + peerIp + " | Exception: " + e.getMessage(), e);
                teardown();
            }
        });
    }

    public synchronized void listenForIncomingCall(final LifecycleOwner lifecycleOwner, final VideoDecoderManager.VideoFrameListener frameListener) {
        WifeLogger.log(TAG, "listenForIncomingCall() invoked. Checking active status...");
        if (isCallActive) {
            WifeLogger.log(TAG, "listenForIncomingCall() aborted: A video call session is already active.");
            return;
        }
        isCallActive = true;

        executorService.execute(() -> {
            WifeLogger.log(TAG, "Inbound video call thread launched. Initializing ServerSocket on Port: " + Constants.OFF_PORT_VIDEO);
            try {
                serverSocket = new ServerSocket(Constants.OFF_PORT_VIDEO);
                Log.d(TAG, "Listening for incoming Video Call on port " + Constants.OFF_PORT_VIDEO);
                WifeLogger.log(TAG, "Inbound video ServerSocket bound successfully. Awaiting incoming connections...");
                
                activeSocket = serverSocket.accept();
                String remoteIp = activeSocket.getInetAddress() != null ? activeSocket.getInetAddress().getHostAddress() : "Unknown IP";
                Log.d(TAG, "Inbound video call socket accepted.");
                WifeLogger.log(TAG, "Inbound Video socket connection accepted from client: " + remoteIp);

                WifeLogger.log(TAG, "Initializing CameraX capture stream on socket OutputStream.");
                captureManager.startCapture(lifecycleOwner, activeSocket.getOutputStream());

                WifeLogger.log(TAG, "Initializing VideoDecoderManager frame reader on socket InputStream.");
                decoderManager.startDecoding(activeSocket.getInputStream(), frameListener);

                Log.d(TAG, "Inbound video calling pipeline active.");
                WifeLogger.log(TAG, "Inbound video calling pipeline active and executing frames.");
            } catch (Exception e) {
                Log.e(TAG, "Inbound video calling server failed: " + e.getMessage());
                WifeLogger.log(TAG, "Inbound video server or capture initialization failed: " + e.getMessage(), e);
                teardown();
            }
        });
    }

    public synchronized void switchCamera(LifecycleOwner lifecycleOwner) {
        WifeLogger.log(TAG, "switchCamera() invoked. Attempting to alternate active camera lens facing...");
        if (isCallActive && activeSocket != null) {
            try {
                captureManager.switchCamera(lifecycleOwner, activeSocket.getOutputStream());
                WifeLogger.log(TAG, "Camera lens rotated successfully.");
            } catch (Exception e) {
                e.printStackTrace();
                WifeLogger.log(TAG, "Failed to switch active camera lens facing: " + e.getMessage(), e);
            }
        } else {
            WifeLogger.log(TAG, "switchCamera() aborted: Call is inactive or activeSocket is null.");
        }
    }

    public synchronized void muteCamera(boolean mute) {
        WifeLogger.log(TAG, "muteCamera() invoked. Mute: " + mute);
        if (mute) {
            WifeLogger.log(TAG, "Mute requested. Stopping camera capture.");
            captureManager.stopCapture();
        } else if (activeSocket != null && activeSocket.isConnected()) {
            WifeLogger.log(TAG, "Unmute requested. Camera capture restart is delegated to local UI activity context.");
            // Restart capture (this would typically need a LifecycleOwner. 
            // We can delegate this camera on/off to our UI activity easily.)
        }
    }

    public synchronized void endCall() {
        WifeLogger.log(TAG, "endCall() invoked. Terminating active session.");
        teardown();
    }

    private synchronized void teardown() {
        WifeLogger.log(TAG, "teardown() invoked. Terminating video socket connections and releasing camera engines.");
        isCallActive = false;
        
        WifeLogger.log(TAG, "Stopping CameraX capture provider.");
        captureManager.stopCapture();
        
        WifeLogger.log(TAG, "Stopping VideoDecoderManager frame rendering thread.");
        decoderManager.stopDecoding();

        if (activeSocket != null) {
            try {
                WifeLogger.log(TAG, "Closing active Video Socket client connection.");
                activeSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
                WifeLogger.log(TAG, "Error closing active Video Socket: " + e.getMessage(), e);
            }
            activeSocket = null;
        }

        if (serverSocket != null) {
            try {
                WifeLogger.log(TAG, "Closing inbound video ServerSocket.");
                serverSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
                WifeLogger.log(TAG, "Error closing video ServerSocket: " + e.getMessage(), e);
            }
            serverSocket = null;
        }
        Log.d(TAG, "VideoCall pipelines terminated.");
        WifeLogger.log(TAG, "Video call pipeline teardown completed cleanly.");
    }
}