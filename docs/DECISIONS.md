# Howard-Claw: Architectural Decision Log

## ADR-001: Native APK, Not Termux Wrapper

**Date:** March 2026
**Status:** Accepted

**Context:** Howard could be built as a Termux package/script (like ClawPhone) or as a native Android APK.

**Decision:** Native APK via Android NDK + Kotlin.

**Rationale:**
- Play Store distribution requires a standard APK/AAB
- Native APK provides proper UI (Jetpack Compose), notifications, services, and system integration
- Termux requires separate installation and manual setup — unacceptable for target users
- llama.cpp compiles cleanly for ARM64 via CMake/NDK
- andClaw proved this approach works and is Play Store compliant

**Consequences:**
- More complex build system (NDK + CMake + Gradle)
- JNI bridge needed between C++ and Kotlin
- Cannot easily use Python ML libraries (must use C++ inference)

---

## ADR-002: OpenClaw as Optional Phase 4 Feature

**Date:** March 2026
**Status:** Accepted

**Context:** The original design bundled OpenClaw as a core component extracted on first launch. This adds ~130MB to APK size, introduces a Node.js child process management layer, and creates a second inference/agent path parallel to Howard's own.

**Decision:** OpenClaw is Phase 4 (optional), not MVP.

**Rationale:**
- Howard's own inference engines (local + cloud) work independently
- Howard's own agent dispatch (CMD tokens) works independently
- Adding OpenClaw before proving the base app works is premature complexity
- ~130MB saved from APK size in MVP
- Clearer architecture: one inference path, one agent system
- OpenClaw can be added later as an optional download or advanced feature

**Consequences:**
- MVP has simpler architecture and smaller APK
- Power users who want OpenClaw's richer agent framework must wait for Phase 4
- The relationship between Howard's agent system and OpenClaw's must be defined when integration happens

---

## ADR-003: CPU-Only Inference

**Date:** March 2026
**Status:** Accepted (constrained by platform)

**Context:** llama.cpp supports GPU offloading on desktop (CUDA, Metal, Vulkan). Android NDK does not expose Vulkan compute in a way llama.cpp can use as of early 2026.

**Decision:** CPU-only inference. `n_gpu_layers = 0`.

**Rationale:**
- No viable GPU offloading path on Android NDK currently
- CPU inference on ARM64 (Snapdragon 8 Gen 2/3) gives 3–8 tok/s for Q4_K_M models — usable for small models
- Cloud fallback covers the performance gap for complex tasks

**Consequences:**
- Large models (7B+) are impractically slow
- Practical model range is 0.6B–3B
- Phone gets warm during sustained inference
- Cloud fallback is important, not just nice-to-have

---

## ADR-004: Remove Shell Passthrough Tool

**Date:** March 2026
**Status:** Proposed

**Context:** The original design includes a `shell` tool that executes arbitrary bash commands from LLM output. On Android, the app sandbox severely restricts what shell commands can do. Combined with LLM hallucination risk, this creates a security concern with minimal utility.

**Decision:** Remove the `shell` tool from the initial design. Replace with specific, sandboxed operations.

**Rationale:**
- Android app sandbox limits shell to a tiny subset of commands
- LLM-generated shell commands are unpredictable and potentially destructive
- Specific tools (file_ops, git_sync, telegram_send) are safer and more useful
- Can be reconsidered as an opt-in advanced feature with explicit user confirmation per command

**Consequences:**
- Reduced attack surface
- Less flexible for power users
- Specific tools must cover the most common use cases

---

## ADR-005: EncryptedSharedPreferences for Secrets

**Date:** March 2026
**Status:** Accepted

**Context:** Howard stores API keys, bot tokens, and configuration that must remain private.

**Decision:** Use Android's `EncryptedSharedPreferences` (AES256-GCM, backed by Android Keystore).

**Rationale:**
- Standard Android security practice
- Keys never leave the device
- Encrypted at rest
- No server-side storage needed

**Known issue:** On some devices, `MasterKey` creation fails after OS upgrades due to Android Keystore corruption. Mitigation: try-catch with fallback to plain SharedPreferences + UI warning.

---

## ADR-006: Planning Repo First, Implementation Repo Later

**Date:** March 2026
**Status:** Accepted

**Context:** The repository contained design materials (a handoff doc and embedded source code in a JSX viewer) but no buildable Android project. The choice is between (a) immediately creating a full project skeleton or (b) establishing clear documentation first.

**Decision:** Restructure as a planning/architecture repo first. Implementation begins in Phase 2.

**Rationale:**
- The design is good but untested against real Android constraints
- Creating project files without validating assumptions produces false confidence
- Clear documentation prevents architectural drift
- Phase 2 (proof of concept) will validate assumptions before full implementation
- Preserved source code designs in `reference/` for use when implementation begins

**Consequences:**
- Repo does not contain runnable code (yet)
- Clear separation between "designed" and "built"
- Forces validation of assumptions before writing production code
