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

        val binary = File(filesDir, "bin/llama-server")
        if (!binary.exists()) {
            log("❌ llama-server binary not found. Please install it first.")
            stopSelf()
            return START_NOT_STICKY
        }

        Thread {
            try {
                val cmd = buildCommand(binary, modelPath, host, port, gpuLayers, ctxSize, threads, batch)
                val env = buildEnv()
                startWithFallbacks(binary, cmd, env)
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
        val binDir = java.io.File(filesDir, "bin").absolutePath
        val ldPath = listOf(
            binDir,                                      // all extracted .so libs live here
            applicationInfo.nativeLibraryDir,
            "/system/lib64",
            "/apex/com.android.runtime/lib64",
            "/apex/com.android.art/lib64",
            "/vendor/lib64"
        ).joinToString(":")
        return mapOf(
            "LD_LIBRARY_PATH"    to ldPath,
            // GGML backend plugin discovery path — required for CPU/GPU backends
            "GGML_BACKEND_PATH"  to binDir,
            "TMPDIR"             to cacheDir.absolutePath,
            "HOME"               to filesDir.absolutePath
        )
    }

    private fun startWithFallbacks(
        binary: File,
        cmd: List<String>,
        env: Map<String, String>
    ) {
        // ── Strategy 1: Direct execution ──────────────────────────────────────
        try {
            log("Starting llama-server…")
            runProcess(cmd, env)
            return
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (!msg.contains("error=13") && !msg.contains("Permission denied")) {
                log("❌ Failed: $msg")
                return
            }
            log("Direct exec denied (SELinux/noexec). Trying fallbacks…")
        }

        // ── Strategy 2: Copy to externalCacheDir ──────────────────────────────
        externalCacheDir?.let { extCache ->
            try {
                val extBin = File(extCache, "llama-server")
                binary.copyTo(extBin, overwrite = true)
                extBin.setExecutable(true, false)
                log("Trying external cache: ${extBin.absolutePath}")
                runProcess(listOf(extBin.absolutePath) + cmd.drop(1), env)
                return
            } catch (e: Exception) {
                log("External cache fallback failed: ${e.message}")
            }
        }

        // ── Strategy 3: Android dynamic linker64 ─────────────────────────────
        for (linker in listOf(
            "/system/bin/linker64",
            "/apex/com.android.runtime/bin/linker64"
        )) {
            if (File(linker).exists()) {
                try {
                    log("Trying linker64: $linker")
                    runProcess(listOf(linker) + cmd, env)
                    return
                } catch (e: Exception) {
                    log("linker64 fallback failed: ${e.message}")
                }
            }
        }

        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        log("❌ All execution strategies failed.")
        log("Device SELinux blocks binary execution from user storage.")
        log("• Try on a non-Knox / stock Android device")
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    private fun runProcess(cmd: List<String>, env: Map<String, String>) {
        val pb = ProcessBuilder(cmd)
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        serverProcess = proc
        proc.inputStream.bufferedReader().forEachLine { line -> log(line) }
        proc.waitFor()
    }

    private fun log(msg: String) {
        synchronized(logBuffer) {
            logBuffer.add(msg)
            if (logBuffer.size > 2000) logBuffer.removeAt(0)
        }
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
