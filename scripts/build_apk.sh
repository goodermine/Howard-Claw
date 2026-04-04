#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Howard — Self-Contained Build Script
# Installs Android SDK + NDK, fetches dependencies, builds the APK.
# Run from repo root: bash scripts/build_apk.sh
# ─────────────────────────────────────────────────────────────────────────────
set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

echo "========================================="
echo "  Howard — Full Build Script"
echo "========================================="

# ── 1. Check Java ──────────────────────────────────────────────────────────
echo ""
echo "[1/7] Checking Java..."
java -version 2>&1 | head -1 || { echo "ERROR: Java 17+ required"; exit 1; }

# ── 2. Install Android SDK if missing ──────────────────────────────────────
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
echo ""
echo "[2/7] Setting up Android SDK at $ANDROID_HOME..."

# Command-line tools
if [ ! -f "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "  Downloading command-line tools..."
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    curl -sL -o /tmp/cmdline-tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    cd "$ANDROID_HOME/cmdline-tools" && unzip -qo /tmp/cmdline-tools.zip
    mv cmdline-tools latest 2>/dev/null || true
    cd "$PROJECT_DIR"
fi

# Platform 35 (direct download — avoids sdkmanager proxy issues)
if [ ! -f "$ANDROID_HOME/platforms/android-35/android.jar" ]; then
    echo "  Downloading platform 35..."
    mkdir -p "$ANDROID_HOME/platforms"
    curl -sL -o /tmp/platform-35.zip \
        "https://dl.google.com/android/repository/platform-35_r02.zip"
    cd "$ANDROID_HOME/platforms" && unzip -qo /tmp/platform-35.zip
    [ -d "android-15" ] && mv android-15 android-35
    cd "$PROJECT_DIR"
    echo "    OK"
fi

# Build-tools 34.0.0 (required by AGP 8.7.3)
if [ ! -f "$ANDROID_HOME/build-tools/34.0.0/aapt2" ]; then
    echo "  Downloading build-tools 34..."
    mkdir -p "$ANDROID_HOME/build-tools"
    curl -sL -o /tmp/bt34.zip \
        "https://dl.google.com/android/repository/build-tools_r34-linux.zip"
    cd "$ANDROID_HOME/build-tools" && unzip -qo /tmp/bt34.zip
    [ -d "android-14" ] && mv android-14 34.0.0
    cd "$PROJECT_DIR"
    echo "    OK"
fi

# Build-tools 35.0.0
if [ ! -f "$ANDROID_HOME/build-tools/35.0.0/aapt2" ]; then
    echo "  Downloading build-tools 35..."
    curl -sL -o /tmp/bt35.zip \
        "https://dl.google.com/android/repository/build-tools_r35-linux.zip"
    cd "$ANDROID_HOME/build-tools" && unzip -qo /tmp/bt35.zip
    [ -d "android-15" ] && mv android-15 35.0.0
    cd "$PROJECT_DIR"
    echo "    OK"
fi

# NDK r26b (26.1.10909125) — ~639MB download
if [ ! -d "$ANDROID_HOME/ndk/26.1.10909125" ]; then
    echo "  Downloading NDK r26b (639MB, this will take a minute)..."
    mkdir -p "$ANDROID_HOME/ndk"
    curl -sL -o /tmp/ndk.zip \
        "https://dl.google.com/android/repository/android-ndk-r26b-linux.zip"
    cd "$ANDROID_HOME/ndk" && unzip -qo /tmp/ndk.zip
    mv android-ndk-r26b 26.1.10909125
    cd "$PROJECT_DIR"
    echo "    OK"
fi

# Accept licenses
mkdir -p "$ANDROID_HOME/licenses"
echo -e "\n24333f8a63b6825ea9c5514f83c2829b004d1fee" > "$ANDROID_HOME/licenses/android-sdk-license"
echo -e "\n84831b9409646a918e30573bab4c9c91346d8abd" > "$ANDROID_HOME/licenses/android-sdk-preview-license"

echo "  SDK ready"

# ── 3. local.properties ───────────────────────────────────────────────────
echo ""
echo "[3/7] Writing local.properties..."
echo "sdk.dir=$ANDROID_HOME" > "$PROJECT_DIR/local.properties"

# ── 4. Init project (llama.cpp submodule + keystore) ──────────────────────
echo ""
echo "[4/7] Initializing project..."
if [ ! -d "$PROJECT_DIR/app/src/main/cpp/llama.cpp/.git" ]; then
    bash "$PROJECT_DIR/scripts/init_project.sh"
else
    echo "  Already initialized (llama.cpp submodule present)"
fi

# ── 5. Bundle assets (Node.js ARM64 + OpenClaw) ──────────────────────────
echo ""
echo "[5/7] Bundling assets..."
if [ ! -f "$PROJECT_DIR/app/src/main/assets/node/node" ]; then
    bash "$PROJECT_DIR/scripts/bundle_assets.sh"
else
    echo "  Assets already bundled"
    echo "    node: $(ls -lh "$PROJECT_DIR/app/src/main/assets/node/node" | awk '{print $5}')"
    echo "    openclaw: $(ls -lh "$PROJECT_DIR/app/src/main/assets/openclaw.tar.gz" | awk '{print $5}')"
fi

# ── 6. Configure Gradle proxy (handles Claude Code / CI environments) ────
echo ""
echo "[6/7] Configuring Gradle..."
chmod +x "$PROJECT_DIR/gradlew"

# If JAVA_TOOL_OPTIONS has proxy settings with google.com in nonProxyHosts,
# Gradle can't reach maven.google.com. Fix by writing gradle.properties
# with proxy auth but without the google bypass.
if echo "${JAVA_TOOL_OPTIONS:-}" | grep -q "proxyHost"; then
    echo "  Detected proxy environment, configuring gradle.properties..."
    PROXY_HOST=$(echo "$JAVA_TOOL_OPTIONS" | grep -oP '(?<=-Dhttp.proxyHost=)[^ ]+')
    PROXY_PORT=$(echo "$JAVA_TOOL_OPTIONS" | grep -oP '(?<=-Dhttp.proxyPort=)[^ ]+')
    PROXY_USER=$(echo "$JAVA_TOOL_OPTIONS" | grep -oP '(?<=-Dhttp.proxyUser=)[^ ]+')
    PROXY_PASS=$(echo "$JAVA_TOOL_OPTIONS" | grep -oP '(?<=-Dhttp.proxyPassword=)[^ ]+' | head -1)

    mkdir -p ~/.gradle
    cat > ~/.gradle/gradle.properties << PROPEOF
systemProp.http.proxyHost=$PROXY_HOST
systemProp.http.proxyPort=$PROXY_PORT
systemProp.http.proxyUser=$PROXY_USER
systemProp.http.proxyPassword=$PROXY_PASS
systemProp.http.nonProxyHosts=localhost|127.0.0.1

systemProp.https.proxyHost=$PROXY_HOST
systemProp.https.proxyPort=$PROXY_PORT
systemProp.https.proxyUser=$PROXY_USER
systemProp.https.proxyPassword=$PROXY_PASS
systemProp.https.nonProxyHosts=localhost|127.0.0.1

systemProp.jdk.http.auth.tunneling.disabledSchemes=
systemProp.jdk.http.auth.proxying.disabledSchemes=

org.gradle.jvmargs=-Xmx4g
android.useAndroidX=true
PROPEOF
    echo "    Proxy configured for Gradle"
fi

# ── 7. Build ──────────────────────────────────────────────────────────────
echo ""
echo "[7/7] Building APK..."
cd "$PROJECT_DIR"

# Unset JAVA_TOOL_OPTIONS so Gradle uses its own gradle.properties proxy config
# (avoids nonProxyHosts conflicts that block maven.google.com)
JAVA_TOOL_OPTIONS="" ./gradlew assembleDebug --no-daemon

APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
    SIZE=$(du -h "$APK" | cut -f1)
    echo ""
    echo "========================================="
    echo "  BUILD SUCCESSFUL"
    echo "  APK: $APK"
    echo "  Size: $SIZE"
    echo ""
    echo "  Install via ADB:"
    echo "  adb install $APK"
    echo "========================================="
else
    echo ""
    echo "BUILD FAILED — no APK produced"
    exit 1
fi
