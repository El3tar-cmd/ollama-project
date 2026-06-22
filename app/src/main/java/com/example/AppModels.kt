package com.example

enum class ShellLineType { COMMAND, OUTPUT, ERROR, INFO }
data class ShellLine(val text: String, val type: ShellLineType = ShellLineType.OUTPUT)
