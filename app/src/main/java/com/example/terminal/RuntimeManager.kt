package com.example.terminal

import android.content.Context
import java.io.File

object RuntimeManager {

    fun findPython(context: Context): String? = TermuxBridge.findPython(context)
    fun findNode(context: Context): String?   = TermuxBridge.findNode(context)

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

    fun baseEnv(context: Context, binPath: String? = null): Map<String, String> =
        TermuxBridge.buildTermuxEnv(context, binPath?.let { File(it).parent })

    fun isTermuxReady(context: Context): Boolean =
        TermuxBridge.isTermuxInstalled(context) && TermuxBridge.isTermuxAccessible()
}
