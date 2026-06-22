---
name: DevHive IDE refactor approach
description: Key decisions made during the MainActivity.kt split into multiple files
---

# DevHive IDE Refactor Decisions

## Package strategy: flat `package com.example` for ALL files

**Rule:** Every new Kotlin file in this project uses `package com.example` regardless of its physical subdirectory location.

**Why:** Kotlin allows package names to differ from directory structure. Using the same flat package means all classes/functions are in scope without any `import` statements between files. This eliminates "unresolved reference" compile errors that occur when splitting a monolithic file.

**How to apply:** Any new file added to `app/src/main/java/com/example/` must start with `package com.example` — never use sub-packages like `package com.example.ui` or `package com.example.agent`.

## File split after refactor (MainActivity.kt: 3526 lines → 14 files)

- `MainActivity.kt` — Activity + MainAppScreen + AppTab enum + SignInDialog (216 lines)
- `MainViewModel.kt` — All ViewModel logic (741 lines)
- `AppModels.kt` — ShellLine, ShellLineType
- `CommonComponents.kt` — StatusPill, SectionCard, OllamaTextField
- `CodeEditor.kt` — EnhancedCodeEditor + getFileIcon(File) + getLanguageFromExtension
- `TerminalScreen.kt` — TerminalScreen + formatFileSize
- `ServerScreen.kt` — Server management UI
- `ModelsScreen.kt` — Model management UI
- `ChatScreen.kt` — ChatScreen + ChatSegment + parseChatContent + ChatMessageBubble
- `MarkdownViewer.kt` — MdSegment + parseMd + inlineMd + MarkdownViewer
- `AgentScreen.kt` — AgentScreen + FolderPickerDialog
- `AgentChatPane.kt` — AgentChatPane + AgentStepBubble
- `AgentFilesPane.kt` — AgentFilesPane (tab editor + file tree + dialogs)
- `AgentStepsPane.kt` — AgentStepsPane
- `AgentEngine.kt` — Kept as-is (783 lines, self-contained)

## getFileIcon is NOT @Composable

`getFileIcon(file: File): String` in CodeEditor.kt is a pure function (no @Composable). Called from within composable bodies to get emoji icons.
