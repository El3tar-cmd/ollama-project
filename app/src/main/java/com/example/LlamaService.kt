package com.example

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File

class LlamaService : Service() {

    companion object {
        const val CHANNEL_ID   = "llama_service_channel"
        const val NOTIFICATION_ID = 2
        const val ACTION_STOP  = "com.example.LLAMA_STOP"
        var isRunning = false
        val logBuffer = mutableListOf<String>()
    }

    private var serverProcess: Process? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }

        val modelPath = intent?.getStringExtra("model_path") ?: run { stopSelf(); return START_NOT_STICKY }
        val host      = intent.getStringExtra("host") ?: "127.0.0.1"
        val port      = intent.getStringExtra("port") ?: "8080"
        val gpuLayers = intent.getIntExtra("gpu_layers", 99)
        val ctxSize   = intent.getIntExtra("ctx_size", 4096)
        val threads   = intent.getIntExtra("threads", 4)
        val batch     = intent.getIntExtra("batch_size", 512)

        val stopIntent = Intent(this, LlamaService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("llama.cpp server")
            .setContentText("Serving ${File(modelPath).name} on :$port")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notif)
        isRunning = true

        val binary = File(filesDir, "bin/llama-server")
        if (!binary.exists()) { stopSelf(); return START_NOT_STICKY }

        Thread {
            try {
                val cmd = listOf(
                    binary.absolutePath,
                    "--model", modelPath,
                    "--host", host,
                    "--port", port,
                    "-ngl", gpuLayers.toString(),
                    "-c", ctxSize.toString(),
                    "-t", threads.toString(),
                    "-b", batch.toString(),
                    "--chat-template", "chatml"
                )
                val pb = ProcessBuilder(cmd)
                pb.environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
                pb.redirectErrorStream(true)
                val proc = pb.start()
                serverProcess = proc
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(logBuffer) {
                        logBuffer.add(line)
                        if (logBuffer.size > 2000) logBuffer.removeAt(0)
                    }
                }
                proc.waitFor()
            } catch (e: Exception) {
                synchronized(logBuffer) { logBuffer.add("❌ ${e.message}") }
            }
            isRunning = false
        }.start()

        return START_STICKY
    }

    override fun onDestroy() {
        serverProcess?.destroyForcibly()
        serverProcess = null
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "llama.cpp Server", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
}
