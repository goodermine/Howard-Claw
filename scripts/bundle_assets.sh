#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Howard — Asset Bundling Script
# Run on a Linux machine before building the APK.
# Creates the assets directory structure needed for the build.
#
# NOTE: Node.js and OpenClaw are no longer bundled — the gateway is now a
# Kotlin-native NanoHTTPD server running in-process.
# ─────────────────────────────────────────────────────────────────────────────
set -e

ASSETS_DIR="app/src/main/assets"

echo "═══════════════════════════════════════════════"
echo "  HOWARD — Bundling Assets"
echo "═══════════════════════════════════════════════"

mkdir -p "$ASSETS_DIR"

# Remove legacy Node.js / OpenClaw assets if present
rm -rf "$ASSETS_DIR/node"
rm -f  "$ASSETS_DIR/openclaw.tar.gz"

echo ""
echo "  Assets directory ready."
echo "  The OpenClaw gateway runs as a Kotlin-native"
echo "  NanoHTTPD server — no Node.js binary needed."
echo ""
echo "  Now build the APK:"
echo "  ./gradlew :app:assembleDebug"
echo "═══════════════════════════════════════════════"
