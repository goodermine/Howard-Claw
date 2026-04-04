# Troubleshooting (Prototype / Pre-implementation)

> This repository is currently in planning mode, but these runbook notes document the most likely causes of the two field issues reported during APK smoke tests:
> 1) model download stuck at 0%, and
> 2) local OpenClaw gateway failing to come online after repeated attempts.

## 1) Model download stuck at 0%

### Most likely causes
- Download worker cannot write to the selected directory (scoped-storage permission mismatch).
- DNS/TLS handshake failure to the model host while app network checks still pass.
- Disk-space preflight was skipped and the temporary file allocation fails immediately.
- `content-length` is missing/invalid and progress UI stays at 0% even though bytes are arriving.

### Fast checks
1. Verify free space is at least **2× model size** (download + unpack temp).
2. Confirm app has storage permission for the destination tree.
3. Capture first failure from `logcat`:
   - `DownloadManager`
   - `SSLHandshakeException`
   - `EACCES`
   - `ENOSPC`
4. Retry with a smaller model to rule out storage and timeout limits.

### Recommended implementation guardrails
- Always run preflight checks (storage, write access, network type).
- Write to app-private cache first, then atomically move to final location.
- Report real transferred bytes in UI even when total size is unknown.
- Add resumable downloads with checksum verification.

## 2) OpenClaw gateway does not start

### Symptom
Onboarding reaches the gateway phase and fails after a fixed retry budget (for example, 10 attempts).

### Most likely causes
- Node process starts, but child gateway process exits before health endpoint is ready.
- Port `18789` bind race (existing process, delayed release, or startup ordering).
- Health check probes too early and times out before first successful boot.
- On-device process restrictions kill the service between retries.

### Fast checks
1. Confirm no other process is occupying `127.0.0.1:18789`.
2. Log child process stdout/stderr to a persistent file and inspect the first crash.
3. Increase startup timeout window and use exponential backoff instead of fixed-interval retries.
4. Verify gateway health endpoint (`/health`) separately from websocket readiness.

### Recommended implementation guardrails
- Add phased readiness:
  1. Node runtime ready
  2. OpenClaw server process spawned
  3. HTTP health OK
  4. Websocket probe succeeds
- Surface **exact failure reason** in onboarding UI (port in use, missing file, permission error, timeout).
- Keep a short rolling diagnostic log in app storage for support handoff.
- Add a one-tap “Export diagnostics” action from onboarding failure state.

## Suggested next engineering task
Implement a startup diagnostics object returned by the gateway bootstrapper:

```kotlin
data class GatewayStartupResult(
  val phase: String,
  val attempts: Int,
  val port: Int,
  val lastError: String?,
  val logPath: String?
)
```

This allows onboarding to display actionable errors instead of a generic "gateway did not come online" message.
