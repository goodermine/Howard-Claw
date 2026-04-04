#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Howard — Self-Contained Build Script
# Installs Android SDK + NDK, fetches dependencies, builds the APK.
# Run from repo root: bash scripts/build_apk.sh
#
# Fixes for common CI/agent failures:
#   - Uses $HOME/android-sdk (writable) not /opt/android-sdk
#   - Downloads SDK components via curl (avoids sdkmanager proxy issues)
#   - Uses versioned build-tools URL (r35.0.1 not r35 which 404s)
#   - Installs Ninja build system for CMake native builds
#   - Sets Gradle heap to 6GB for asset compression
#   - Configures proxy auth in gradle.properties
# ─────────────────────────────────────────────────────────────────────────────
set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

echo "========================================="
echo "  Howard — Full Build Script"
echo "========================================="

# ── 1. Check prerequisites ────────────────────────────────────────────────
echo ""
echo "[1/8] Checking prerequisites..."
java -version 2>&1 | head -1 || { echo "ERROR: Java 17+ required"; exit 1; }

# Install ninja if missing (required by CMake for native builds)
if ! command -v ninja &>/dev/null; then
    echo "  Installing ninja-build..."
    if command -v apt-get &>/dev/null; then
        sudo apt-get update -qq && sudo apt-get install -y -qq ninja-build 2>/dev/null || \
        apt-get update -qq && apt-get install -y -qq ninja-build 2>/dev/null || true
    elif command -v yum &>/dev/null; then
        sudo yum install -y ninja-build 2>/dev/null || true
    elif command -v brew &>/dev/null; then
        brew install ninja 2>/dev/null || true
    fi
    # Fallback: download ninja binary directly
    if ! command -v ninja &>/dev/null; then
        echo "  Downloading ninja binary..."
        curl -sL -o /tmp/ninja-linux.zip \
            "https://github.com/nicknisi/ninja/releases/download/v1.12.1/ninja-linux.zip" 2>/dev/null || \
        curl -sL -o /tmp/ninja-linux.zip \
            "https://github.com/nicknisi/ninja/releases/download/v1.11.1/ninja-linux.zip" 2>/dev/null || true
        if [ -f /tmp/ninja-linux.zip ]; then
            unzip -qo /tmp/ninja-linux.zip -d /usr/local/bin/ 2>/dev/null || \
            unzip -qo /tmp/ninja-linux.zip -d "$HOME/.local/bin/" 2>/dev/null || true
            export PATH="$HOME/.local/bin:$PATH"
        fi
    fi
fi
echo "  ninja: $(command -v ninja 2>/dev/null || echo 'not found (CMake will use make instead)')"

# ── 2. Install Android SDK if missing ──────────────────────────────────────
# Default to $HOME/android-sdk (writable without root), override with ANDROID_HOME
if [ -z "${ANDROID_HOME:-}" ]; then
    # Try /opt/android-sdk first if it exists and is writable, else use $HOME
    if [ -d "/opt/android-sdk" ] && [ -w "/opt/android-sdk" ]; then
        export ANDROID_HOME="/opt/android-sdk"
    elif [ -d "$HOME/android-sdk" ]; then
        export ANDROID_HOME="$HOME/android-sdk"
    else
        export ANDROID_HOME="$HOME/android-sdk"
    fi
fi
export ANDROID_SDK_ROOT="$ANDROID_HOME"
echo ""
echo "[2/8] Setting up Android SDK at $ANDROID_HOME..."
mkdir -p "$ANDROID_HOME"

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
    # Google names the extracted folder inconsistently — find and rename it
    for d in android-15 android-35-ext* android-VanillaIceCream; do
        [ -d "$d" ] && [ ! -d "android-35" ] && mv "$d" android-35
    done
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
    for d in android-14 android-UpsideDownCake; do
        [ -d "$d" ] && [ ! -d "34.0.0" ] && mv "$d" 34.0.0
    done
    cd "$PROJECT_DIR"
    echo "    OK"
fi

# Build-tools 35.0.0 (use r35.0.1 URL — the r35 URL returns 404)
if [ ! -f "$ANDROID_HOME/build-tools/35.0.0/aapt2" ]; then
    echo "  Downloading build-tools 35..."
    mkdir -p "$ANDROID_HOME/build-tools"
    # Try r35.0.1 first (known good), fall back to r35
    curl -sL -o /tmp/bt35.zip \
        "https://dl.google.com/android/repository/build-tools_r35.0.1_linux.zip" || \
    curl -sL -o /tmp/bt35.zip \
        "https://dl.google.com/android/repository/build-tools_r35-linux.zip"
    cd "$ANDROID_HOME/build-tools" && unzip -qo /tmp/bt35.zip
    for d in android-15 android-VanillaIceCream 35.0.1; do
        [ -d "$d" ] && [ ! -d "35.0.0" ] && mv "$d" 35.0.0
    done
    cd "$PROJECT_DIR"
    echo "    OK"
fi

# NDK r26b (26.1.10909125) — ~639MB download
if [ ! -d "$ANDROID_HOME/ndk/26.1.10909125" ]; then
    echo "  Downloading NDK r26b (639MB, this will take a few minutes)..."
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
echo "[3/8] Writing local.properties..."
echo "sdk.dir=$ANDROID_HOME" > "$PROJECT_DIR/local.properties"

# ── 4. Init project (llama.cpp submodule + keystore) ──────────────────────
echo ""
echo "[4/8] Initializing project..."
if [ ! -e "$PROJECT_DIR/app/src/main/cpp/llama.cpp/CMakeLists.txt" ]; then
    bash "$PROJECT_DIR/scripts/init_project.sh"
else
    echo "  Already initialized (llama.cpp submodule present)"
fi

# ── 5. Bundle assets (Node.js ARM64 + OpenClaw) ──────────────────────────
echo ""
echo "[5/8] Bundling assets..."
if [ ! -f "$PROJECT_DIR/app/src/main/assets/node/node" ]; then
    bash "$PROJECT_DIR/scripts/bundle_assets.sh"
else
    echo "  Assets already bundled"
    echo "    node: $(ls -lh "$PROJECT_DIR/app/src/main/assets/node/node" | awk '{print $5}')"
    echo "    openclaw: $(ls -lh "$PROJECT_DIR/app/src/main/assets/openclaw.tar.gz" | awk '{print $5}')"
fi

# ── 6. Ensure Gradle wrapper jar exists ──────────────────────────────────
echo ""
echo "[6/8] Checking Gradle wrapper..."
chmod +x "$PROJECT_DIR/gradlew" 2>/dev/null || true

if [ ! -f "$PROJECT_DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
    echo "  Downloading gradle-wrapper.jar..."
    GRADLE_VERSION=$(grep distributionUrl "$PROJECT_DIR/gradle/wrapper/gradle-wrapper.properties" | grep -oP 'gradle-\K[0-9.]+')
    curl -sL -o "$PROJECT_DIR/gradle/wrapper/gradle-wrapper.jar" \
        "https://raw.githubusercontent.com/gradle/gradle/v${GRADLE_VERSION}/gradle/wrapper/gradle-wrapper.jar" 2>/dev/null
    # Fallback: generate wrapper using system Gradle if available
    if [ ! -s "$PROJECT_DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
        if command -v gradle &>/dev/null; then
            echo "  Generating wrapper via system Gradle..."
            cd /tmp && mkdir -p _wrapper_gen && cd _wrapper_gen
            echo "rootProject.name='tmp'" > settings.gradle.kts
            touch build.gradle.kts
            gradle wrapper --gradle-version "$GRADLE_VERSION" 2>/dev/null
            cp gradle/wrapper/gradle-wrapper.jar "$PROJECT_DIR/gradle/wrapper/"
            cd "$PROJECT_DIR"
        else
            echo "  ERROR: gradle-wrapper.jar missing and no system Gradle to generate it."
            echo "  Download manually: https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
            exit 1
        fi
    fi
    echo "    OK"
else
    echo "  Wrapper jar present"
fi

# ── 7. Configure Gradle proxy + heap ─────────────────────────────────────
echo ""
echo "[7/8] Configuring Gradle..."

mkdir -p ~/.gradle

# Always write gradle.properties with increased heap for asset compression
# The 224MB of assets (Node.js + OpenClaw) needs significant heap to compress
GRADLE_PROPS="org.gradle.jvmargs=-Xmx6g -XX:+UseG1GC
android.useAndroidX=true
"

# If JAVA_TOOL_OPTIONS has proxy settings with google.com in nonProxyHosts,
# Gradle can't reach maven.google.com. Fix by writing gradle.properties
# with proxy auth but without the google bypass.
if echo "${JAVA_TOOL_OPTIONS:-}" | grep -q "proxyHost"; then
    echo "  Detected proxy environment, configuring proxy for Gradle..."
    PROXY_HOST=$(echo "$JAVA_TOOL_OPTIONS" | grep -oP '(?<=-Dhttp.proxyHost=)[^ ]+')
    PROXY_PORT=$(echo "$JAVA_TOOL_OPTIONS" | grep -oP '(?<=-Dhttp.proxyPort=)[^ ]+')
    PROXY_USER=$(echo "$JAVA_TOOL_OPTIONS" | grep -oP '(?<=-Dhttp.proxyUser=)[^ ]+' || true)
    PROXY_PASS=$(echo "$JAVA_TOOL_OPTIONS" | grep -oP '(?<=-Dhttp.proxyPassword=)[^ ]+' | head -1 || true)

    GRADLE_PROPS+="
systemProp.http.proxyHost=$PROXY_HOST
systemProp.http.proxyPort=$PROXY_PORT
systemProp.https.proxyHost=$PROXY_HOST
systemProp.https.proxyPort=$PROXY_PORT
systemProp.http.nonProxyHosts=localhost|127.0.0.1
systemProp.https.nonProxyHosts=localhost|127.0.0.1
systemProp.jdk.http.auth.tunneling.disabledSchemes=
systemProp.jdk.http.auth.proxying.disabledSchemes=
"
    if [ -n "$PROXY_USER" ]; then
        GRADLE_PROPS+="systemProp.http.proxyUser=$PROXY_USER
systemProp.http.proxyPassword=$PROXY_PASS
systemProp.https.proxyUser=$PROXY_USER
systemProp.https.proxyPassword=$PROXY_PASS
"
    fi
    echo "    Proxy configured"
fi

echo "$GRADLE_PROPS" > ~/.gradle/gradle.properties
echo "    Heap: 6GB, GC: G1GC"

# ── 8. Build ──────────────────────────────────────────────────────────────
echo ""
echo "[8/8] Building APK..."
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
