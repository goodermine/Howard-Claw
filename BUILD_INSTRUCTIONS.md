## Howard-Claw: Download, Build & Run

### Prerequisites

- **Linux or macOS** host (ARM64 or x86_64)
- **Java 17** (`sudo apt install openjdk-17-jdk`)
- **Android SDK** with:
  - Build Tools 34.0.0+
  - Platform android-34
  - NDK 27+ (for llama.cpp JNI)
- **Git**, **curl**, **tar**, **dpkg-deb** (for asset bundling)

### 1. Clone the Repository

    git clone https://github.com/goodermine/Howard-Claw.git
    cd Howard-Claw
    git checkout claude/audit-android-openclaw-OjMw0

### 2. Set Up Android SDK

If you don't have the SDK, use sdkmanager:

    export ANDROID_HOME=$HOME/android-sdk
    mkdir -p "$ANDROID_HOME"
    sdkmanager "platforms;android-34" "build-tools;34.0.0" "ndk;27.0.12077973"

Create local.properties in the project root:

    echo "sdk.dir=$ANDROID_HOME" > local.properties

### 3. Bundle Runtime Assets

The APK requires Termux bootstrap + Node.js + OpenClaw bundled in app/src/main/assets/. Run:

    mkdir -p app/src/main/assets

    # bootstrap-aarch64.zip (Termux environment, ~30MB)
    curl -L -o app/src/main/assets/bootstrap-aarch64.zip \
      "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip"

    # node-supplement.tar.gz (Node.js for Termux)
    TMPDIR=$(mktemp -d)
    cd "$TMPDIR"
    for pkg in nodejs-lts npm c-ares libicu libsqlite; do
      curl -LO "https://packages.termux.dev/apt/termux-main/pool/main/${pkg:0:1}/${pkg}/${pkg}_latest_aarch64.deb" 2>/dev/null || true
    done
    mkdir -p stage
    for deb in *.deb; do
      [ -f "$deb" ] && dpkg-deb -x "$deb" stage/
    done
    cd stage
    tar -czf "$OLDPWD/../node-supplement.tar.gz" -C data/data/com.termux/files/usr . 2>/dev/null || \
    tar -czf "$OLDPWD/../node-supplement.tar.gz" -C usr . 2>/dev/null || true
    cd "$OLDPWD/.."
    cp node-supplement.tar.gz app/src/main/assets/ 2>/dev/null || true
    rm -rf "$TMPDIR"

    # openclaw.tar.gz (OpenClaw npm package)
    TMPDIR=$(mktemp -d)
    cd "$TMPDIR"
    npm pack openclaw 2>/dev/null && \
    tar xzf openclaw-*.tgz && \
    mv package openclaw && \
    tar -czf openclaw.tar.gz openclaw
    cp openclaw.tar.gz "$OLDPWD/app/src/main/assets/" 2>/dev/null || true
    cd "$OLDPWD"
    rm -rf "$TMPDIR"

Alternatively, if scripts/bundle_assets.sh exists:

    chmod +x scripts/bundle_assets.sh
    ./scripts/bundle_assets.sh

### 4. Build the APK

    ./gradlew assembleDebug

The APK will be at:

    app/build/outputs/apk/debug/app-debug.apk

### 5. Install on Android Device

    adb install app/build/outputs/apk/debug/app-debug.apk

### Key Architecture Notes

- targetSdk 28 — Required, allows executing Termux binaries from app data dirs
- BootstrapInstaller.kt — Extracts Termux environment (bin/sh, apt, dpkg) from bootstrap zip
- GatewayService.kt — Runs Node.js + OpenClaw as subprocess via Termux shell
- EngineRouter.kt — Routes inference to Local GGUF (llama.cpp JNI) or Cloud (OpenAI/ChatGPT, Claude, etc.)
- ModelSwitcher — In-chat chip bar, user taps "Local GGUF" or "ChatGPT" to switch

### 6. First Launch

1. Notice — accept terms
2. Model Download — pick a local GGUF model (Qwen3 0.6B for low-RAM, Llama 3.1 8B for flagship)
3. Cloud Keys — enter OpenAI API key for ChatGPT access (optional)
4. OpenClaw Gateway — bootstrap extracts automatically; skip if no assets bundled
5. Telegram — optional bot integration
6. Chat — switch between "Local GGUF" and "ChatGPT" via the chip bar at top
