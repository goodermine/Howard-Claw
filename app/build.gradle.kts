plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "au.howardagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "au.howardagent"
        minSdk = 24
        // targetSdk 28 required to allow executing Termux Node.js binaries
        // from app data directories. Android 10+ (targetSdk 29+) enforces W^X
        // via SELinux, blocking execution from app-private storage.
        // This is the same approach used by codexUI/AnyClaw and Termux (F-Droid).
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1+"
        }
    }

    ndkVersion = "26.1.10909125"

    // Don't compress bootstrap zip or tar.gz assets
    androidResources {
        noCompress += listOf("zip", "tar.gz")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Asset bundling: OpenClaw gateway needs Termux bootstrap + Node.js + openclaw
// packaged into app/src/main/assets/ before the APK is built. We wire the
// bundle_assets.sh script into the build graph so `./gradlew assembleDebug`
// produces a fully self-contained APK when the assets are missing.
//
// Skips automatically if the assets are already present or the script is
// unavailable (e.g. Windows host) — the app gracefully handles missing
// assets at runtime in GatewayService.
// ─────────────────────────────────────────────────────────────────────────────
val bundleAssetsTask = tasks.register("bundleOpenClawAssets") {
    group = "howard"
    description = "Runs scripts/bundle_assets.sh if gateway assets are missing."

    val assetsDir = file("src/main/assets")
    val bootstrap = file("src/main/assets/bootstrap-aarch64.zip")
    val nodeBundle = file("src/main/assets/node-supplement.tar.gz")
    val openclaw = file("src/main/assets/openclaw.tar.gz")
    val script = rootProject.file("scripts/bundle_assets.sh")

    outputs.files(bootstrap, nodeBundle, openclaw)

    doLast {
        assetsDir.mkdirs()
        val allPresent = bootstrap.exists() && nodeBundle.exists() && openclaw.exists()
        if (allPresent) {
            logger.lifecycle("Howard assets already bundled (${assetsDir}); skipping.")
            return@doLast
        }
        if (!script.exists()) {
            logger.warn(
                "Howard: scripts/bundle_assets.sh not found — building without OpenClaw " +
                    "runtime. GatewayService will degrade gracefully."
            )
            return@doLast
        }
        logger.lifecycle("Howard: running scripts/bundle_assets.sh ...")
        val proc = ProcessBuilder("bash", script.absolutePath)
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        proc.inputStream.bufferedReader().forEachLine { logger.lifecycle("[bundle] $it") }
        val code = proc.waitFor()
        if (code != 0) {
            logger.warn(
                "Howard: bundle_assets.sh failed (exit=$code). Building without the " +
                    "gateway runtime; the app will still run but OpenClaw features will " +
                    "be unavailable until assets are provided manually."
            )
        }
    }
}

// Make the asset-packaging steps depend on our bundler so the script runs
// before AGP copies assets into the APK.
afterEvaluate {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
        .configureEach { dependsOn(bundleAssetsTask) }
}

dependencies {
    // Compose
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Security
    implementation(libs.androidx.security.crypto)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
