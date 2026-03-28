# Howard-Claw

A native Android application for on-device LLM inference with agent capabilities, cloud LLM fallback, and optional OpenClaw gateway integration.

## Project Status: Architecture & Planning Phase

This repository is in the **design and planning stage**. No buildable Android project exists yet. The `docs/` directory contains the technical architecture, feasibility analysis, and implementation roadmap. The `archive/` directory preserves original design materials.

**What exists today:**
- Detailed architecture documentation
- Android feasibility analysis
- Source code designs (Kotlin) for all major components — preserved as reference, not yet in a buildable project
- Phased implementation roadmap

**What does not exist yet:**
- Android Studio project / Gradle build system
- Compilable source code
- Working APK
- Tests or CI

## What Howard-Claw Is

Howard is an Android APK that combines:

1. **Local LLM inference** — llama.cpp compiled via Android NDK into a native library. Runs GGUF models (0.6B–3B recommended) directly on ARM64 hardware. No Termux, no Python, no cloud required for basic operation.
2. **Cloud LLM fallback** — OkHttp streaming against OpenAI-compatible endpoints (OpenAI, Anthropic, Gemini, OpenRouter, others). User provides their own API keys.
3. **Agent tool dispatch** — LLM output is parsed for `[CMD: tool_name args]` tokens, which trigger sandboxed tool execution (file operations, GitHub sync, Telegram messaging).
4. **Telegram integration** — Bot token polling for receiving tasks remotely, message sending for replies.
5. **OpenClaw gateway** (optional, Phase 4) — Bundled Node.js ARM64 binary + OpenClaw package running as a ForegroundService for advanced agent workflows.

## What Problem It Solves

No single Android app currently combines local LLM inference, cloud LLM access, agent tool execution, and messaging integration in one native package. Howard aims to be a self-contained AI agent environment that runs primarily on your phone.

## Limitations

- **Local inference is CPU-only.** Android does not support GPU offloading for llama.cpp as of early 2026. Expect 3–8 tokens/sec on flagship SoCs with small models.
- **Large models (7B+) are impractical on most phones.** Realistic sweet spot is 0.6B–3B models.
- **Background persistence is unreliable.** Android OEMs (Samsung, Xiaomi, Huawei) aggressively kill background processes despite ForegroundService + wake locks. Users must manually whitelist the app.
- **Scoped storage limits file operations.** The agent cannot freely access arbitrary directories on Android 11+.
- **This is experimental software.** Not production-ready. Not on the Play Store.

## Repository Structure

```
Howard-Claw/
├── README.md                       # This file
├── .gitignore                      # Android/Kotlin/Node ignores
├── docs/
│   ├── PROJECT_VISION.md           # Mission, scope, target users
│   ├── ARCHITECTURE.md             # Full technical architecture
│   ├── ANDROID_FEASIBILITY.md      # Honest Android constraints
│   ├── SETUP_PLAN.md               # Build steps (for when code exists)
│   ├── ROADMAP.md                  # Phased implementation plan
│   └── DECISIONS.md                # Architectural decision log
└── archive/
    ├── HOWARD_CLAUDE_CODE_HANDOFF.md   # Original design handoff document
    └── howard-complete-apk.jsx         # Original code viewer artifact
```

## Roadmap

| Phase | Goal | Status |
|---|---|---|
| 1. Repo coherence | Clean structure, honest docs, clear architecture | **In progress** |
| 2. Android proof of concept | Prove llama.cpp JNI + basic chat UI works | Not started |
| 3. Minimum working version | Downloadable model + local chat + cloud fallback | Not started |
| 4. System hardening | OpenClaw gateway, Telegram, agent tools, reliability | Not started |
| 5. Distribution | Play Store readiness, polish, optional monetisation | Not started |

See [docs/ROADMAP.md](docs/ROADMAP.md) for details.

## Disclaimer

This is an experimental project in early planning. It is not affiliated with OpenClaw, llama.cpp, or any referenced project. Use at your own risk.
