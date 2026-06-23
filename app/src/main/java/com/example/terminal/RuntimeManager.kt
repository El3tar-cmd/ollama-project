package com.example.terminal

import android.content.Context
import java.io.File

object RuntimeManager {

    private val PYTHON_CANDIDATES = listOf(
        "/data/data/com.termux/files/usr/bin/python3",
        "/data/data/com.termux/files/usr/bin/python",
        "/system/bin/python3",
        "/system/usr/bin/python3",
        "/usr/bin/python3",
        "/usr/local/bin/python3"
    )

    private val NODE_CANDIDATES = listOf(
        "/data/data/com.termux/files/usr/bin/node",
        "/system/bin/node",
        "/system/usr/bin/node",
        "/usr/bin/node",
        "/usr/local/bin/node"
    )

    fun findPython(context: Context): String? {
        val downloaded = File(context.filesDir, "runtimes/bin/python3")
        if (downloaded.exists() && downloaded.canExecute()) return downloaded.absolutePath
        return PYTHON_CANDIDATES.firstOrNull { File(it).exists() }
    }

    fun findNode(context: Context): String? {
        val downloaded = File(context.filesDir, "runtimes/bin/node")
        if (downloaded.exists() && downloaded.canExecute()) return downloaded.absolutePath
        return NODE_CANDIDATES.firstOrNull { File(it).exists() }
    }

    fun pythonSource(context: Context): String = when {
        findPython(context) == null -> "not installed"
        findPython(context)!!.contains("termux", ignoreCase = true) -> "Termux"
        findPython(context)!!.startsWith(context.filesDir.absolutePath) -> "downloaded"
        else -> "system"
    }

    fun nodeSource(context: Context): String = when {
        findNode(context) == null -> "not installed"
        findNode(context)!!.contains("termux", ignoreCase = true) -> "Termux"
        findNode(context)!!.startsWith(context.filesDir.absolutePath) -> "downloaded"
        else -> "system"
    }

    fun baseEnv(context: Context, binPath: String? = null): Map<String, String> = buildMap {
        put("HOME", System.getenv("HOME") ?: context.filesDir.absolutePath)
        put("TMPDIR", context.cacheDir.absolutePath)
        val termuxBin = "/data/data/com.termux/files/usr/bin"
        val extraBin = binPath?.let { File(it).parent }
        put("PATH", listOfNotNull(termuxBin, extraBin, "/system/bin", "/system/usr/bin", "/usr/bin")
            .distinct().joinToString(":"))
        put("LD_LIBRARY_PATH", listOfNotNull(
            context.applicationInfo.nativeLibraryDir,
            if (File("/data/data/com.termux/files/usr/lib").exists())
                "/data/data/com.termux/files/usr/lib" else null
        ).joinToString(":"))
    }
}
