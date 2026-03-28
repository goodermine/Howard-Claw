# Howard-Claw: Technical Architecture

## Overview

Howard is a native Android application built with Kotlin and Jetpack Compose. It combines local LLM inference via llama.cpp (NDK/JNI), cloud LLM access via OkHttp, and an agent tool dispatch system.

```
┌─────────────────────────────────────────────────┐
│                 ANDROID DEVICE                   │
│                                                  │
│  ┌────────────────────────────────────────────┐ │
│  │              Howard APK                     │ │
│  │                                             │ │
│  │  ┌─────────┐  ┌──────────┐  ┌───────────┐ │ │
│  │  │ Chat UI │  │Onboarding│  │ Settings  │ │ │
│  │  │(Compose)│  │  Flow    │  │  Screens  │ │ │
│  │  └────┬────┘  └──────────┘  └───────────┘ │ │
│  │       │                                     │ │
│  │  ┌────┴────────────────────────────────┐   │ │
│  │  │         ChatViewModel               │   │ │
│  │  │  ┌─────────────┐ ┌──────────────┐  │   │ │
│  │  │  │PromptBuilder│ │  CMD Parser  │  │   │ │
│  │  │  └─────────────┘ └──────┬───────┘  │   │ │
│  │  └─────────────┬───────────┘           │   │ │
│  │                │                        │   │ │
│  │  ┌─────────────┴──────────────────┐    │   │ │
│  │  │        EngineRouter            │    │   │ │
│  │  │  ┌───────────┐ ┌────────────┐ │    │   │ │
│  │  │  │LocalEngine│ │CloudEngine │ │    │   │ │
│  │  │  │(llama.cpp)│ │  (OkHttp)  │ │    │   │ │
│  │  │  └─────┬─────┘ └─────┬──────┘ │    │   │ │
│  │  └────────┼──────────────┼────────┘    │   │ │
│  │           │              │              │   │ │
│  │     ┌─────┴─────┐  ┌────┴───────┐     │   │ │
│  │     │ JNI Bridge│  │ Cloud APIs │     │   │ │
│  │     │libllama.so│  │ (OpenAI,   │     │   │ │
│  │     └───────────┘  │ Anthropic, │     │   │ │
│  │                     │ Gemini...) │     │   │ │
│  │                     └────────────┘     │   │ │
│  │                                         │   │ │
│  │  ┌─────────────────────────────────┐   │   │ │
│  │  │      CommandDispatcher          │   │   │ │
│  │  │  ┌────────────────────────────┐ │   │   │ │
│  │  │  │      ToolExecutor          │ │   │   │ │
│  │  │  │ github_sync | file_ops    │ │   │   │ │
│  │  │  │ telegram_send | web_gen   │ │   │   │ │
│  │  │  └────────────────────────────┘ │   │   │ │
│  │  └─────────────────────────────────┘   │   │ │
│  │                                         │   │ │
│  │  ┌──────────┐  ┌───────────────────┐   │   │ │
│  │  │ Room DB  │  │ EncryptedPrefs    │   │   │ │
│  │  │(messages,│  │(API keys, tokens, │   │   │ │
│  │  │ tasks)   │  │ config)           │   │   │ │
│  │  └──────────┘  └───────────────────┘   │   │ │
│  └────────────────────────────────────────────┘ │
│                                                  │
│  ┌────────────────────────────────────────────┐ │
│  │  OpenClaw Gateway (Phase 4, optional)       │ │
│  │  Node.js ARM64 ForegroundService            │ │
│  │  localhost:18789 (HTTP + WebSocket)          │ │
│  └────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────┘
```

## Component Details

### Inference Engines

**LocalEngine** — llama.cpp compiled via Android NDK into `libllama.so`. JNI bridge (`howard_jni.cpp`) exposes:
- `loadModel(path: String, threads: Int, contextSize: Int)`
- `runInference(prompt: String, systemPrompt: String, callback: TokenCallback)`
- `stopInference()`
- `freeModel()`

CPU-only inference. No GPU offloading available on Android NDK as of early 2026. Expected performance: 3–8 tokens/sec for Q4_K_M models on Snapdragon 8 Gen 2/3.

**CloudEngine** — OkHttp SSE streaming against OpenAI-compatible `/chat/completions` endpoints. Supports:

| Provider | Base URL | Auth |
|---|---|---|
| OpenAI | api.openai.com/v1 | Bearer token |
| Anthropic | api.anthropic.com/v1 | x-api-key header |
| Gemini | generativelanguage.googleapis.com/v1beta/openai | Bearer token |
| OpenRouter | openrouter.ai/api/v1 | Bearer token |

**EngineRouter** — holds one active engine. User switches between local and cloud via UI chips. Hot-swappable.

### Agent System

The agent system uses a simple token-based command protocol:

1. `SystemPrompts.kt` defines the base prompt including the tool grammar
2. `PromptBuilder.kt` constructs the full prompt with conversation history
3. LLM output is streamed token-by-token
4. `ChatViewModel` regex-matches `[CMD: tool_name args]` patterns mid-stream
5. `CommandDispatcher` routes matched commands to `ToolExecutor`
6. Tool results are injected back as `role = "tool"` messages

**Available tools:**

| Tool | Purpose | Android Constraints |
|---|---|---|
| github_sync | Clone or pull a git repo | Requires git binary or JGit library |
| file_organizer | Sort files by extension | Limited to app-accessible directories (scoped storage) |
| web_component_gen | Scaffold a React component | Writes to app-internal storage only |
| telegram_send | Send a Telegram message | Requires bot token configured |
| shell | Execute a bash command | **Severely limited** on Android — sandboxed, no system access |

### Data Layer

- **Room database** (`HowardDatabase`): tables for `messages`, `task_history`, `model_registry`
- **EncryptedSharedPreferences** (`SecurePrefs`): AES256-GCM storage for API keys, bot tokens, and configuration. Keys never leave the device.
- **Model files**: stored in `filesDir/models/`. Downloaded via `ModelDownloader` with HTTP range-request resume support.

### Services

- **InferenceService**: ForegroundService that keeps the LLM engine alive during background operation. Handles Telegram polling and task dispatch.
- **GatewayService** (Phase 4): ForegroundService that manages the OpenClaw Node.js child process. Extracts bundled assets on first launch, spawns Node, monitors health, auto-restarts on crash.
- **BootReceiver**: BroadcastReceiver that restarts services after device reboot.

### OpenClaw Integration (Phase 4)

OpenClaw is an optional enhancement, not a core dependency for MVP:

- Node.js ARM64 binary (~50MB) + OpenClaw npm package (~60-80MB) bundled in APK assets
- Extracted to `filesDir/runtime/` on first launch (marker file prevents re-extraction)
- Runs as a child process of `GatewayService` via `ProcessBuilder`
- Listens on `localhost:18789` (HTTP + WebSocket)
- Howard communicates via `OpenClawConnector` for advanced agent workflows
- Config stored at `filesDir/.openclaw/openclaw.json`

**Architectural decision:** Howard's own inference engines and agent dispatch work independently of OpenClaw. OpenClaw adds a richer agent framework for power users but is not required for basic chat + tools functionality.

## Technology Stack

| Component | Technology | Version |
|---|---|---|
| Language | Kotlin | 2.0.x |
| UI | Jetpack Compose | BOM 2024.12.01 |
| Database | Room | 2.6.1 |
| Background work | WorkManager | 2.10.0 |
| Secrets | Security-Crypto | 1.1.0-alpha06 |
| HTTP | OkHttp + Retrofit | 4.12.0 / 2.11.0 |
| Async | Kotlin Coroutines | 1.9.0 |
| Navigation | Navigation Compose | 2.8.5 |
| Native build | NDK + CMake | 3.22.1 |
| Local inference | llama.cpp | Latest (git submodule) |

## Package Structure

```
au.howardagent/
├── HowardApplication.kt          # Application class, singleton refs
├── MainActivity.kt                # NavHost, navigation graph
├── agent/
│   ├── CommandDispatcher.kt       # CMD token routing
│   ├── PromptBuilder.kt           # Prompt construction
│   ├── SystemPrompts.kt           # Base prompts and tool grammar
│   └── ToolExecutor.kt            # Tool implementations
├── connectors/
│   ├── OpenClawConnector.kt       # HTTP/WS to local OpenClaw gateway
│   └── TelegramConnector.kt       # Telegram Bot API client
├── data/
│   ├── HowardDatabase.kt          # Room DB + DAOs
│   └── SecurePrefs.kt             # EncryptedSharedPreferences wrapper
├── download/
│   ├── DeviceDetector.kt          # RAM/SoC detection for model filtering
│   ├── ModelDownloader.kt         # HTTP download with resume
│   └── ModelRegistry.kt           # Available models catalog
├── engine/
│   ├── CloudEngine.kt             # Cloud LLM SSE client
│   ├── EngineRouter.kt            # Engine switching logic
│   ├── InferenceEngine.kt         # Engine interface
│   └── LocalEngine.kt             # llama.cpp JNI wrapper
├── service/
│   ├── BootReceiver.kt            # Restart on device boot
│   ├── GatewayService.kt          # OpenClaw Node.js process manager
│   └── InferenceService.kt        # Foreground inference + Telegram polling
└── ui/
    ├── chat/
    │   ├── ChatScreen.kt          # Chat UI
    │   └── ChatViewModel.kt       # Chat state management
    ├── onboarding/
    │   ├── OnboardingScreen.kt    # Onboarding container
    │   ├── NoticeStep.kt          # Privacy notice
    │   ├── Step1_ModelDownload.kt # Model selection + download
    │   ├── Step2_CloudKeys.kt     # API key entry
    │   ├── Step3_OpenClaw.kt      # Gateway setup
    │   └── Step4_Telegram.kt      # Bot configuration
    ├── settings/
    │   └── SettingsScreen.kt      # Settings hub
    ├── tools/
    │   └── ToolsScreen.kt         # Tool dashboard
    └── theme/
        ├── Theme.kt               # Colors and theme
        └── Type.kt                # Typography
```
