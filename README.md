# DevHive IDE — Android LLM Server

Run Ollama and llama.cpp locally on Android. Built with Kotlin & Jetpack Compose.

## Features
- 🚀 Run Ollama daemon or llama.cpp server as a foreground Android service
- 🤖 AI Agent with file read/write, shell execution, web fetch, and memory tools
- 💬 Chat with local models (Ollama or GGUF via llama.cpp)
- 📦 Pull, delete and manage Ollama models; download GGUF from HuggingFace
- 🔒 100% on-device — no cloud required

## Architecture

```
com.example/
├── MainActivity.kt          # Entry point — setContent { MainAppScreen() }
├── MainViewModel.kt         # Shared app state (stays in root package)
├── OllamaExecutor.kt        # Binary download/run
├── OllamaService.kt         # Foreground service (Ollama daemon)
├── OllamaAuth.kt            # Cloud auth / API key
├── LlamaCppServer.kt        # llama.cpp server wrapper + GGUFModel
├── LlamaService.kt          # Foreground service (llama.cpp)
├── Ed25519.kt               # Crypto helpers
│
├── data/
│   ├── model/
│   │   └── AppModels.kt     # OllamaModel, ChatMessage, AgentStep,
│   │                        # ShellLine/Type, ChatSegment, MdSegment, AppTab
│   └── api/
│       ├── OllamaApi.kt     # Ollama HTTP client
│       └── LlamaCppApi.kt   # llama.cpp HTTP client
│
├── agent/
│   ├── AgentEngine.kt       # ReAct loop orchestrator
│   ├── AgentPrompt.kt       # System prompts
│   └── tools/
│       ├── FileTools.kt     # read_file, write_file, list_files, …
│       ├── BashTool.kt      # run_bash
│       ├── WebTool.kt       # web_fetch
│       ├── SearchTools.kt   # web_search, grep_files
│       ├── MemoryTool.kt    # memory_note
│       └── ToolExecutor.kt  # Tool call dispatcher
│
└── ui/
    ├── theme/               # Color.kt, Theme.kt, Type.kt
    ├── components/
    │   └── CommonComponents.kt   # StatusPill, SectionCard, OllamaTextField
    ├── editor/
    │   └── CodeEditor.kt    # EnhancedCodeEditor + syntax helpers
    ├── home/
    │   └── HomeScreen.kt    # MainAppScreen + bottom nav
    ├── server/
    │   └── ServerScreen.kt  # Daemon control panel
    ├── models/
    │   └── ModelsScreen.kt  # Model management (Ollama + GGUF/HF)
    ├── chat/
    │   ├── ChatScreen.kt    # Chat UI + ChatMessageBubble
    │   └── MarkdownViewer.kt # Inline markdown renderer
    ├── agent/
    │   ├── AgentScreen.kt    # Root agent view + FolderPickerDialog
    │   ├── AgentChatPane.kt  # Chat pane + AgentStepBubble
    │   ├── AgentFilesPane.kt # File browser + multi-tab editor
    │   └── AgentStepsPane.kt # Raw step log
    └── terminal/
        └── TerminalScreen.kt # Interactive shell + daemon logs
```

## Backends

| Backend   | API endpoint              | Notes                       |
|-----------|---------------------------|-----------------------------|
| Ollama    | `http://127.0.0.1:11434`  | Full model management       |
| llama.cpp | `http://127.0.0.1:<port>` | GGUF only, GPU via Vulkan   |

## Build

**Prerequisites:** Android Studio, device with ARM64 (Android 7+)

1. Open this directory in Android Studio
2. Connect your device
3. Run → **Run 'app'**

A GitHub Actions workflow (`.github/workflows/build-apk.yml`) builds a debug APK on every push to `main`.

## User preferences

(empty — add persistent prefs here)
