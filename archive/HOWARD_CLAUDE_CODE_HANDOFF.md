# HOWARD ANDROID — CLAUDE CODE HANDOFF DOCUMENT
**Project:** Howard Mobile Agent APK  
**Package:** `au.howardagent`  
**Status:** Architecture complete, all source files generated, ready for Android Studio build  
**Handoff date:** March 2026  

---

## WHAT YOU ARE BUILDING

Howard is a native Android APK that combines four capabilities no single Play Store app currently has together:

1. **Local LLM inference** — llama.cpp compiled via Android NDK into `libllama.so`. No Termux, no Python, no cloud required. Runs `.gguf` models directly on ARM64 hardware.
2. **OpenClaw gateway** — bundled Node.js ARM64 binary + OpenClaw npm package extracted from APK assets on first launch. Runs as a `ForegroundService`. No separate install needed by the user.
3. **Agent tools** — `[CMD: tool_name args]` tokens parsed from LLM output in real time and dispatched to bash tool scripts (github_sync, file_organizer, web_component_gen, shell passthrough).
4. **Telegram inbound/outbound** — bot token polling for receiving tasks, `sendMessage` for replies. All config stored in `EncryptedSharedPreferences`.

**Target users:** Play Store purchase. Non-technical users who want an always-on AI agent on their phone. Onboarding must be zero-friction.

---

## REPOSITORY STRUCTURE

```
howard-android/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/
│       │   ├── CMakeLists.txt
│       │   ├── howard_jni.cpp
│       │   └── llama.cpp/              ← git submodule
│       ├── assets/
│       │   ├── node/node               ← Node.js ARM64 binary (see bundle script)
│       │   └── openclaw.tar.gz         ← OpenClaw npm package (see bundle script)
│       ├── res/
│       │   ├── values/strings.xml
│       │   ├── drawable/ic_howard_notif.xml
│       │   └── font/                   ← Outfit, Source Sans 3, JetBrains Mono
│       └── java/au/howardagent/
│           ├── HowardApplication.kt
│           ├── MainActivity.kt
│           ├── agent/
│           │   ├── CommandDispatcher.kt
│           │   ├── PromptBuilder.kt
│           │   ├── SystemPrompts.kt
│           │   └── ToolExecutor.kt
│           ├── connectors/
│           │   ├── OpenClawConnector.kt
│           │   └── TelegramConnector.kt
│           ├── data/
│           │   ├── HowardDatabase.kt
│           │   └── SecurePrefs.kt
│           ├── download/
│           │   ├── DeviceDetector.kt
│           │   ├── ModelDownloader.kt
│           │   └── ModelRegistry.kt
│           ├── engine/
│           │   ├── CloudEngine.kt
│           │   ├── EngineRouter.kt
│           │   ├── InferenceEngine.kt
│           │   └── LocalEngine.kt
│           ├── service/
│           │   ├── BootReceiver.kt
│           │   ├── GatewayService.kt
│           │   └── InferenceService.kt
│           └── ui/
│               ├── chat/
│               │   ├── ChatScreen.kt
│               │   └── ChatViewModel.kt
│               ├── onboarding/
│               │   ├── OnboardingScreen.kt
│               │   ├── NoticeStep.kt
│               │   ├── Step1_ModelDownload.kt
│               │   ├── Step2_CloudKeys.kt
│               │   ├── Step3_OpenClaw.kt
│               │   └── Step4_Telegram.kt
│               ├── settings/
│               │   └── SettingsScreen.kt
│               ├── tools/
│               │   └── ToolsScreen.kt
│               └── theme/
│                   ├── Theme.kt
│                   └── Type.kt
├── gradle/
│   └── libs.versions.toml
└── scripts/
    ├── init_project.sh
    └── bundle_assets.sh
```

---

## KEY ARCHITECTURAL DECISIONS

### Why NDK not Termux
llama.cpp is compiled directly into `libllama.so` via the Android NDK + CMake. The JNI bridge in `howard_jni.cpp` exposes `loadModel()`, `runInference()` (streaming via callback), `stopInference()`, and `freeModel()` to Kotlin. This is what makes Howard a real APK rather than a Termux wrapper.

### Why bundled assets not download
`GatewayService.kt` extracts Node.js ARM64 + OpenClaw from APK assets into `filesDir/runtime/` on first launch. A `.extracted` marker file prevents re-extraction on subsequent launches. This is the same technique used by andClaw (Play Store, 1K+ installs). Play Store compliant — no downloading of executables post-install.

### Engine routing
`EngineRouter.kt` holds one active `InferenceEngine` at a time. `LocalEngine` calls JNI. `CloudEngine` uses OkHttp streaming against OpenAI-compatible endpoints (OpenAI, Anthropic, Gemini, Kimi, OpenRouter all use the same `/chat/completions` SSE format). Swapping is hot — user taps a chip in the model switcher row.

### CMD token dispatch
Howard is prompted (via `SystemPrompts.kt`) to emit `[CMD: tool_name args]` tokens inline in its responses. `ChatViewModel` streams tokens into a buffer and uses `Regex("""\[CMD:\s*([^\]]+)\]""")` to detect complete CMD tokens mid-stream. On match, the pre-CMD text is displayed, the CMD is stripped from the buffer, and `CommandDispatcher.dispatch()` is called in a child coroutine. The tool result is injected back into the conversation as a `role = "tool"` message.

### Memory / persistence
- **Chat history:** Room database (`HowardDatabase`) with `messages`, `task_history`, `model_registry` tables.
- **API keys + tokens:** `EncryptedSharedPreferences` via `SecurePrefs.kt`. AES256-GCM. Keys never leave the device.
- **Model files:** Stored in `filesDir/models/`. `ModelDownloader` streams bytes with range-request resume support.

---

## BUILD SEQUENCE (EXACT ORDER)

### Step 1 — Init
```bash
git clone <repo> howard-android && cd howard-android
bash scripts/init_project.sh
# This adds llama.cpp as a git submodule and generates a debug keystore
```

### Step 2 — Bundle assets (run on Geekom A9 Max or any Linux machine)
```bash
bash scripts/bundle_assets.sh
# Downloads Node.js 22.12.0 ARM64 binary → app/src/main/assets/node/node
# Packs OpenClaw npm package → app/src/main/assets/openclaw.tar.gz
# Total asset size: ~120-150 MB
```

### Step 3 — Fonts (download free from Google Fonts)
Place in `app/src/main/res/font/`:
- `outfit_regular.ttf`, `outfit_semibold.ttf`, `outfit_bold.ttf`
- `source_sans3_regular.ttf`, `source_sans3_medium.ttf`
- `jetbrainsmono_regular.ttf`

### Step 4 — Android Studio
1. Open project in Android Studio Hedgehog or newer
2. File → Sync Project with Gradle Files
3. Build → Make Project (triggers CMake for llama.cpp — first build ~10 min)
4. For testing: Run on connected ARM64 device (Pixel 6+, Galaxy S22+, or similar)
5. For release: Build → Generate Signed Bundle/APK → APK → use `howard-debug.jks` (storepass: `howard123`)

### Step 5 — Verify on device
After sideload install:
- App launches → onboarding shows
- Tap through to Step 3 (OpenClaw) — gateway status should show "online" within 30s
- Download a model in Step 1 (Qwen3 0.6B recommended for testing — 640 MB)
- Chat screen appears — send a test message
- Notification bar should show "Howard online · port 18789"

---

## DEPENDENCIES (all in libs.versions.toml)

| Library | Version | Purpose |
|---|---|---|
| Jetpack Compose BOM | 2024.12.01 | UI framework |
| Room | 2.6.1 | SQLite ORM |
| WorkManager | 2.10.0 | Background downloads |
| Security-Crypto | 1.1.0-alpha06 | EncryptedSharedPreferences |
| Retrofit + OkHttp | 2.11.0 / 4.12.0 | Cloud LLM + Telegram API |
| Kotlin Coroutines | 1.9.0 | Async inference + polling |
| Navigation Compose | 2.8.5 | NavGraph |
| KSP | 2.0.21-1.0.28 | Room annotation processor |
| NDK / CMake | 3.22.1 | llama.cpp native build |

---

## CLOUD PROVIDERS WIRED (CloudEngine.kt)

| Provider | Base URL | Model default | Auth header |
|---|---|---|---|
| OpenAI | `api.openai.com/v1` | gpt-4o-mini | `Authorization: Bearer` |
| Anthropic | `api.anthropic.com/v1` | claude-sonnet-4-6 | `x-api-key` |
| Gemini | `generativelanguage.googleapis.com/v1beta/openai` | gemini-2.0-flash | `Authorization: Bearer` |
| Kimi | `api.moonshot.cn/v1` | moonshot-v1-8k | `Authorization: Bearer` |
| OpenRouter | `openrouter.ai/api/v1` | llama-3.1-8b-instruct:free | `Authorization: Bearer` |

All use the OpenAI-compatible SSE streaming format. Adding a new provider = one new enum entry in `CloudProvider`.

---

## AGENT TOOL GRAMMAR

Howard is prompted to emit commands in this exact format:
```
[CMD: tool_name arg1 arg2]
```

| Tool | Signature | What it does |
|---|---|---|
| `github_sync` | `<repo_url> <local_dir>` | Clone or pull a git repo |
| `file_organizer` | `<source_dir>` | Sort files into extension subdirs |
| `web_component_gen` | `<ComponentName> <output_dir>` | Scaffold a React JSX file |
| `telegram_send` | `<message>` | Send to configured Telegram channel |
| `shell` | `<bash_command>` | Raw shell passthrough |

Adding a new tool: (1) add a branch in `ToolExecutor.execute()`, (2) add the grammar to `SystemPrompts.HOWARD_BASE`, (3) optionally add a card in `ToolsScreen.kt`.

---

## MODEL REGISTRY

All models are Q4_K_M quantised `.gguf` from Bartowski on HuggingFace. Three categories:

**Thinking Models** (Qwen3 0.6B / 1.7B / 4B) — show reasoning steps  
**Standard Models** (SmolLM3 3B, LFM2.5 1.2B, Llama 3.2 1B, Gemma 2 2B, Qwen2.5 3B, Rocket 3B)  
**Agent Models** (Llama 3.1 8B, Mistral 7B) — optimised for tool calling

`DeviceDetector.compatibleWith(ramGb)` filters the list so users only see models their device can run. Suitability tiers: Excellent (12GB+), Good (8GB+), Moderate (6GB+), Limited (4GB+), Unsupported (<4GB).

---

## OPENCLAW INTEGRATION DETAILS

- **Gateway port:** `18789` (WebSocket + HTTP)
- **Config file:** `filesDir/.openclaw/openclaw.json` (written on first launch)
- **Process management:** `GatewayService` spawns Node as a `ProcessBuilder` child process, streams stdout to Logcat, monitors exit and auto-restarts after 5s
- **Health check endpoint:** `GET http://127.0.0.1:18789/health`
- **Task endpoint:** `POST http://127.0.0.1:18789/api/message` with `{"message": "task text"}`
- **WebSocket:** `ws://127.0.0.1:18789` for streaming token delivery

The official OpenClaw Android app is not yet publicly released. Howard bundles the gateway directly rather than depending on a companion app — this is the same approach as andClaw (com.coderred.andclaw, Play Store).

---

## TELEGRAM INTEGRATION DETAILS

- **Bot creation:** User creates bot via @BotFather, pastes token in onboarding Step 4
- **Polling:** Long-poll `getUpdates?timeout=30` loop in `InferenceService` coroutine
- **Inbound flow:** Telegram message → `InferenceService.handleTask()` → `EngineRouter` → inference → `CommandDispatcher` for any CMD tokens → reply via `sendMessage`
- **Outbound:** `TelegramConnector.sendMessage()` called from `ToolExecutor.telegramSend()` when Howard emits `[CMD: telegram_send ...]`
- **Privacy:** Bot token stored in `EncryptedSharedPreferences`. Never sent to any Howard server (there is no Howard server).

---

## ONBOARDING FLOW (4 STEPS)

| Step | Screen | What happens |
|---|---|---|
| 1 | `NoticeStep` | Privacy notice card — mirrors OfflineGPT UX. "Got it" CTA. |
| 2 | `Step1_ModelDownload` | Model chooser by category. DeviceDetector filters list. WorkManager download with progress bar. |
| 3 | `Step2_CloudKeys` | Optional API key entry per provider. Auto-saved to EncryptedSharedPreferences on each keystroke. |
| 4 | `Step3_OpenClaw` | Gateway extraction status (10 retries × 2s). Shows green "online" card when port 18789 responds. |
| 5 | `Step4_Telegram` | Bot token + channel ID. Test connection button sends a welcome message. Toggle inbound polling. |

On completion, `securePrefs.setOnboardingComplete()` is called. `MainActivity` NavGraph starts at `"chat"` on all subsequent launches.

---

## WHAT IS NOT YET BUILT (NEXT TASKS FOR CLAUDE CODE)

The following files are stubs or missing — implement these in priority order:

### Priority 1 — Required for first working build
- [ ] `ui/settings/ModelsScreen.kt` — manage downloaded models, set active, delete
- [ ] `ui/settings/ApiKeysScreen.kt` — edit/reveal/delete stored API keys  
- [ ] `ui/settings/TelegramSettingsScreen.kt` — edit bot config after onboarding
- [ ] `ui/theme/Type.kt` font files — download Outfit + Source Sans 3 + JetBrains Mono from Google Fonts
- [ ] `R.drawable.ic_howard_logo` — vector drawable for onboarding splash (currently placeholder)
- [ ] `R.mipmap.ic_launcher` + `ic_launcher_round` — app icon (Howard logo / robot claw icon)

### Priority 2 — Required for Play Store submission
- [ ] `download/ModelDownloader.kt` WorkManager integration — currently uses coroutine Flow; wrap in `CoroutineWorker` for background download that survives process death
- [ ] Custom GGUF import — file picker intent in `Step1_ModelDownload.kt` for "Add custom model" card
- [ ] Storage permission handling — `READ_EXTERNAL_STORAGE` / `READ_MEDIA_DOCUMENTS` runtime request for GGUF import
- [ ] Play Store listing assets — icon, feature graphic, screenshots (use the UI Preview from howard-ui-layer artifact)
- [ ] Privacy policy page — required for Play Store (local-only data handling; reference andClaw's policy structure at coderredlab.github.io/andclaw-privacy/)
- [ ] `local.properties` NDK path — confirm `ndk.dir` and `sdk.dir` are correct for build machine

### Priority 3 — Polish and monetisation
- [ ] In-app purchase / Pro tier — gate 8B agent models and unlimited Telegram polling behind one-time purchase (follow OfflineGPT's model: free tier = limited models, Pro = full access)
- [ ] `HowardForegroundService` wakelock — currently acquires 6h max; implement `WorkManager` periodic task to renew wakelock and restart gateway if dead
- [ ] `InferenceService` Telegram polling rate limit — add exponential backoff and per-user rate limiting to prevent API abuse
- [ ] Chat markdown rendering — `AssistantBubble` currently renders plain text; add a Compose Markdown library (Jetbrains/markdown or Halilibo/compose-richtext)
- [ ] Conversation management — currently one conversation per app session; add conversation list screen backed by Room

---

## KNOWN ISSUES AND GOTCHAS

1. **CMake first build time** — llama.cpp takes 8-15 minutes to compile on first `./gradlew assembleDebug`. This is normal. Subsequent builds are incremental.

2. **Asset size** — Node.js ARM64 binary is ~50 MB, OpenClaw package ~60-80 MB. Total APK will be ~200-250 MB. This is under Play Store's 150 MB APK limit — use **Android App Bundle (AAB)** instead of APK for Play Store. AABs support expansion files automatically.

3. **`GatewayService` process killing** — Android aggressively kills background processes. The `ForegroundService` + `PARTIAL_WAKE_LOCK` + `START_STICKY` combination handles most cases. For guaranteed persistence, guide users through disabling battery optimisation for Howard in Settings → Battery → Howard → Unrestricted.

4. **llama.cpp GPU offloading** — `n_gpu_layers = 0` in `howard_jni.cpp`. Android does not support GPU offloading for llama.cpp (no Vulkan compute support in the NDK build chain as of early 2026). CPU-only inference. Expected speed: 3-8 tokens/sec on Snapdragon 8 Gen 2 with Q4_K_M 7B model.

5. **OpenClaw `/tmp` directory** — OpenClaw expects `/tmp/openclaw` to exist. Android's app sandbox doesn't have `/tmp`. In `GatewayService`, set `TMPDIR` environment variable to `filesDir/tmp` before spawning the Node process:
   ```kotlin
   environment()["TMPDIR"] = File(filesDir, "tmp").also { it.mkdirs() }.absolutePath
   ```

6. **`EncryptedSharedPreferences` first-run crash** — on some devices, `MasterKey` creation can fail if the Android Keystore is in a bad state after a system upgrade. Wrap `SecurePrefs` constructor in try-catch and fall back to plain `SharedPreferences` with a UI warning.

7. **Telegram long-polling on doze** — Android doze mode kills network access for background apps. Telegram polling will pause during doze. Use `setAndAllowWhileIdle` alarm or guide users to add Howard to doze exceptions.

---

## PLAY STORE REQUIREMENTS CHECKLIST

- [ ] `targetSdk = 35` (required for new submissions from Aug 2024)
- [ ] `foregroundServiceType` declared in manifest ✅ (already set to `specialUse` + `dataSync`)
- [ ] `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property declared ✅ (already in manifest)
- [ ] Privacy policy URL — required (create a simple HTML page on GitHub Pages)
- [ ] Data safety form — declare: no data collected, no data shared, data encrypted in transit
- [ ] Content rating questionnaire — answer "no" to all sensitive content questions
- [ ] AAB format — build with `./gradlew bundleRelease` for Play Store upload
- [ ] Signed with production keystore — generate a separate `howard-release.jks` (NOT the debug one)
- [ ] `minSdk = 26` means Android 8.0+ — covers ~99% of active devices

---

## REFERENCE APPS STUDIED

| App | Package | What we borrowed |
|---|---|---|
| OfflineGPT | `com.offlinegpt.app` | Model chooser UX, category layout, device info panel, important notice card |
| andClaw | `com.coderred.andclaw` | Asset bundling pattern, foreground service architecture, one-tap setup concept |
| openclaw-android (GitHub) | AidanPark/openclaw-android | Termux setup documentation, llama.cpp constraints on Android |
| ClawPhone (GitHub) | marshallrichards/ClawPhone | Termux session management, Termux:API integration patterns |

---

## TESTING CHECKLIST (before Play Store submission)

- [ ] Cold launch → onboarding displays correctly
- [ ] Model download → progress bar works → model appears as downloadable in Step 1
- [ ] Local inference → tokens stream into chat UI
- [ ] Cloud inference → each of the 5 providers returns a response
- [ ] CMD dispatch → send "organise my downloads folder" → `[CMD: file_organizer]` fires → tool result appears
- [ ] Gateway service survives screen off for 10 minutes
- [ ] Gateway service restarts after app force-stop
- [ ] BootReceiver fires after device reboot
- [ ] Telegram → create test bot → send message → Howard replies
- [ ] Settings → delete API key → cloud engine deactivates
- [ ] Uninstall → reinstall → onboarding appears again (encrypted prefs cleared)
- [ ] Test on minimum spec device (4GB RAM, Snapdragon 6 series)

---

## QUICK REFERENCE — KEY FILES BY FUNCTION

| Need to change... | Edit this file |
|---|---|
| Howard's personality / tool list | `agent/SystemPrompts.kt` |
| Add a new tool | `agent/ToolExecutor.kt` + `SystemPrompts.kt` |
| Add a new cloud provider | `engine/CloudEngine.kt` (new enum entry) |
| Add a new model to the registry | `download/ModelRegistry.kt` |
| Change gateway port | `service/GatewayService.kt` → `GATEWAY_PORT` constant |
| Change app colours | `ui/theme/Theme.kt` → `HowardColors` object |
| Change notification text | `res/values/strings.xml` |
| Adjust context window / threads | `engine/LocalEngine.kt` constructor defaults |
| Change Telegram polling interval | `connectors/TelegramConnector.kt` → `pollUpdates()` timeout param |
