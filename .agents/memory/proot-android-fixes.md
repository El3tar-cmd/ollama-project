---
name: PRoot Android critical fixes
description: Required fixes for PRoot/Alpine to work on Android with Python, cd, and agent stability
---

## Rule
Any PRoot command on Android MUST include `-k 4.9.0` kernel spoof flag. Without it, musl/Python call `getrandom()`/`fchdir()` syscalls unavailable on Android kernel, causing "Function not implemented".

**Why:** Android kernel version exposed to PRoot triggers syscall paths in musl that Android doesn't implement. Spoofing 4.9 makes musl/Python use safe legacy paths.

**How to apply:** In `buildProotCommand()`, always include `"-k", "4.9.0"` in the PRoot arg list.

## Python env vars required in exec()
Always set `PYTHONHASHSEED=0`, `PYTHONDONTWRITEBYTECODE=1`, `PYTHONNOUSERSITE=1` in the PRoot environment. These prevent Python from calling newer Linux syscalls.

## BashTool infrastructure error detection
"Function not implemented" must NOT be in `isProotInfrastructureError()`. It's also emitted by child processes (Python, etc.) — treating it as a PRoot infra error causes silent fallback to Android shell (no Python).

## Alpine terminal cd tracking
Each PRoot invocation is a fresh shell — `cd` state does not persist. Solution: track `alpineCwd` in ViewModel, handle `cd` commands natively (update the variable), prepend `cd <cwd> &&` to all subsequent commands.

## Alpine package manager
Alpine uses `apk add --no-cache`, NOT `apt-get`. Using apt-get silently fails.

## Agent regex crash (PatternSyntaxException)
LLM output containing shell fork bombs (`:(){:|:&};:`) or other regex metacharacters can crash `Regex()` calls. Wrap all regex operations on LLM-generated content in try-catch blocks that catch `PatternSyntaxException` (and generic `Exception`).
