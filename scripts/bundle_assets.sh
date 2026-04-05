#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Howard — Asset Bundling Script
# Run on a Linux machine before building the APK with OpenClaw support.
#
# Downloads an Android-compatible (Bionic libc) Node.js ARM64 binary from
# Termux packages and the OpenClaw npm package, then places them for the
# Android build:
#   - Node.js binary   → jniLibs/arm64-v8a/libnode.so  (native lib dir)
#   - Node.js deps     → jniLibs/arm64-v8a/lib*.so
#   - OpenClaw package → assets/openclaw.tar.gz
#
# IMPORTANT: Standard Linux ARM64 Node.js binaries (from nodejs.org) will NOT
# run on Android — Android uses Bionic libc, not glibc. We use Termux's builds
# which are compiled against Bionic and can be executed via ProcessBuilder.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

ASSETS_DIR="app/src/main/assets"
JNILIBS_DIR="app/src/main/jniLibs/arm64-v8a"
TERMUX_REPO="https://packages.termux.dev/apt/termux-main/pool/main"
WORK_DIR="/tmp/howard-bundle-$$"

# ── Termux package versions (update these as needed) ────────────────────────
# Browse available versions at:
#   https://packages.termux.dev/apt/termux-main/pool/main/n/nodejs/
#   https://packages.termux.dev/apt/termux-main/pool/main/libi/libicu/
NODE_DEB="nodejs_22.12.0_aarch64.deb"
LIBICU_DEB="libicu_74.2-1_aarch64.deb"

echo "═══════════════════════════════════════════════"
echo "  HOWARD — Bundling Assets (Android/Bionic)"
echo "═══════════════════════════════════════════════"

mkdir -p "$ASSETS_DIR" "$JNILIBS_DIR" "$WORK_DIR"
trap 'rm -rf "$WORK_DIR"' EXIT

# ── 1. Download Node.js ARM64 (Termux/Bionic build) ────────────────────────
echo ""
echo "[1/4] Downloading Node.js (Termux/Android ARM64)..."
curl -fL -o "$WORK_DIR/node.deb" "$TERMUX_REPO/n/nodejs/$NODE_DEB"

# Extract node binary from .deb (ar + tar)
mkdir -p "$WORK_DIR/node-extract"
(cd "$WORK_DIR/node-extract" && ar x ../node.deb && tar xf data.tar.* 2>/dev/null)
NODE_BIN=$(find "$WORK_DIR/node-extract" -name "node" -type f -path "*/bin/node" | head -1)

if [ -z "$NODE_BIN" ]; then
    echo "ERROR: Could not find node binary in Termux .deb"
    exit 1
fi

# Place as libnode.so in jniLibs (Android requires lib*.so naming for
# executables in nativeLibraryDir; see Android 10+ W^X restrictions)
cp "$NODE_BIN" "$JNILIBS_DIR/libnode.so"
chmod +x "$JNILIBS_DIR/libnode.so"
echo "    Node binary: $(ls -lh "$JNILIBS_DIR/libnode.so" | awk '{print $5}')"
echo "    Type: $(file "$JNILIBS_DIR/libnode.so" | grep -o 'ELF.*' | head -c 80)"

# ── 2. Download Node.js shared library dependencies ────────────────────────
echo ""
echo "[2/4] Downloading Node.js dependencies (ICU libs)..."

mkdir -p "$WORK_DIR/libicu-extract"
curl -fL -o "$WORK_DIR/libicu.deb" "$TERMUX_REPO/libi/libicu/$LIBICU_DEB"
(cd "$WORK_DIR/libicu-extract" && ar x ../libicu.deb && tar xf data.tar.* 2>/dev/null)

# Copy all shared libs from extracted deps to jniLibs
find "$WORK_DIR/libicu-extract" -name "*.so*" -type f | while read -r lib; do
    basename=$(basename "$lib")
    # Strip version suffixes for Android (libicuuc.so.74 -> libicuuc.so)
    target="${basename%%\.so*}.so"
    cp "$lib" "$JNILIBS_DIR/$target"
    echo "    Bundled: $target ($(ls -lh "$lib" | awk '{print $5}'))"
done

echo "    Dependencies placed in $JNILIBS_DIR/"

# ── 3. Pack OpenClaw as tar.gz ───────────────────────────────────────────────
echo ""
echo "[3/4] Packing OpenClaw npm package..."

npm install -g openclaw@latest --prefix "$WORK_DIR/openclaw_install"
mkdir -p "$WORK_DIR/openclaw_bundle"
cp -r "$WORK_DIR/openclaw_install/lib/node_modules/openclaw" "$WORK_DIR/openclaw_bundle/openclaw"

cd "$WORK_DIR/openclaw_bundle/openclaw"
npm prune --omit=dev 2>/dev/null || true
cd -

tar -czf "$ASSETS_DIR/openclaw.tar.gz" -C "$WORK_DIR/openclaw_bundle" openclaw
echo "    OpenClaw bundle: $(ls -lh "$ASSETS_DIR/openclaw.tar.gz" | awk '{print $5}')"

# ── 4. Verify ────────────────────────────────────────────────────────────────
echo ""
echo "[4/4] Verifying assets..."
echo ""
echo "  jniLibs/arm64-v8a/ contents:"
ls -lh "$JNILIBS_DIR/" | tail -n +2 | awk '{printf "    %-30s %s\n", $NF, $5}'
echo ""
echo "  assets/ contents:"
ls -lh "$ASSETS_DIR/" | tail -n +2 | awk '{printf "    %-30s %s\n", $NF, $5}'

TOTAL_JNILIBS=$(du -sh "$JNILIBS_DIR" | cut -f1)
TOTAL_ASSETS=$(du -sh "$ASSETS_DIR" | cut -f1)
echo ""
echo "═══════════════════════════════════════════════"
echo "  Assets ready."
echo "    Native libs (jniLibs): $TOTAL_JNILIBS"
echo "    Assets (OpenClaw):     $TOTAL_ASSETS"
echo ""
echo "  Now build the APK:"
echo "    ./gradlew :app:assembleRelease"
echo "═══════════════════════════════════════════════"
