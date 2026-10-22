package net.hawyn.vivootahelper

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Runs from the resumed UI (automatic inspection or explicit action); never resumes writes. */
class OperationService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false
    private val repository get() = (application as SlotApplication).repository

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout") // Kept for the full transaction, including its rollback; released in finally.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getStringExtra("token")
        val command = repository.takeCommand(token)
        if (command == null) {
            if (!running) stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "槽位操作", NotificationManager.IMPORTANCE_LOW))
            val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("origionos7lk升级助手正在处理")
                .setContentText("备份、修补或校验进行中，请保持设备供电")
                .setContentIntent(openApp).setOngoing(true).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, notification)
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SlotAssistant:transaction").also { it.acquire() }
            running = true
            executor.execute {
                try { repository.execute(command) { wakeLock?.isHeld == true } }
                finally {
                    Handler(Looper.getMainLooper()).post {
                        wakeLock?.let { if (it.isHeld) it.release() }
                        wakeLock = null
                        running = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                        repository.executionFinished()
                    }
                }
            }
        } catch (error: Exception) {
            wakeLock?.let { if (it.isHeld) it.release() }
            repository.startFailed(error)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // shutdown() allows an already running root transaction and rollback to finish.
        executor.shutdown()
        super.onDestroy()
    }

    companion object { private const val CHANNEL = "slot_operations" }
}
