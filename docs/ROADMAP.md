# Howard-Claw: Implementation Roadmap

## Phase 1: Repository Coherence

**Status: In progress**

**Purpose:** Transform the repo from scattered design materials into a structured, honest project with clear documentation.

| Task | Status |
|---|---|
| Clean up repo structure | Done |
| Write README with honest status | Done |
| Create architecture documentation | Done |
| Create Android feasibility analysis | Done |
| Create setup plan | Done |
| Create this roadmap | Done |
| Archive original design materials | Done |
| Add .gitignore | Done |

**Success criteria:** Another engineer can read the repo and understand exactly what exists, what doesn't, and what the plan is.

## Phase 2: Android Proof of Concept

**Status: Not started**

**Purpose:** Validate the three riskiest technical assumptions before building the full app.

| Task | Details |
|---|---|
| Create Android Studio project skeleton | Gradle + Kotlin + Compose + NDK/CMake config |
| Integrate llama.cpp as git submodule | Add to `app/src/main/cpp/`, configure CMakeLists.txt |
| Build JNI bridge | `howard_jni.cpp` with loadModel, runInference, stopInference, freeModel |
| Prove local inference works | Load a Q4_K_M 0.6B model and generate tokens on a real ARM64 device |
| Measure performance | Record tok/s, RAM usage, thermal behaviour on 2–3 devices |
| Test ForegroundService persistence | Run a dummy service for 30+ minutes across Pixel, Samsung, and one other OEM |
| Document findings | Update feasibility doc with real measurements |

**Dependencies:** Android Studio, NDK, physical ARM64 test device(s)

**Risks:**
- llama.cpp NDK build may require patches for Android-specific issues
- JNI memory management bugs (native heap vs JVM heap)
- Performance may be worse than estimated

**Success criteria:** A bare APK that loads a 0.6B model, generates tokens via JNI, and displays them in a minimal Compose UI. Measured performance numbers documented.

## Phase 3: Minimum Working Version

**Status: Not started**

**Purpose:** Build a usable chat app with local inference and cloud fallback.

| Task | Details |
|---|---|
| ChatScreen + ChatViewModel | Compose LazyColumn, streaming token display, input bar |
| LocalEngine | llama.cpp JNI wrapper with streaming callback |
| CloudEngine | OkHttp SSE against OpenAI-compatible endpoints (start with one provider) |
| EngineRouter | Hot-swap between local and cloud |
| ModelDownloader | HTTP download with range-request resume, WorkManager integration |
| ModelRegistry | Catalog of available models with RAM-based filtering |
| DeviceDetector | RAM and SoC detection |
| Room database | Messages table with conversation support |
| SecurePrefs | EncryptedSharedPreferences for API keys |
| Basic onboarding | Model download step + optional API key entry |
| Navigation | NavHost with chat and settings screens |

**Dependencies:** Phase 2 proof of concept validated

**Risks:**
- Compose + NDK integration complexity
- Model download reliability (large files, interrupted downloads)
- Memory management during inference

**Success criteria:** User can install APK, download a model, chat locally with streaming responses, and switch to cloud inference with an API key. App does not crash during normal use.

## Phase 4: System Hardening

**Status: Not started**

**Purpose:** Add agent capabilities, messaging, and background reliability.

| Task | Details |
|---|---|
| Agent CMD parser | Regex-based [CMD:] token extraction from LLM output |
| CommandDispatcher + ToolExecutor | Tool routing and execution (file_ops, telegram_send, github_sync) |
| SystemPrompts | Base prompt with tool grammar |
| Telegram connector | Bot token entry, long-poll getUpdates, sendMessage |
| InferenceService | ForegroundService for background inference + Telegram |
| Battery optimisation handling | Detection + user guidance for whitelisting |
| OpenClaw gateway (optional) | Bundle Node.js + OpenClaw, GatewayService, health monitoring |
| Error recovery | Crash resilience, auto-restart, graceful degradation |
| Multiple cloud providers | Add Anthropic, Gemini, OpenRouter to CloudEngine |

**Dependencies:** Phase 3 stable

**Risks:**
- Background service reliability across OEMs
- OpenClaw Node.js process stability
- Tool execution security (sandboxing)
- Telegram polling during doze

**Success criteria:** App runs reliably for 24+ hours including screen-off periods on a Pixel device. Agent tools execute correctly. Telegram integration works.

## Phase 5: Distribution and Polish

**Status: Not started**

**Purpose:** Play Store readiness, UX polish, and optional features.

| Task | Details |
|---|---|
| Play Store listing | Icon, screenshots, feature graphic, description |
| Privacy policy | GitHub Pages hosted policy page |
| Data safety form | No data collected, encrypted local storage |
| AAB build | Android App Bundle for Play Store distribution |
| Release signing | Production keystore (not debug) |
| Custom GGUF import | File picker for user-provided models |
| Conversation management | Multiple conversations backed by Room |
| Chat markdown rendering | Compose Markdown library for assistant messages |
| Settings screens | Models, API keys, Telegram config, about |
| In-app purchase (optional) | Pro tier for larger models or unlimited features |

**Dependencies:** Phase 4 stable, real-world testing on multiple devices

**Risks:**
- Play Store review may flag bundled Node.js binary
- APK size limits if OpenClaw is bundled
- User experience complexity for non-technical users

**Success criteria:** Published on Play Store. Users can install and use the app without developer assistance.

## Timeline Expectations

No time estimates are provided. Each phase should be completed and validated before starting the next. Rushing to Phase 5 without solid Phase 2/3 foundations will produce a broken app.

## Decision Points

At each phase boundary, evaluate:

1. **After Phase 2:** Is local inference good enough to be useful? If not, pivot to cloud-primary with optional local.
2. **After Phase 3:** Is the app stable enough for daily use? If not, focus on stability before adding features.
3. **After Phase 4:** Is OpenClaw integration worth the APK size and complexity? If not, keep it as optional/advanced.
4. **Before Phase 5:** Is the target audience technical users (sideload) or general users (Play Store)? This changes UX requirements significantly.
