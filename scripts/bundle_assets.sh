#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Howard — Asset Bundling Script
# Run on a Linux machine before building the APK with OpenClaw support.
#
# Based on the codexUI/AnyClaw architecture:
#   1. Downloads the Termux bootstrap (provides bin/sh, libtermux-exec.so, etc.)
#   2. Downloads Node.js LTS from Termux packages
#   3. Packs OpenClaw npm package
#
# Output layout:
#   assets/bootstrap-aarch64.zip   — Termux Linux environment (~30MB)
#   assets/openclaw.tar.gz         — OpenClaw npm package
#
# At runtime, GatewayService extracts the bootstrap into filesDir/usr/,
# then runs Node.js via ProcessBuilder with Termux-style environment variables.
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

# ── Termux bootstrap release ────────────────────────────────────────────────
# Get the latest bootstrap from: https://github.com/termux/termux-packages/releases
# Look for bootstrap-aarch64.zip
BOOTSTRAP_URL="https://github.com/termux/termux-packages/releases/download/bootstrap-2025.01.19-r1%2Bapt-android-7/bootstrap-aarch64.zip"

echo "═══════════════════════════════════════════════"
echo "  HOWARD — Bundling Assets (Termux Bootstrap)"
echo "═══════════════════════════════════════════════"

mkdir -p "$ASSETS_DIR" "$WORK_DIR"
trap 'rm -rf "$WORK_DIR"' EXIT

# ── 1. Download Termux bootstrap ────────────────────────────────────────────
echo ""
echo "[1/3] Downloading Termux bootstrap (aarch64)..."

if [ -f "$ASSETS_DIR/bootstrap-aarch64.zip" ]; then
    echo "    Already exists, skipping download."
else
    curl -fL -o "$ASSETS_DIR/bootstrap-aarch64.zip" "$BOOTSTRAP_URL"
fi
echo "    Bootstrap: $(ls -lh "$ASSETS_DIR/bootstrap-aarch64.zip" | awk '{print $5}')"

# ── 2. Download Node.js from Termux and repack into bootstrap ───────────────
echo ""
echo "[2/3] Downloading Node.js LTS (Termux aarch64)..."

# We need to add Node.js to the bootstrap since it's not included by default.
# Download the .deb and extract the node binary + npm into a supplementary zip.
TERMUX_REPO="https://packages.termux.dev/apt/termux-main/pool/main"
TERMUX_INDEX="https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages"

mkdir -p "$WORK_DIR/node-extract"

# Discover the current filename for nodejs-lts (preferred) or nodejs by parsing
# the Packages index. Termux bumps version numbers constantly, so hard-coding
# the .deb name is fragile.
resolve_latest_deb() {
    local pkg="$1"
    curl -fsSL "$TERMUX_INDEX" 2>/dev/null \
        | awk -v p="$pkg" '
            /^Package: / { current = $2 }
            /^Filename: / && current == p { print $2; exit }
        '
}

NODE_DEB_PATH=""
for candidate in nodejs-lts nodejs; do
    NODE_DEB_PATH="$(resolve_latest_deb "$candidate" || true)"
    if [ -n "$NODE_DEB_PATH" ]; then
        echo "    Resolved $candidate → $NODE_DEB_PATH"
        break
    fi
done

if [ -z "$NODE_DEB_PATH" ]; then
    echo "    WARNING: could not resolve Node.js package from Termux index."
    echo "    Falling back to a known filename (may 404)."
    NODE_DEB_PATH="pool/main/n/nodejs/nodejs_22.12.0_aarch64.deb"
fi

NODE_URL="https://packages.termux.dev/apt/termux-main/$NODE_DEB_PATH"
curl -fL -o "$WORK_DIR/node.deb" "$NODE_URL" || {
    echo "    ERROR: failed to download Node.js from $NODE_URL"
    echo "    Skipping node bundle — GatewayService will attempt runtime install."
}

if [ -f "$WORK_DIR/node.deb" ]; then
    (cd "$WORK_DIR/node-extract" && ar x ../node.deb && tar xf data.tar.* 2>/dev/null) || true
fi

# Create a node supplement zip that will be extracted alongside the bootstrap
# The deb extracts to data/data/com.termux/files/usr/ — we need just the usr/ contents
NODE_USR=$(find "$WORK_DIR/node-extract" -type d -name "usr" -path "*/com.termux/*" | head -1)
if [ -z "$NODE_USR" ]; then
    # Some debs have different path structure
    NODE_USR=$(find "$WORK_DIR/node-extract" -type d -name "usr" | head -1)
fi

if [ -n "$NODE_USR" ] && [ -f "$NODE_USR/bin/node" ]; then
    # Also pull in the runtime libraries nodejs depends on so a cold install
    # doesn't need network access. Termux's nodejs depends on c-ares, libicu
    # and libsqlite — we fetch and merge them into the same staging tree.
    for dep in c-ares libicu libsqlite; do
        DEP_PATH="$(resolve_latest_deb "$dep" || true)"
        if [ -n "$DEP_PATH" ]; then
            echo "    + bundling dep: $DEP_PATH"
            curl -fsSL -o "$WORK_DIR/$dep.deb" \
                "https://packages.termux.dev/apt/termux-main/$DEP_PATH" || continue
            mkdir -p "$WORK_DIR/$dep-extract"
            (cd "$WORK_DIR/$dep-extract" && ar x "../$dep.deb" && tar xf data.tar.* 2>/dev/null) || true
            DEP_USR=$(find "$WORK_DIR/$dep-extract" -type d -name "usr" -path "*/com.termux/*" | head -1)
            [ -z "$DEP_USR" ] && DEP_USR=$(find "$WORK_DIR/$dep-extract" -type d -name "usr" | head -1)
            if [ -n "$DEP_USR" ]; then
                cp -a "$DEP_USR"/* "$NODE_USR/" 2>/dev/null || true
            fi
        fi
    done

    # Create a tar.gz of the node install that GatewayService can extract over the bootstrap
    tar -czf "$ASSETS_DIR/node-supplement.tar.gz" -C "$NODE_USR" .
    echo "    Node supplement: $(ls -lh "$ASSETS_DIR/node-supplement.tar.gz" | awk '{print $5}')"
    echo "    Node binary: $(file "$NODE_USR/bin/node" 2>/dev/null | grep -o 'ELF.*' | head -c 80 || echo '(file tool unavailable)')"
else
    echo "    WARNING: Could not find node binary in Termux .deb"
    echo "    Node.js will need to be installed manually via Termux apt"
fi

# ── 3. Pack OpenClaw as tar.gz ───────────────────────────────────────────────
echo ""
echo "[3/3] Packing OpenClaw npm package..."

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
echo "    node-supplement.tar.gz — Node.js binary + npm (from Termux)"
echo "    openclaw.tar.gz        — OpenClaw npm package"
echo ""
echo "  Now build the APK:"
echo "    ./gradlew :app:assembleDebug"
echo "═══════════════════════════════════════════════"
