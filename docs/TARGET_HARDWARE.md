# Howard-Claw: Target Hardware

## Primary Development Device

**REDMAGIC 10S Pro (Dusk / high-end configuration)**

| Spec | Value |
|---|---|
| SoC | Qualcomm Snapdragon 8 Elite Leading Version |
| CPU | Up to 4.47 GHz |
| GPU | Adreno |
| Secondary chip | RedCore R3 Pro |
| RAM | 24 GB LPDDR5T |
| Storage | 1 TB UFS 4.1 Pro |
| Display | 6.85" AMOLED, 2688 x 1216, 144 Hz |
| Battery | 7,050 mAh |
| Charging | 80 W wired |
| Cooling | ICE-X active cooling, Liquid Metal 2.0, large vapour chamber, 23,000 RPM fan |
| OS | Android 15 |
| SIM | Dual nano-SIM (no eSIM) |

## Why This Device

This is not a random test phone. It was chosen specifically for Howard because:

1. **24GB RAM** — the single most important spec. Allows 7B–8B Q4_K_M models to run with ~19GB headroom after the OS. Most phones max at 8–12GB, which limits inference to 3B models.

2. **Active cooling** — the fan, vapour chamber, and liquid metal cooling allow sustained CPU performance during inference. Normal phones thermal-throttle within 2–5 minutes, dropping to 50–70% of peak speed. The REDMAGIC can sustain peak performance for extended sessions.

3. **Snapdragon 8 Elite (4.47 GHz)** — the fastest available ARM64 CPU, directly translating to higher tokens/sec during inference.

4. **1TB UFS 4.1 Pro** — stores dozens of GGUF models simultaneously. Fast sequential reads speed up model loading.

5. **7,050 mAh battery** — extended inference sessions without charging. Estimated 3–5 hours of continuous local inference.

6. **RedCore R3 Pro** — handles background tasks while the main SoC focuses on inference.

## Role in the Howard Ecosystem

```
┌───────────────────────────────────────────────┐
│          WORKSTATION HOWARD                    │
│  Heavy reasoning, deep research, large code,  │
│  big workflows, primary source of truth        │
└───────────────────┬───────────────────────────┘
                    │ escalation
                    ▼
┌───────────────────────────────────────────────┐
│          PHONE HOWARD (REDMAGIC 10S Pro)       │
│  Quick response, field instance, lighter jobs: │
│  - Email triage, short drafting                │
│  - Lightweight coding edits                    │
│  - Simple webpage updates                      │
│  - Status checks, monitoring                   │
│  - Task routing back to workstation            │
│  - Prompt drafting                             │
│  - Quick local model experiments               │
│  - GitHub push/pull for on-the-go commits      │
└───────────────────────────────────────────────┘
```

The phone is a **portable field operator with local reflex intelligence and cloud escalation**, not a workstation replacement.

## Play Store Target Hardware Tiers

When distributing via Play Store, Howard should communicate device suitability clearly:

| Tier | RAM | SoC Class | Experience |
|---|---|---|---|
| **Full** | 16GB+ | Snapdragon 8 Gen 2+ / Dimensity 9000+ | 7B–8B local models, all features, sustained inference |
| **Standard** | 8–12GB | Snapdragon 7-series+ / Dimensity 7000+ | 3B local models, cloud fallback for larger tasks |
| **Basic** | 6–8GB | Mid-range 2024+ | 0.6B–1.7B local models, cloud-primary |
| **Unsupported** | <6GB | — | Cloud-only, local inference disabled |

`DeviceDetector` filters the model registry based on available RAM and shows the appropriate tier to the user during onboarding.

## Expected Performance (to be validated in Phase 2)

| Model | Size (Q4_K_M) | RAM Usage | REDMAGIC 10S Pro (est.) | 8GB phone (est.) |
|---|---|---|---|---|
| Qwen3 0.6B | ~400MB | ~1GB | 20–30 tok/s | 10–15 tok/s |
| Qwen3 1.7B | ~1GB | ~2GB | 15–25 tok/s | 6–10 tok/s |
| SmolLM3 3B | ~2GB | ~3GB | 10–18 tok/s | 4–7 tok/s |
| Qwen3 4B | ~2.5GB | ~3.5GB | 8–15 tok/s | 3–5 tok/s |
| Llama 3.2 7B | ~4GB | ~5GB | 6–12 tok/s | 2–4 tok/s (marginal) |
| Llama 3.1 8B | ~4.5GB | ~5.5GB | 5–10 tok/s | Not recommended |
| 13B (experimental) | ~7.5GB | ~9GB | 3–5 tok/s | Not possible |

These are estimates based on published llama.cpp benchmarks for similar hardware. Actual numbers must be measured in Phase 2 and documented here.
