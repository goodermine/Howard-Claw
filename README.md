# Howard-Claw

A native Android application for on-device LLM inference with agent capabilities, cloud LLM fallback, GitHub integration, and optional OpenClaw gateway. Built on a REDMAGIC 10S Pro as a portable AI agent field node.

## Project Status: Architecture & Planning Phase

This repository is in the **design and planning stage**. No buildable Android project exists yet. The `docs/` directory contains the technical architecture, feasibility analysis, and implementation roadmap. The `archive/` directory preserves original design materials.

**What exists today:**
- Detailed architecture documentation
- Android feasibility analysis (validated against real target hardware)
- Source code designs (Kotlin) for all major components — preserved as reference
- Phased implementation roadmap

**What does not exist yet:**
- Android Studio project / Gradle build system
- Compilable source code
- Working APK
- Tests or CI

## What Howard-Claw Is

Howard is a "Phone Howard" — a portable AI agent node that handles quick-response field tasks. It is part of a broader Howard ecosystem where the workstation handles heavy work and the phone handles lighter, on-the-go tasks.

The app combines:

1. **Local LLM inference** — llama.cpp via Android NDK. Runs GGUF models directly on ARM64 hardware. Up to 7B–8B on the primary device (24GB RAM), 0.6B–3B on standard phones.
2. **Cloud LLM fallback** — OpenAI, Anthropic, Gemini, OpenRouter (including free models), and Ollama (self-hosted). User provides their own keys.
3. **Agent tool dispatch** — LLM output is parsed for `[CMD: tool_name args]` tokens triggering sandboxed tool execution.
4. **GitHub integration** — Push/pull repos via personal access token. On-the-go commits.
5. **Telegram integration** — Bot token polling for receiving tasks remotely.
6. **OpenClaw gateway** (optional, Phase 4) — Bundled Node.js + OpenClaw for advanced agent workflows.

## Inference Providers

Howard supports multiple backends with zero vendor lock-in:

| Provider | Cost | Use Case |
|---|---|---|
| Local (llama.cpp) | Free | Fast small tasks, offline operation |
| OpenRouter (free tier) | Free | Better quality than small local models, no API cost |
| Ollama (self-hosted) | Free (own hardware) | Home server inference, larger models |
| OpenAI / Anthropic / Gemini | Paid | High-quality reasoning and generation |

## Primary Development Device

**REDMAGIC 10S Pro** — 24GB RAM, Snapdragon 8 Elite, active cooling, 1TB storage, 7,050 mAh battery. This phone can run 7B–8B models with sustained performance. See [docs/TARGET_HARDWARE.md](docs/TARGET_HARDWARE.md).

## Limitations

- **Local inference is CPU-only.** No GPU offloading on Android NDK. Expect 8–15 tok/s on Snapdragon 8 Elite, 3–8 tok/s on older flagships.
- **Background persistence is OEM-dependent.** ForegroundService + wake locks work but require user whitelisting on most OEMs.
- **Scoped storage limits file operations.** Agent tools can only access app-internal storage or SAF-granted directories.
- **This is experimental software.** Not production-ready.

## Repository Structure

```
Howard-Claw/
├── README.md                       # This file
├── .gitignore                      # Android/Kotlin/Node ignores
├── docs/
│   ├── PROJECT_VISION.md           # Mission, scope, target users
│   ├── ARCHITECTURE.md             # Full technical architecture
│   ├── ANDROID_FEASIBILITY.md      # Honest Android constraints
│   ├── TARGET_HARDWARE.md          # Primary device specs and role
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
| 1. Repo coherence | Clean structure, honest docs, clear architecture | **Complete** |
| 2. Android proof of concept | Prove llama.cpp JNI + basic chat UI works on REDMAGIC | Not started |
| 3. Minimum working version | Local chat + cloud fallback + GitHub integration | Not started |
| 4. System hardening | OpenClaw gateway, Telegram, agent tools, reliability | Not started |
| 5. Distribution | Play Store for users with suitable hardware | Not started |

See [docs/ROADMAP.md](docs/ROADMAP.md) for details.

## Disclaimer

This is an experimental project in early planning. It is not affiliated with OpenClaw, llama.cpp, or any referenced project. Use at your own risk.
