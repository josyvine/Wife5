package com.wife.app;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SocketServer {
    private static final String TAG = "SocketServer";

    private final Context context;
    private final ConnectionManager connectionManager;
    private final ExecutorService executorService;

    private ServerSocket controlSocket;
    private ServerSocket textSocket;

    private boolean isRunning = false;

    public SocketServer(Context context, ConnectionManager connectionManager) {
        this.context = context;
        this.connectionManager = connectionManager;
        this.executorService = Executors.newFixedThreadPool(2); // Reduced pool to 2 threads for control/text
    }

    public void start() {
        WifeLogger.log(TAG, "start() invoked. Changing isRunning to true and queueing background server tasks.");
        isRunning = true;
        
        executorService.execute(this::runControlServer);
        executorService.execute(this::runTextServer);
        
        Log.d(TAG, "Socket servers started successfully.");
        WifeLogger.log(TAG, "Background server threads queued successfully inside the thread pool.");
    }

    public void stop() {
        WifeLogger.log(TAG, "stop() invoked. Shutting down ServerSockets and terminating pool.");
        isRunning = false;
        try {
            if (controlSocket != null && !controlSocket.isClosed()) {
                WifeLogger.log(TAG, "Closing Control ServerSocket bound to port: " + Constants.OFF_PORT_CONTROL);
                controlSocket.close();
            }
        } catch (IOException e) {
            WifeLogger.log(TAG, "Error closing Control ServerSocket: " + e.getMessage(), e);
        }
        try {
            if (textSocket != null && !textSocket.isClosed()) {
                WifeLogger.log(TAG, "Closing Text ServerSocket bound to port: " + Constants.OFF_PORT_TEXT);
                textSocket.close();
            }
        } catch (IOException e) {
            WifeLogger.log(TAG, "Error closing Text ServerSocket: " + e.getMessage(), e);
        }
        
        executorService.shutdownNow();
        WifeLogger.log(TAG, "Socket server background thread pool terminated.");
    }

    private void runControlServer() {
        WifeLogger.log(TAG, "runControlServer() thread started. Initializing socket on port: " + Constants.OFF_PORT_CONTROL);
        try {
            controlSocket = new ServerSocket(Constants.OFF_PORT_CONTROL);
            Log.d(TAG, "Control server running on port " + Constants.OFF_PORT_CONTROL);
            WifeLogger.log(TAG, "Control server bound successfully. Awaiting incoming connections...");
            while (isRunning) {
                Socket client = controlSocket.accept();
                String remoteIp = client.getInetAddress() != null ? client.getInetAddress().getHostAddress() : "Unknown IP";
                WifeLogger.log(TAG, "Control socket connection accepted from client: " + remoteIp);
                connectionManager.updatePeerIpFromAccept(remoteIp);
                
                // Start control handler thread
                WifeLogger.log(TAG, "Spawning control handler MessageReceiver thread for: " + remoteIp);
                new Thread(new MessageReceiver(context, client, true)).start();
            }
        } catch (IOException e) {
            Log.d(TAG, "Control server closed or failed: " + e.getMessage());
            WifeLogger.log(TAG, "Control ServerSocket loop terminated or failed: " + e.getMessage(), e);
        }
    }

    private void runTextServer() {
        WifeLogger.log(TAG, "runTextServer() thread started. Initializing socket on port: " + Constants.OFF_PORT_TEXT);
        try {
            textSocket = new ServerSocket(Constants.OFF_PORT_TEXT);
            Log.d(TAG, "Text communication server running on port " + Constants.OFF_PORT_TEXT);
            WifeLogger.log(TAG, "Text server bound successfully. Awaiting incoming connections...");
            while (isRunning) {
                Socket client = textSocket.accept();
                String remoteIp = client.getInetAddress() != null ? client.getInetAddress().getHostAddress() : "Unknown IP";
                WifeLogger.log(TAG, "Text socket connection accepted from client: " + remoteIp);
                connectionManager.updatePeerIpFromAccept(remoteIp);

                // Start chat handler thread
                WifeLogger.log(TAG, "Spawning text handler MessageReceiver thread for: " + remoteIp);
                new Thread(new MessageReceiver(context, client, false)).start();
            }
        } catch (IOException e) {
            Log.d(TAG, "Text server closed or failed: " + e.getMessage());
            WifeLogger.log(TAG, "Text ServerSocket loop terminated or failed: " + e.getMessage(), e);
        }
    }
}