plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.secrets)
}

android {
    namespace = "com.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ollamadevhive.server"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing — only used when building a signed release APK.
    // Set KEYSTORE_PATH, STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD env vars.
    signingConfigs {
        create("release") {
            val kPath = System.getenv("KEYSTORE_PATH")
            if (kPath != null) {
                storeFile    = file(kPath)
                storePassword = System.getenv("STORE_PASSWORD") ?: ""
                keyAlias     = System.getenv("KEY_ALIAS") ?: "upload"
                keyPassword  = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isCrunchPngs   = false
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // Uses Android's built-in debug keystore automatically — no config needed.
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose     = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    testOptions {
        unitTests { isIncludeAndroidResources = true }
    }
}

// Secrets plugin: reads .env for local dev, .env.example as fallback.
secrets {
    propertiesFileName        = ".env"
    defaultPropertiesFileName = ".env.example"
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(platform(libs.firebase.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logging.interceptor)
    implementation(libs.okhttp)
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")

    // ── Tests ──────────────────────────────────────────
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// ── Download Ollama ARM64 binary at build time ─────────────────────────────
// The binary is bundled as libollama.so in jniLibs so the installer puts it
// in nativeLibraryDir — the only directory Android always allows execution in
// (not subject to SELinux noexec / Samsung Knox restrictions).
val downloadOllamaBinary by tasks.registering {
    val jniDir  = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")
    val outFile = jniDir.file("libollama.so").asFile
    outputs.file(outFile)
    doFirst {
        jniDir.asFile.mkdirs()
        if (!outFile.exists()) {
            println("[Devhive] Downloading Ollama ARM64 binary (~48 MB)...")
            val src = java.net.URL(
                "https://github.com/sunshine0523/OllamaServer/raw/master/android/app/src/main/assets/arm64-v8a/ollama"
            )
            val conn = src.openConnection().also { it.connectTimeout = 30_000; it.readTimeout = 120_000 }
            conn.getInputStream().use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            outFile.setExecutable(true, false)
            println("[Devhive] Downloaded: ${outFile.length() / 1024 / 1024} MB → ${outFile.absolutePath}")
        } else {
            println("[Devhive] libollama.so already present (${outFile.length() / 1024 / 1024} MB), skipping download.")
        }
    }
}

afterEvaluate {
    tasks.named("preBuild") { dependsOn(downloadOllamaBinary) }
}
