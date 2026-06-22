# Comprehensive Refactor — Professional Package Structure

## What & Why

Split the monolithic `MainActivity.kt` (~3500 lines) and `AgentEngine.kt` (~800 lines) into a clean, professional multi-file architecture. Currently all UI screens, ViewModels, data models, API clients, and agent tools live in 2–3 giant files, making the codebase unmaintainable. The goal is a clear separation between backend (data/API/agent) and frontend (UI screens/components), with every logical concern in its own file.

## Done looks like

- `MainActivity.kt` is ≤ 80 lines (entry point + nav only)
- `MainViewModel.kt` holds all shared app state (extracted from MainActivity)
- `data/` package has all models and API clients
- `agent/` package has AgentEngine + system prompt + tools each in their own file
- `ui/` package has each screen in its own file under a named sub-package
- `ui/components/` holds reusable composables shared across screens
- `README.md` is updated with the new structure diagram and feature list
- App compiles and runs identically to before — no regressions

## Out of scope

- Adding new features (this is refactor-only)
- Changing build.gradle or adding Gradle sub-modules
- Changing any business logic or UI behavior

## Steps

1. **Create `data/model/AppModels.kt`** — Move all data classes out of MainActivity.kt: `ChatMessage`, `OllamaModel`, `AgentStep`, `ShellLine`, `AppTab`, `ChatSegment`, `MdSegment`, `CodeSegment`.

2. **Move API clients to `data/api/`** — Move `OllamaApi.kt` and `LlamaCppApi.kt` into `data/api/` sub-package (update import in MainActivity and AgentEngine).

3. **Extract `MainViewModel.kt`** — Pull the entire `MainViewModel` class (and its helpers) out of `MainActivity.kt` into its own top-level file in `com.example`.

4. **Split AgentEngine tools into `agent/tools/`** — Create individual tool files:
   - `FileTools.kt` — list_dir, read_file, read_lines, write_file, edit_file, delete_file, move_file
   - `BashTool.kt` — bash, calculate
   - `WebTool.kt` — fetch_url, web_search
   - `SearchTools.kt` — search_files, grep
   - `MemoryTool.kt` — memory_save, memory_recall
   - `ToolExecutor.kt` — the `executeTool()` dispatcher that delegates to the above

5. **Extract `agent/AgentPrompt.kt`** — Move the `SYSTEM_PROMPT` constant and `parseToolCalls()` function into their own file to keep AgentEngine.kt focused on the loop only.

6. **Slim down `AgentEngine.kt`** — After extracting tools and prompt, AgentEngine should only contain `runAgentLoop()` and the minimal state needed to coordinate tools.

7. **Create `ui/components/`** — Extract shared composables from MainActivity.kt:
   - `StatusPill.kt` — the server status pill
   - `SectionCard.kt` — the card wrapper
   - `CommonComponents.kt` — `OllamaTextField`, `AppTab` nav items, loading indicators

8. **Create `ui/editor/CodeEditor.kt`** — Move `EnhancedCodeEditor` and `getLanguageFromExtension`/`getFileIcon` helpers here.

9. **Create `ui/chat/MarkdownViewer.kt`** — Move `MarkdownViewer`, `ChatMessageBubble`, and markdown parsing logic here.

10. **Create each screen in its own file**:
    - `ui/home/HomeScreen.kt` — `MainAppScreen` + navigation scaffold + `BottomNavBar`
    - `ui/server/ServerScreen.kt` — `ServerScreen` composable
    - `ui/models/ModelsScreen.kt` — `ModelsScreen` composable
    - `ui/chat/ChatScreen.kt` — `ChatScreen` composable
    - `ui/agent/AgentScreen.kt` — `AgentScreen` composable
    - `ui/agent/AgentChatPane.kt` — `AgentChatPane` composable
    - `ui/agent/AgentFilesPane.kt` — `AgentFilesPane` composable
    - `ui/agent/AgentStepsPane.kt` — `AgentStepsPane` composable
    - `ui/terminal/TerminalScreen.kt` — `TerminalScreen` composable

11. **Slim `MainActivity.kt` to entry point** — After all extractions, MainActivity.kt should only contain the `MainActivity` class that calls `setContent { MainAppScreen(vm) }`.

12. **Update `README.md`** — Rewrite with project overview, feature list, architecture diagram (ASCII), new package structure, build instructions, and contribution guide.

13. **Verify compilation** — Run a full build to confirm zero errors after the restructuring. Fix any import or visibility issues.

## Relevant files

- `app/src/main/java/com/example/MainActivity.kt`
- `app/src/main/java/com/example/AgentEngine.kt`
- `app/src/main/java/com/example/LlamaCppApi.kt`
- `app/src/main/java/com/example/OllamaApi.kt`
- `app/src/main/java/com/example/OllamaService.kt`
- `app/src/main/java/com/example/LlamaService.kt`
- `app/src/main/java/com/example/ui/theme/Color.kt`
