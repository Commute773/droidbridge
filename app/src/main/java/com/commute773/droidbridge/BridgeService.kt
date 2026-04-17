package com.commute773.droidbridge

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class BridgeService : Service() {
    companion object {
        private const val TAG = "BridgeService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "droidbridge_channel"
        const val PORT = 8765

        var instance: BridgeService? = null
            private set
    }

    private lateinit var bleManager: BleManager
    private var server: BridgeServer? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        bleManager = BleManager(this)
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startServer()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DroidBridge",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "BLE Bridge Server"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DroidBridge")
            .setContentText("BLE server running on port $PORT")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startServer() {
        if (server == null) {
            server = BridgeServer(applicationContext, bleManager, PORT)
            server?.start()
            Log.i(TAG, "Server started on port $PORT")
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        bleManager.disconnectAll()
        Log.i(TAG, "Server stopped")
    }

    override fun onDestroy() {
        stopServer()
        instance = null
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun getServerInfo(): String {
        return "Port: $PORT, Connections: ${bleManager.getConnectedDevices().size}"
    }
}
