# Howard-Claw: Project Vision

## Mission

Build a native Android application that functions as a portable AI agent node — a "Phone Howard" that handles quick-response field tasks with local LLM inference, cloud escalation, and tool execution. Developed primarily on a REDMAGIC 10S Pro, then released on the Play Store for users with suitable hardware.

## Core Thesis

A phone is the most personal, always-available compute device most people own. If an AI agent can run on a phone — even partially — it becomes genuinely useful in ways that desktop-only or cloud-only agents cannot match: instant availability, personal context, notification-driven workflows, and physical portability.

Howard is designed around the Howard ecosystem concept:
- **Workstation Howard** = heavy reasoning, deep research, large coding tasks, big workflows
- **Phone Howard** = quick-response field instance for lighter jobs

The phone is not a workstation replacement. It is a dedicated field node with local intelligence for fast tasks and cloud escalation for everything else.

## What Howard Is

Howard is a native Android APK (not a Termux wrapper, not a web app) that:

1. Runs LLM models locally via llama.cpp compiled through the Android NDK — up to 7B–8B on high-RAM devices, 0.6B–3B on standard devices
2. Falls back to cloud LLMs via user-provided API keys (OpenAI, Anthropic, Gemini, OpenRouter, Ollama)
3. Supports free cloud models via OpenRouter's free tier — no API key cost for basic usage
4. Supports self-hosted models via Ollama API — for users running inference on a home server or VPS
5. Parses agent tool commands from LLM output and executes them in a sandboxed environment
6. Connects to Telegram for remote task submission and response delivery
7. Integrates with GitHub via personal access token for push/pull operations
8. Optionally runs an OpenClaw gateway as a local Node.js service for advanced agent workflows

## What Howard Is Not

- Not a general-purpose terminal emulator (use Termux for that)
- Not a cloud service with a thin mobile client
- Not a wrapper around someone else's app
- Not a workstation replacement — it's a field node
- Not an attempt to run 70B models on a phone

## Target Users

**Phase 1 (current):** The developer. Personal tool built on a REDMAGIC 10S Pro (24GB RAM, Snapdragon 8 Elite, active cooling). Developed for own use first.

**Phase 2:** Technical users with suitable hardware (12GB+ RAM, flagship SoC) who want an AI agent on their phone and are comfortable with downloading GGUF models, entering API keys, and understanding local inference trade-offs.

**Phase 3 (aspirational):** Broader Android users via Play Store with guided onboarding. Minimum hardware: 8GB RAM, Snapdragon 7-series or equivalent.

## Phone Howard — Practical Role

The phone instance handles:
- Email triage and short drafting
- Lightweight coding edits
- Simple webpage updates
- Status checks and monitoring
- Task routing to workstation Howard
- Prompt drafting
- Quick local model experiments
- GitHub push/pull for on-the-go commits

Tasks that should escalate to workstation Howard:
- Deep research and analysis
- Large-scale code generation
- Complex multi-step workflows
- Heavy reasoning tasks
- Anything requiring models larger than 8B

## Inference Provider Model

Howard supports multiple inference backends with zero vendor lock-in:

| Provider | Type | Cost | Use Case |
|---|---|---|---|
| Local (llama.cpp) | On-device | Free | Fast small tasks, offline operation |
| OpenRouter (free tier) | Cloud | Free | Better quality than local, no API cost |
| Ollama | Self-hosted | Free (own hardware) | Home server inference, larger models |
| OpenAI | Cloud | Paid | GPT-4o for high-quality tasks |
| Anthropic | Cloud | Paid | Claude for reasoning-heavy tasks |
| Gemini | Cloud | Paid/free tier | Google models |
| OpenRouter (paid) | Cloud | Paid | Access to any model via single API |

This gives users a clear cost ladder: free local → free cloud (OpenRouter) → free self-hosted (Ollama) → paid cloud.

## Scope Boundaries

**In scope:**
- Local LLM inference (llama.cpp, GGUF models)
- Cloud LLM inference (OpenAI, Anthropic, Gemini, OpenRouter, Ollama)
- Agent tool dispatch (file operations, GitHub sync, messaging)
- GitHub integration (push/pull via personal access token)
- Telegram bot integration
- On-device encrypted storage for all sensitive data (API keys, tokens)
- Optional OpenClaw gateway

**Out of scope:**
- Running models larger than ~13B as a primary use case
- Hosting a cloud backend for users
- Multi-user or team features
- iOS or desktop versions
- Training or fine-tuning models on device

## Success Criteria

The project succeeds if:
1. The developer can use it daily on the REDMAGIC 10S Pro as a functional field node
2. A user can install the APK, pick an inference backend (local, free cloud, or paid cloud), and have a working chat quickly
3. GitHub push/pull works via personal access token
4. At least 3 agent tools work reliably (file operations, Telegram send, GitHub sync)
5. The app survives background operation for at least 30 minutes on the primary device
6. All secrets (API keys, bot tokens, GitHub tokens) are encrypted on device and never transmitted to any Howard server
