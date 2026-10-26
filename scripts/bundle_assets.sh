#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Howard — Asset Bundling Script
# Run on a Linux machine before building the APK with OpenClaw support.
#
# Based on the openclaw-android glibc dynamic linker architecture:
#   1. Downloads the Termux bootstrap (provides bin/sh, libtermux-exec.so, etc.)
#   2. Downloads glibc libraries (provides ld-linux-aarch64.so.1)
#   3. Downloads standard linux-arm64 Node.js from nodejs.org
#   4. Packs OpenClaw npm package
#
# Output layout:
#   assets/bootstrap-aarch64.zip   — Termux Linux environment (~30MB)
#   assets/glibc-libs.tar.gz       — glibc dynamic linker + runtime libs (~10MB)
#   assets/node-supplement.tar.gz  — Node.js linux-arm64 (bin/node.real + npm)
#   assets/openclaw.tar.gz         — OpenClaw npm package
#   assets/glibc-compat.js         — Android kernel quirk fixes (copied separately)
#
# At runtime, GatewayService:
#   1. Extracts bootstrap into filesDir/usr/
#   2. Extracts glibc libs into filesDir/usr/glibc/lib/
#   3. Extracts Node.js into filesDir/home/.openclaw-android/node/
#   4. Executes node via: ld-linux-aarch64.so.1 --library-path $PREFIX/glibc/lib node.real
#
# This avoids running Termux-compiled (Bionic) Node.js directly, which fails
# due to SELinux and dynamic linker mismatches. Instead, standard linux-arm64
# binaries are loaded through the glibc dynamic linker — the proven approach
# from openclaw-android (1.8k+ stars).
#
# IMPORTANT: This approach requires targetSdk <= 28 in build.gradle.kts
# to allow executing binaries from app data directories.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# Run from the project root (allow being called from anywhere).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

ASSETS_DIR="app/src/main/assets"
WORK_DIR="/tmp/howard-bundle-$$"

# ── Pinned versions ────────────────────────────────────────────────────────
# These match openclaw-android's verified pair. Bump together, never one alone.
NODE_VERSION="24.21.0"
GLIBC_PKG_URL="https://sync.termux-pacman.dev/gpkg/aarch64/glibc-2.44-0-aarch64.pkg.tar.xz"
BOOTSTRAP_URL="https://github.com/termux/termux-packages/releases/download/bootstrap-2026.02.12-r1%2Bapt.android-7/bootstrap-aarch64.zip"

echo "═══════════════════════════════════════════════"
echo "  HOWARD — Bundling Assets (glibc approach)"
echo "═══════════════════════════════════════════════"

mkdir -p "$ASSETS_DIR" "$WORK_DIR"
trap 'rm -rf "$WORK_DIR"' EXIT

# ── 1. Download Termux bootstrap ────────────────────────────────────────────
echo ""
echo "[1/4] Downloading Termux bootstrap (aarch64)..."

if [ -f "$ASSETS_DIR/bootstrap-aarch64.zip" ]; then
    echo "    Already exists, skipping download."
else
    curl -fL -o "$ASSETS_DIR/bootstrap-aarch64.zip" "$BOOTSTRAP_URL"
fi
echo "    Bootstrap: $(ls -lh "$ASSETS_DIR/bootstrap-aarch64.zip" | awk '{print $5}')"

# ── 2. Download glibc runtime libraries ─────────────────────────────────────
echo ""
echo "[2/4] Downloading glibc runtime (aarch64)..."

if [ -f "$ASSETS_DIR/glibc-libs.tar.gz" ]; then
    echo "    Already exists, skipping download."
else
    echo "    Downloading glibc package from Termux pacman repo..."
    curl -fL -o "$WORK_DIR/glibc.pkg.tar.xz" "$GLIBC_PKG_URL"
    echo "    Package: $(ls -lh "$WORK_DIR/glibc.pkg.tar.xz" | awk '{print $5}')"

    echo "    Extracting glibc libraries..."
    mkdir -p "$WORK_DIR/glibc-extract"
    tar xJf "$WORK_DIR/glibc.pkg.tar.xz" -C "$WORK_DIR/glibc-extract"

    # The pacman package extracts to data/data/com.termux/files/usr/glibc/
    GLIBC_ROOT=$(find "$WORK_DIR/glibc-extract" -type d -name "glibc" -path "*/com.termux/*" | head -1)
    if [ -z "$GLIBC_ROOT" ]; then
        GLIBC_ROOT=$(find "$WORK_DIR/glibc-extract" -type d -name "glibc" | head -1)
    fi

    if [ -z "$GLIBC_ROOT" ] || [ ! -d "$GLIBC_ROOT/lib" ]; then
        echo "    ERROR: Could not find glibc/lib/ in the package."
        echo "    Contents of extract dir:"
        find "$WORK_DIR/glibc-extract" -maxdepth 5 -type d | head -20
        exit 1
    fi

    # Verify the dynamic linker exists
    if [ ! -f "$GLIBC_ROOT/lib/ld-linux-aarch64.so.1" ]; then
        echo "    ERROR: ld-linux-aarch64.so.1 not found in glibc package"
        exit 1
    fi

    # Bundle the glibc directory (preserving glibc/lib/ structure)
    tar -czf "$ASSETS_DIR/glibc-libs.tar.gz" -C "$(dirname "$GLIBC_ROOT")" glibc
    echo "    glibc libs: $(ls -lh "$ASSETS_DIR/glibc-libs.tar.gz" | awk '{print $5}')"
    echo "    ld.so: $(file "$GLIBC_ROOT/lib/ld-linux-aarch64.so.1" 2>/dev/null | grep -o 'ELF.*' | head -c 80 || echo '(file tool unavailable)')"

    # List key libraries bundled
    echo "    Libraries:"
    ls "$GLIBC_ROOT/lib/"*.so* 2>/dev/null | while read -r f; do
        echo "      $(basename "$f")"
    done | sort -u | head -20
fi

# ── 3. Download Node.js (standard linux-arm64 from nodejs.org) ──────────────
echo ""
echo "[3/4] Downloading Node.js v${NODE_VERSION} (linux-arm64)..."

NODE_DIST_BASE="https://nodejs.org/dist/v${NODE_VERSION}"
NODE_TARBALL="node-v${NODE_VERSION}-linux-arm64.tar.xz"

if [ -f "$ASSETS_DIR/node-supplement.tar.gz" ]; then
    echo "    Already exists, skipping download."
else
    echo "    Downloading from nodejs.org..."
    curl -fL --max-time 300 -o "$WORK_DIR/$NODE_TARBALL" "$NODE_DIST_BASE/$NODE_TARBALL"

    # Verify SHA-256 checksum
    echo "    Verifying checksum..."
    curl -fsSL --max-time 60 -o "$WORK_DIR/SHASUMS256.txt" "$NODE_DIST_BASE/SHASUMS256.txt"
    EXPECTED_SHA=$(awk -v f="$NODE_TARBALL" '$2 == f { print $1; exit }' "$WORK_DIR/SHASUMS256.txt")
    ACTUAL_SHA=$(sha256sum "$WORK_DIR/$NODE_TARBALL" | awk '{ print $1 }')

    if [ -z "$EXPECTED_SHA" ] || [ "$EXPECTED_SHA" != "$ACTUAL_SHA" ]; then
        echo "    ERROR: Checksum mismatch for $NODE_TARBALL"
        echo "      expected: ${EXPECTED_SHA:-<not listed>}"
        echo "      actual:   $ACTUAL_SHA"
        exit 1
    fi
    echo "    SHA-256 verified"

    # Extract Node.js
    echo "    Extracting..."
    mkdir -p "$WORK_DIR/node-extract"
    tar xJf "$WORK_DIR/$NODE_TARBALL" -C "$WORK_DIR/node-extract" --strip-components=1

    # Rename node binary to node.real (the glibc wrapper will exec it via ld.so)
    mv "$WORK_DIR/node-extract/bin/node" "$WORK_DIR/node-extract/bin/node.real"

    # Create the supplement archive
    tar -czf "$ASSETS_DIR/node-supplement.tar.gz" -C "$WORK_DIR/node-extract" .
    echo "    Node supplement: $(ls -lh "$ASSETS_DIR/node-supplement.tar.gz" | awk '{print $5}')"
    echo "    Node binary: $(file "$WORK_DIR/node-extract/bin/node.real" 2>/dev/null | grep -o 'ELF.*' | head -c 80 || echo '(file tool unavailable)')"
fi

# ── 4. Pack OpenClaw as tar.gz ───────────────────────────────────────────────
echo ""
echo "[4/4] Packing OpenClaw npm package..."

if [ -f "$ASSETS_DIR/openclaw.tar.gz" ]; then
    echo "    Already exists, skipping."
else
    npm install -g openclaw@latest --prefix "$WORK_DIR/openclaw_install" 2>/dev/null || {
        echo "    WARNING: openclaw npm package not found or install failed."
        echo "    Creating empty placeholder."
        mkdir -p "$WORK_DIR/openclaw_install/lib/node_modules/openclaw"
        echo '{"name":"openclaw","version":"0.0.0","main":"index.js"}' > \
            "$WORK_DIR/openclaw_install/lib/node_modules/openclaw/package.json"
        echo 'console.log("OpenClaw placeholder");' > \
            "$WORK_DIR/openclaw_install/lib/node_modules/openclaw/index.js"
    }

    mkdir -p "$WORK_DIR/openclaw_bundle"
    cp -r "$WORK_DIR/openclaw_install/lib/node_modules/openclaw" "$WORK_DIR/openclaw_bundle/openclaw"

    cd "$WORK_DIR/openclaw_bundle/openclaw"
    npm prune --omit=dev 2>/dev/null || true
    cd - > /dev/null

    tar -czf "$ASSETS_DIR/openclaw.tar.gz" -C "$WORK_DIR/openclaw_bundle" openclaw
    echo "    OpenClaw bundle: $(ls -lh "$ASSETS_DIR/openclaw.tar.gz" | awk '{print $5}')"
fi

# ── Verify ───────────────────────────────────────────────────────────────────
echo ""
echo "  assets/ contents:"
ls -lh "$ASSETS_DIR/" | tail -n +2 | awk '{printf "    %-35s %s\n", $NF, $5}'

TOTAL=$(du -sh "$ASSETS_DIR" | cut -f1)
echo ""
echo "═══════════════════════════════════════════════"
echo "  Assets ready. Total size: $TOTAL"
echo ""
echo "  Layout:"
echo "    bootstrap-aarch64.zip  — Termux Linux env (bin/sh, libtermux-exec.so)"
echo "    glibc-libs.tar.gz     — glibc dynamic linker + runtime libs"
echo "    node-supplement.tar.gz — Node.js v${NODE_VERSION} linux-arm64 (node.real + npm)"
echo "    openclaw.tar.gz        — OpenClaw npm package"
echo "    glibc-compat.js        — Android kernel quirk fixes (copied separately)"
echo ""
echo "  Execution chain:"
echo "    ld-linux-aarch64.so.1 --library-path \$PREFIX/glibc/lib node.real <script>"
echo ""
echo "  Now build the APK:"
echo "    ./gradlew :app:assembleDebug"
echo "═══════════════════════════════════════════════"
