package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.Collections

class OllamaService : Service() {
    companion object {
        var isRunning = false
        var activeProcess: Process? = null
        val logBuffer = Collections.synchronizedList(mutableListOf<String>())
        var onLogReceived: ((String) -> Unit)? = null
        var serviceInstance: OllamaService? = null

        private const val NOTIF_ID  = 11434
        private const val CHANNEL_ID = "OllamaChannel"
    }

    private lateinit var ollamaExecutor: OllamaExecutor
    private var notifManager: NotificationManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        ollamaExecutor  = OllamaExecutor(this)
        notifManager    = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host    = intent?.getStringExtra("host")     ?: "127.0.0.1:11434"
        val origins = intent?.getStringExtra("origins") ?: "*"

        logBuffer.clear()
        addLog("Ollama Devhive service initializing...")

        // Android requires startForeground() within 5 s — show "Starting" state
        startForeground(NOTIF_ID, buildNotification("Starting…", "Launching Ollama daemon"))

        // Stop any previous process
        activeProcess?.let { ollamaExecutor.stopOllamaService(it); activeProcess = null }

        val proc = ollamaExecutor.startOllamaService(host, origins) { line ->
            addLog(line)
        }

        if (proc != null) {
            isRunning     = true
            activeProcess = proc
            // Update notification to "Running"
            notifManager?.notify(NOTIF_ID, buildNotification(
                getString(R.string.notification_title),
                getString(R.string.notification_msg)
            ))
        } else {
            isRunning = false
            addLog("Failed to launch Ollama process — stopping service.")
            // Dismiss the notification immediately and stop the service
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        notifManager?.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setOngoing(true)
            .build()

    private fun addLog(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            synchronized(logBuffer) {
                logBuffer.add(trimmed)
                if (logBuffer.size > 1500) logBuffer.removeAt(0)
            }
            onLogReceived?.invoke(trimmed)
        }
    }

    override fun onDestroy() {
        isRunning = false
        activeProcess?.let { ollamaExecutor.stopOllamaService(it); activeProcess = null }
        serviceInstance = null
        addLog("Ollama Devhive service stopped.")
        super.onDestroy()
    }
}
