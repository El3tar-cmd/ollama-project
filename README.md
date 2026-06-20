# Ollama Devhive — Android LLM Server

Run a local Ollama LLM daemon directly on your Android device. Built with Kotlin & Jetpack Compose.

## Features
- 🚀 Run Ollama serve as a background Android service
- 🤖 AI Agent with file read/write and shell command execution
- 💬 Chat with local models
- 📦 Pull, delete and manage models
- 🔒 100% on-device — no cloud required

## Build Locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio)

1. Open Android Studio
2. Select **Open** and choose this directory
3. Connect an Android device (Android 7+, ARM64)
4. Run → **Run 'app'**

## GitHub Actions Build

Push to `main` → APK is built automatically via `.github/workflows/build-apk.yml`
