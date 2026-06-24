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

        private const val NOTIF_ID   = 11434
        private const val CHANNEL_ID = "OllamaChannel"
    }

    private val TAG = "OllamaService"
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
        val host    = intent?.getStringExtra("host")    ?: "127.0.0.1:11434"
        val origins = intent?.getStringExtra("origins") ?: "*"
        // API key forwarded from the ViewModel so the daemon can authenticate
        // with ollama.com when pulling or running cloud models.
        val apiKey  = intent?.getStringExtra("api_key") ?: ""

        logBuffer.clear()
        addLog("DevHive IDE service initializing...")

        startForeground(NOTIF_ID, buildNotification("Starting…", "Launching Ollama daemon"))

        activeProcess?.let { 
            android.util.Log.d(TAG, "    Stopping existing process...")
            ollamaExecutor.stopOllamaService(it); activeProcess = null 
        }
        
        android.util.Log.d(TAG, ">>> Starting Ollama service...")
        val proc = ollamaExecutor.startOllamaService(host, origins, apiKey) { line ->
            android.util.Log.d(TAG, "    [OLLAMA] $line")
            addLog(line)
        }

        if (proc != null) {
            android.util.Log.d(TAG, "    Ollama started successfully, pid: ${proc.pid}")
            isRunning     = true
            activeProcess = proc
            notifManager?.notify(NOTIF_ID, buildNotification(
                getString(R.string.notification_title),
                getString(R.string.notification_msg)
            ))
        } else {
            android.util.Log.e(TAG, "!!! startOllamaService returned null!")
            isRunning = false
            addLog("Failed to launch Ollama process — stopping service.")
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
        activeProcess?.let { 
            try {
                ollamaExecutor.stopOllamaService(it)
            } catch (e: Exception) {
                android.util.Log.e("OllamaService", "Error stopping process", e)
            }
            activeProcess = null
        }
        serviceInstance = null
        addLog("DevHive IDE service stopped.")
        super.onDestroy()
    }
}
