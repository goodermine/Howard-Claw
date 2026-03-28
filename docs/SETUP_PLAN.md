# Howard-Claw: Setup Plan

## Prerequisites

This document describes how to build Howard once the Android project exists. The project is currently in the architecture/planning phase — these steps cannot be executed yet.

## Development Environment

| Requirement | Version | Notes |
|---|---|---|
| Android Studio | Hedgehog (2023.1.1) or newer | Required for NDK/CMake support |
| JDK | 17+ | Bundled with Android Studio |
| Android SDK | API 35 (target), API 26 (min) | Install via SDK Manager |
| Android NDK | r26+ | Required for llama.cpp native build |
| CMake | 3.22.1+ | Install via SDK Manager → SDK Tools |
| Git | 2.x | For llama.cpp submodule |
| Physical ARM64 device | Android 8.0+ | Emulator cannot test native inference performance |

## Build Sequence

### Step 1: Clone and Initialise

```bash
git clone https://github.com/goodermine/Howard-Claw.git
cd Howard-Claw
git submodule update --init --recursive  # pulls llama.cpp
```

### Step 2: Configure Local Properties

Create `local.properties` in the project root:

```properties
sdk.dir=/path/to/Android/Sdk
ndk.dir=/path/to/Android/Sdk/ndk/26.x.xxxxx
```

### Step 3: Download Fonts

Download from Google Fonts and place in `app/src/main/res/font/`:
- Outfit: regular, semibold, bold
- Source Sans 3: regular, medium
- JetBrains Mono: regular

### Step 4: Build

```bash
# Debug build (first build takes 8-15 minutes due to llama.cpp compilation)
./gradlew assembleDebug

# Release build (requires signing configuration)
./gradlew assembleRelease
```

### Step 5: Install and Test

```bash
# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk

# Watch logs
adb logcat -s Howard:*
```

### Step 6: Verify on Device

1. App launches → onboarding appears
2. Download a test model (Qwen3 0.6B, ~640MB)
3. Chat screen appears → send test message
4. Tokens stream into the chat UI
5. Check notification: "Howard online"

## Bundle OpenClaw Assets (Phase 4)

When OpenClaw integration is implemented:

```bash
# Run on a Linux machine (not on the phone)
bash scripts/bundle_assets.sh
# Downloads Node.js 22.x ARM64 binary → app/src/main/assets/node/node
# Packs OpenClaw npm package → app/src/main/assets/openclaw.tar.gz
```

This adds ~130MB to the APK. Use Android App Bundle (AAB) format for Play Store distribution.

## Signing

### Debug (development)
Generated automatically by Android Studio or via:
```bash
keytool -genkey -v -keystore howard-debug.jks -keyalg RSA -keysize 2048 -validity 10000 -alias howard
```

### Release (distribution)
Generate a separate release keystore. **Never commit the release keystore or its password to version control.**

```bash
keytool -genkey -v -keystore howard-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias howard-release
```

Configure in `app/build.gradle.kts`:
```kotlin
signingConfigs {
    create("release") {
        storeFile = file(System.getenv("HOWARD_KEYSTORE_PATH") ?: "howard-release.jks")
        storePassword = System.getenv("HOWARD_KEYSTORE_PASSWORD") ?: ""
        keyAlias = System.getenv("HOWARD_KEY_ALIAS") ?: "howard-release"
        keyPassword = System.getenv("HOWARD_KEY_PASSWORD") ?: ""
    }
}
```

Use environment variables for signing credentials. Never hardcode passwords.

## CI/CD (Future)

When CI is set up, the GitHub Actions workflow should:
1. Check out code with submodules
2. Set up JDK 17 + Android SDK + NDK
3. Run `./gradlew assembleDebug`
4. Run `./gradlew testDebug` (unit tests)
5. Optionally upload APK as artifact
