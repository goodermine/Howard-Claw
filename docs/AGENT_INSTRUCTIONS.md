# Howard APK — Build & Install Guide

## Repository
```
https://github.com/goodermine/Howard-Claw
Branch: claude/audit-android-openclaw-OjMw0
```

## If building from source

**Prerequisites:** Java 17+, Android SDK (platform 35, build-tools 34+, NDK r26b), CMake 3.22+, Node.js 22+, npm

```bash
# Clone
git clone -b claude/audit-android-openclaw-OjMw0 https://github.com/goodermine/Howard-Claw
cd Howard-Claw

# 1. Init submodules + debug keystore
./scripts/init_project.sh

# 2. Bundle Node.js ARM64 + OpenClaw into assets (~248MB)
./scripts/bundle_assets.sh

# 3. Set SDK path
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 4. Build
./gradlew assembleDebug

# APK output: app/build/outputs/apk/debug/app-debug.apk (~224MB)
```

## If APK is already installed on the REDMAGIC 10S Pro

- **Package:** `au.howardagent`
- **App name:** Howard
- **Data dir:** `/data/data/au.howardagent/`
  - `files/models/` — downloaded GGUF models
  - `files/runtime/node/node` — Node.js binary (extracted on first launch)
  - `files/runtime/openclaw/` — OpenClaw package (extracted on first launch)
  - `files/.openclaw/openclaw.json` — gateway config (port 18789)
- **Services:**
  - `InferenceService` — foreground service for local LLM inference + Telegram polling
  - `GatewayService` — foreground service running Node.js + OpenClaw on `127.0.0.1:18789`
- **Encrypted prefs:** API keys stored via `EncryptedSharedPreferences` (AES256-GCM)

## Key architecture points

| Layer | Detail |
|-------|--------|
| Local inference | llama.cpp via JNI (`libhoward_jni.so`), CPU-only, arm64-v8a |
| Cloud fallback | OpenAI, Anthropic, Gemini, OpenRouter (free tier), Ollama (self-hosted) |
| Tool dispatch | `[CMD: tool_name args]` tokens parsed from LLM output |
| Available tools | `github_sync`, `file_organizer`, `web_component_gen`, `telegram_send` |
| Gateway | Node.js 22 ARM64 + OpenClaw npm package, auto-extracted from APK assets |
| Telegram | Long-poll `getUpdates`, bot token + channel ID in encrypted prefs |

## Downloadable models (all verified)

| Model | Size | RAM needed |
|-------|------|------------|
| Qwen3 0.6B Q4_K_M | 462MB | 1GB |
| Qwen3 1.7B Q4_K_M | 1.2GB | 2GB |
| Gemma 2 2B Q4_K_M | 1.6GB | 2.5GB |
| SmolLM3 3B Q4_K_M | 1.8GB | 3GB |
| Qwen3 4B Q4_K_M | 2.4GB | 3.5GB |
| Mistral 7B Q4_K_M | 4.2GB | 5GB |
| Llama 3.1 8B Q4_K_M | 4.7GB | 5.5GB |

All models download from HuggingFace (bartowski GGUF repos). The REDMAGIC 10S Pro (24GB RAM) can run all of them.
