# Howard-Claw: Android Feasibility Analysis

## Summary Verdict

Building Howard as described is **feasible but demanding**. The core concept — local LLM inference + cloud fallback + agent tools in a native APK — works. But several design assumptions need correction, and the realistic scope is narrower than the original vision.

## Component-by-Component Feasibility

### Local LLM Inference via llama.cpp NDK

**Verdict: Feasible, with constraints.**

| Factor | Assessment |
|---|---|
| NDK compilation | llama.cpp builds cleanly for ARM64 via CMake. Well-tested path. |
| JNI bridge | Standard pattern. No unusual challenges. |
| CPU inference speed | 3–8 tok/s for Q4_K_M models on Snapdragon 8 Gen 2/3. Usable for small models. |
| GPU offloading | **Not available.** Android NDK does not expose Vulkan compute for llama.cpp as of early 2026. CPU-only. |
| RAM usage | Q4_K_M 3B model ≈ 2GB RAM. Q4_K_M 7B ≈ 4.5GB. On a 6GB phone, 3B is the practical max. On 8GB+, 7B is marginal. |
| Thermal throttling | Sustained inference causes thermal throttling within 2–5 minutes on most devices. Speed drops 30–50%. |
| Model storage | GGUF files are 400MB–5GB. Internal storage only (scoped storage). Users need free space. |

**Recommended model tier:**
- Primary: 0.6B–1.7B models (fast, low RAM, minimal thermal impact)
- Secondary: 3B models (slower, needs 6GB+ device)
- Aspirational: 7B+ models (marginal, flagship devices only, hot phone)

### Cloud LLM Fallback

**Verdict: Fully feasible.**

OkHttp SSE streaming against OpenAI-compatible endpoints is standard Android networking. No platform-specific issues. Works on any network connection.

### Bundled Node.js (OpenClaw Gateway)

**Verdict: Feasible, proven by andClaw, but adds complexity and size.**

| Factor | Assessment |
|---|---|
| ARM64 Node.js binary | Official Node.js builds exist for linux-arm64. Works on Android. |
| ProcessBuilder spawn | Works. Node runs as a child process in the app's sandbox. |
| Asset size | ~50MB for Node.js + ~60-80MB for OpenClaw = ~130MB added to APK. |
| Play Store compliance | andClaw (com.coderred.andclaw) does exactly this and is published. Precedent exists. |
| Stability | Node.js child process can be killed by Android independently of the app. Requires monitoring and restart logic. |
| `/tmp` directory | Android has no `/tmp`. Must set `TMPDIR` env var to app-internal path before spawning. |

**Recommendation:** Make this Phase 4 (optional). The MVP should work without OpenClaw. This reduces APK size by ~130MB and removes a major source of complexity.

### ForegroundService Persistence

**Verdict: Partially feasible. The #1 reliability risk.**

Android's ForegroundService mechanism is designed for tasks the user is actively aware of (music playback, navigation, file downloads). Using it for "always-on AI agent" pushes against the platform's design intent.

| OEM | Behaviour |
|---|---|
| Stock Android (Pixel) | ForegroundService + PARTIAL_WAKE_LOCK works reliably. Doze still pauses network periodically. |
| Samsung (One UI) | Aggressive battery optimisation. May kill ForegroundService after 20–30 minutes of screen-off unless user adds to "Never sleeping apps". |
| Xiaomi (MIUI/HyperOS) | Very aggressive. Battery Saver + MIUI autostart restrictions. Users must manually enable autostart AND disable battery optimisation. |
| Huawei (EMUI/HarmonyOS) | Most aggressive. "App launch" settings page required. ForegroundService frequently killed. |
| OnePlus (OxygenOS) | Moderate. Similar to Samsung but slightly more permissive. |

**Mitigation strategies:**
1. Clear user guidance: "Go to Settings → Battery → Howard → Unrestricted"
2. `START_STICKY` to request restart after kill
3. WorkManager periodic task as backup restart mechanism
4. In-app check for battery optimisation status with prompt to whitelist

**Reality check:** There is no reliable way to guarantee an always-on background service on Android across all OEMs. This must be communicated honestly to users.

### Telegram Bot Integration

**Verdict: Feasible, with doze limitations.**

Long-poll `getUpdates` works fine when the app is in the foreground. During doze mode, network access is restricted. Polling will pause and resume when the device wakes.

For near-real-time notification of Telegram messages during doze, the only reliable option is Firebase Cloud Messaging (FCM) — but that requires a server-side component to receive Telegram webhooks and forward via FCM. This contradicts the "no server" design goal.

**Recommendation:** Accept that Telegram responsiveness degrades during doze. Document this clearly. If real-time is needed, add an optional FCM relay server as a Phase 5 feature.

### Agent Tool Execution

**Verdict: Partially feasible. Several tools need redesign.**

| Tool | Feasibility | Issues |
|---|---|---|
| github_sync | Medium | No git binary on Android. Requires bundling JGit or a custom implementation. ~5MB library addition. |
| file_organizer | Low | Scoped storage on Android 11+ prevents access to arbitrary directories. Can only operate within app-owned directories or files explicitly granted via SAF (Storage Access Framework). The "organise my Downloads folder" use case requires explicit user permission via a directory picker. |
| web_component_gen | High | Writing files to app-internal storage is unrestricted. Works fine. |
| telegram_send | High | Standard HTTP API call. No issues. |
| shell | Very Low | Android's app sandbox severely restricts shell execution. No access to most system paths. No `su`. Very few useful commands available. **Recommendation: Remove or replace with a curated set of safe operations.** |

### Scoped Storage Impact

Android 11+ (API 30+) enforces scoped storage. Key implications:

- **App-internal storage** (`filesDir`, `cacheDir`): Full read/write access. No permissions needed. This is where models, database, and runtime files live.
- **Shared storage** (Downloads, Documents, etc.): Requires `READ_MEDIA_*` permissions or SAF. Cannot enumerate or modify files without explicit user grant.
- **External SD card**: Extremely restricted. Essentially read-only for apps.
- **Other apps' files**: Completely inaccessible.

This means agent tools that claim to "organise your files" can only operate on files the user explicitly selects via a system file picker, or within Howard's own storage.

### APK Size

| Component | Estimated Size |
|---|---|
| Kotlin + Compose + Libraries | ~15MB |
| libllama.so (ARM64) | ~5MB |
| Node.js ARM64 binary | ~50MB |
| OpenClaw package | ~60-80MB |
| Resources + assets | ~5MB |
| **Total (with OpenClaw)** | **~135-155MB** |
| **Total (without OpenClaw)** | **~25MB** |

Without OpenClaw, the APK is a very reasonable ~25MB. With OpenClaw bundled, it approaches the 150MB APK limit and requires AAB + Play Asset Delivery for Play Store distribution.

**Recommendation:** Ship without OpenClaw for MVP. Add it as an optional download (Play Asset Delivery or in-app download) in Phase 4.

## Overall Feasibility Matrix

| Feature | On-Device Feasibility | Recommended Phase |
|---|---|---|
| Chat UI (Compose) | High | Phase 3 (MVP) |
| Local inference (≤3B) | High | Phase 3 (MVP) |
| Local inference (7B+) | Low | Phase 5 (optional) |
| Cloud LLM fallback | High | Phase 3 (MVP) |
| Model download + management | High | Phase 3 (MVP) |
| Encrypted secret storage | High | Phase 3 (MVP) |
| Telegram send | High | Phase 4 |
| Telegram polling | Medium (doze issues) | Phase 4 |
| Agent tool dispatch | Medium | Phase 4 |
| File operations (app-internal) | High | Phase 4 |
| File operations (shared storage) | Low (SAF required) | Phase 5 |
| Shell passthrough | Very Low | Remove or Phase 5 |
| OpenClaw gateway | Medium | Phase 4 |
| Always-on background service | Low (OEM-dependent) | Phase 4 |
| Play Store distribution | Medium | Phase 5 |

## Hard Constraints (Cannot Be Worked Around)

1. **No GPU inference** on Android via NDK (no Vulkan compute support for llama.cpp)
2. **No guaranteed background persistence** across all OEMs
3. **No unrestricted file system access** on Android 11+
4. **No real-time push notifications** without a server-side component
5. **Thermal throttling** will degrade inference speed during sustained use
6. **RAM is the hard ceiling** for model size — cannot be worked around with swap or compression
