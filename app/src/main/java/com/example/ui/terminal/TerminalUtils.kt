package com.example.ui.terminal

import java.io.File
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

fun formatFileSize(file: File): String {
    if (!file.exists()) return "0 B"
    val bytes = file.length()
    if (bytes < 1024) return "$bytes B"
    
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = bytes.toDouble()
    var unitIndex = 0
    
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }
    
    val df = DecimalFormat("#.##", DecimalFormatSymbols(Locale.US))
    return "${df.format(size)} ${units[unitIndex]}"
}
