#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Howard — Project Initialisation
# Run once after cloning the repository.
# ─────────────────────────────────────────────────────────────────────────────
set -e

echo "═══════════════════════════════════════════════"
echo "  HOWARD — Project Init"
echo "═══════════════════════════════════════════════"

# ── 1. Add llama.cpp as git submodule ────────────────────────────────────────
echo ""
echo "[1/3] Adding llama.cpp submodule..."
if [ ! -d "app/src/main/cpp/llama.cpp/.git" ]; then
    git submodule add https://github.com/ggml-org/llama.cpp \
        app/src/main/cpp/llama.cpp
    git submodule update --init --recursive
    echo "    Done: llama.cpp submodule added"
else
    echo "    llama.cpp already present, updating..."
    git submodule update --remote app/src/main/cpp/llama.cpp
fi

# ── 2. Generate debug keystore ───────────────────────────────────────────────
echo ""
echo "[2/3] Generating debug keystore..."
KEYSTORE="app/howard-debug.jks"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair \
        -alias howard \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -keystore "$KEYSTORE" \
        -dname "CN=Howard Agent, O=Howard, C=AU" \
        -storepass howard123 \
        -keypass howard123
    echo "    Keystore generated: $KEYSTORE"
else
    echo "    Keystore already exists"
fi

# ── 3. Create placeholder drawables ──────────────────────────────────────────
echo ""
echo "[3/3] Creating placeholder resources..."
DRAWABLE_DIR="app/src/main/res/drawable"
mkdir -p "$DRAWABLE_DIR"

cat > "$DRAWABLE_DIR/ic_howard_notif.xml" << 'ICON_EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zm-2,14.5v-9l6,4.5-6,4.5z"/>
</vector>
ICON_EOF
echo "    Notification icon created"

echo ""
echo "═══════════════════════════════════════════════"
echo "  Init complete. Next steps:"
echo "  1. Open in Android Studio"
echo "  2. Sync Gradle"
echo "  3. Build"
echo "═══════════════════════════════════════════════"
