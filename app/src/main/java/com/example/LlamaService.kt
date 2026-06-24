package com.example

import android.app.*
import android.content.Context
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

        val binary = File(applicationInfo.nativeLibraryDir, "libllama_server.so")
        if (!binary.exists()) {
            log("❌ libllama_server.so not found in native library directory.")
            stopSelf()
            return START_NOT_STICKY
        }

        Thread {
            try {
                val cmd = buildCommand(binary, modelPath, host, port, gpuLayers, ctxSize, threads, batch)
                val env = buildEnv()
                log("Starting llama-server: ${cmd.joinToString(" ")}")
                runProcess(cmd, env)
            } catch (e: Exception) {
                log("❌ ${e.message}")
            }
            isRunning = false
        }.start()

        return START_STICKY
    }

    private fun buildCommand(
        binary: File,
        modelPath: String,
        host: String,
        port: String,
        gpuLayers: Int,
        ctxSize: Int,
        threads: Int,
        batch: Int
    ) = listOf(
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

    private fun buildEnv(): Map<String, String> {
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val ldPath = listOf(
            nativeLibDir,
            "/system/lib64",
            "/apex/com.android.runtime/lib64",
            "/apex/com.android.art/lib64",
            "/vendor/lib64"
        ).joinToString(":")
        return mapOf(
            "LD_LIBRARY_PATH"    to ldPath,
            // GGML backend plugin discovery path — required for CPU/GPU backends
            "GGML_BACKEND_PATH"  to nativeLibDir,
            "TMPDIR"             to cacheDir.absolutePath,
            "HOME"               to filesDir.absolutePath
        )
    }

    private fun runProcess(cmd: List<String>, env: Map<String, String>) {
        android.util.Log.d("LlamaService", ">>> runProcess() called")
        android.util.Log.d("LlamaService", "    command: $cmd")
        
        val pb = ProcessBuilder(cmd)
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            android.util.Log.e("LlamaService", "!!! Process start failed: ${e.message}", e)
            log("!!! Process start failed: ${e.message}")
            throw e
        }
        
        serverProcess = proc
        android.util.Log.d("LlamaService", "    Process started")
        
        try {
            proc.inputStream.bufferedReader().forEachLine { line -> 
                android.util.Log.d("LlamaService", "    [OUT] $line")
                log(line) 
            }
            val exitCode = proc.waitFor()
            android.util.Log.d("LlamaService", "    Process exited with code: $exitCode")
            log("Process exited with code: $exitCode")
        } catch (e: Exception) {
            android.util.Log.e("LlamaService", "!!! Process error: ${e.message}", e)
            log("❌ Process error: ${e.message}")
        } finally {
            serverProcess = null
            isRunning = false
        }
    }

    private fun log(msg: String) {
        synchronized(logBuffer) {
            logBuffer.add(msg)
            if (logBuffer.size > 2000) logBuffer.removeAt(0)
        }
    }

    override fun onDestroy() {
        serverProcess?.let { proc ->
            try {
                proc.destroy()
                // Wait a bit for graceful shutdown
                Thread.sleep(500)
                if (proc.isAlive) {
                    proc.destroyForcibly()
                }
            } catch (e: Exception) {
                android.util.Log.e("LlamaService", "Error stopping process", e)
            }
            serverProcess = null
        }
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "llama.cpp Server", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
}
