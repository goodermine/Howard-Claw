#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Howard — Asset Bundling Script
# Run on a Linux machine before building the APK with OpenClaw support.
# Places Node.js ARM64 binary + OpenClaw package into app/src/main/assets/
# ─────────────────────────────────────────────────────────────────────────────
set -e

ASSETS_DIR="app/src/main/assets"
NODE_VERSION="22.12.0"
NODE_ARCH="linux-arm64"

echo "═══════════════════════════════════════════════"
echo "  HOWARD — Bundling Assets"
echo "═══════════════════════════════════════════════"

mkdir -p "$ASSETS_DIR/node"

# ── 1. Download Node.js ARM64 binary ────────────────────────────────────────
echo ""
echo "[1/3] Downloading Node.js $NODE_VERSION ARM64..."
NODE_URL="https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-$NODE_ARCH.tar.xz"
NODE_TAR="node-$NODE_VERSION.tar.xz"

curl -L -o "/tmp/$NODE_TAR" "$NODE_URL"
tar -xf "/tmp/$NODE_TAR" -C /tmp/

NODE_BIN="/tmp/node-v$NODE_VERSION-$NODE_ARCH/bin/node"
cp "$NODE_BIN" "$ASSETS_DIR/node/node"
chmod +x "$ASSETS_DIR/node/node"
echo "    Node binary: $(ls -lh $ASSETS_DIR/node/node | awk '{print $5}')"

# ── 2. Pack OpenClaw as tar.gz ───────────────────────────────────────────────
echo ""
echo "[2/3] Packing OpenClaw npm package..."

npm install -g openclaw@latest --prefix /tmp/openclaw_install
mkdir -p /tmp/openclaw_bundle
cp -r /tmp/openclaw_install/lib/node_modules/openclaw /tmp/openclaw_bundle/openclaw

cd /tmp/openclaw_bundle/openclaw
npm prune --omit=dev 2>/dev/null || true
cd -

tar -czf "$ASSETS_DIR/openclaw.tar.gz" -C /tmp/openclaw_bundle openclaw
echo "    OpenClaw bundle: $(ls -lh $ASSETS_DIR/openclaw.tar.gz | awk '{print $5}')"

# ── 3. Verify ────────────────────────────────────────────────────────────────
echo ""
echo "[3/3] Verifying assets..."
echo "    node binary:     $(file $ASSETS_DIR/node/node)"
echo "    openclaw.tar.gz: $(ls -lh $ASSETS_DIR/openclaw.tar.gz)"

TOTAL=$(du -sh $ASSETS_DIR | cut -f1)
echo ""
echo "═══════════════════════════════════════════════"
echo "  Assets ready. Total size: $TOTAL"
echo "  Now build the APK:"
echo "  ./gradlew :app:assembleRelease"
echo "═══════════════════════════════════════════════"
