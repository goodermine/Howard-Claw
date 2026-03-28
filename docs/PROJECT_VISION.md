# Howard-Claw: Project Vision

## Mission

Build a native Android application that provides a self-contained AI agent environment on a phone. The app should combine local LLM inference, cloud LLM access, tool execution, and messaging integration without requiring Termux, root, or external server infrastructure for basic operation.

## Core Thesis

A phone is the most personal, always-available compute device most people own. If an AI agent can run on a phone — even partially — it becomes genuinely useful in ways that desktop-only or cloud-only agents cannot match: instant availability, personal context, notification-driven workflows, and physical portability.

The trade-off is that phones have constrained compute, aggressive power management, and sandboxed file systems. Howard accepts these constraints and designs around them rather than pretending they don't exist.

## What Howard Is

Howard is a native Android APK (not a Termux wrapper, not a web app) that:

1. Runs small LLM models (0.6B–3B parameters) locally via llama.cpp compiled through the Android NDK
2. Falls back to cloud LLMs (OpenAI, Anthropic, Gemini, etc.) via user-provided API keys when local models are insufficient
3. Parses agent tool commands from LLM output and executes them in a sandboxed environment
4. Connects to Telegram for remote task submission and response delivery
5. Optionally runs an OpenClaw gateway as a local Node.js service for advanced agent workflows

## What Howard Is Not

- Not a general-purpose terminal emulator (use Termux for that)
- Not a cloud service with a thin mobile client
- Not a wrapper around someone else's app
- Not a production-ready product (yet)
- Not an attempt to run 70B models on a phone

## Target Users

**Phase 1 (current):** The developer building it. This is a personal/experimental project.

**Phase 2 (future, if viable):** Technical users who want an AI agent on their phone and are comfortable with:
- Downloading GGUF model files (hundreds of MB)
- Entering API keys for cloud providers
- Understanding that local inference is slow
- Whitelisting apps from battery optimisation

**Phase 3 (aspirational):** Broader Android users via Play Store distribution with guided onboarding. This phase requires significant UX work and may not be the right goal — the complexity of the setup may permanently limit the audience to technical users.

## Scope Boundaries

**In scope:**
- Local LLM inference (small models)
- Cloud LLM inference (user's own keys)
- Agent tool dispatch (file operations, GitHub sync, messaging)
- Telegram bot integration
- On-device encrypted storage for all sensitive data
- Optional OpenClaw gateway

**Out of scope:**
- Running large models (7B+) as a primary use case
- Hosting a cloud backend for users
- Multi-user or team features
- iOS or desktop versions
- Training or fine-tuning models on device

## Success Criteria

The project succeeds if:
1. A user can install the APK, download a small model, and have a working local chat in under 5 minutes
2. Cloud fallback works seamlessly when local inference is too slow
3. At least 3 agent tools work reliably (file operations, Telegram send, GitHub sync)
4. The app survives background operation for at least 30 minutes on a mainstream device
5. All secrets (API keys, bot tokens) are encrypted on device and never transmitted to any Howard server
