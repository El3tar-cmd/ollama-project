import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

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
        // PRoot is a standalone executable stored under app filesDir.
        // Android 10+ blocks executing app-data binaries for apps targeting 29+.
        targetSdk = 28
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
            // Uses Android's built-in debug keystore automatically — config needed.
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

    // externalNativeBuild disabled — we bundle prebuilt native binaries
    // ndkVersion not required since we don't compile native code locally

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
    implementation(libs.androidx.compose.material.icons.extended)
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

    // ── Embedded Linux (TAR extraction) ────────────────
    implementation("org.apache.commons:commons-compress:1.26.1")
    implementation("org.tukaani:xz:1.9")

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

// ── Download Ollama ARM64 binary + libc++_shared.so at build time ───────────
// Both are bundled as .so files in jniLibs so the installer extracts them
// into nativeLibraryDir — the only dir Android always allows execution from
// (not subject to SELinux noexec / Samsung Knox restrictions).
// libc++_shared.so is required by the Ollama binary at link time; we try to
// copy it from the NDK in the build environment first, then fall back to
// downloading it from the OllamaServer repository.
val downloadOllamaBinary by tasks.registering {
    val jniDir     = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")
    val ollamaFile = jniDir.file("libollama.so").asFile
    val libCppFile = jniDir.file("libc++_shared.so").asFile
    outputs.files(ollamaFile, libCppFile)

    doFirst {
        jniDir.asFile.mkdirs()

        // ── 1. libollama.so ─────────────────────────────────────────────────
        if (!ollamaFile.exists()) {
            println("[Devhive] Downloading Ollama ARM64 binary (~48 MB)...")
            val conn = URL(
                "https://github.com/sunshine0523/OllamaServer/raw/master/android/app/src/main/assets/arm64-v8a/ollama"
            ).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout    = 180_000
            conn.connect()
            conn.inputStream.use { input ->
                ollamaFile.outputStream().use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            ollamaFile.setExecutable(true, false)
            println("[Devhive] Downloaded libollama.so: ${ollamaFile.length() / 1024 / 1024} MB")
        } else {
            println("[Devhive] libollama.so already present (${ollamaFile.length() / 1024 / 1024} MB), skipping.")
        }

        // ── 2. libc++_shared.so ─────────────────────────────────────────────
        // The Ollama binary dynamically links libc++_shared.so. Bundle it so
        // the app's nativeLibraryDir satisfies the linker (via LD_LIBRARY_PATH).
        if (!libCppFile.exists()) {
            var done = false

            // 2a. Try to copy from local NDK (fastest in CI — NDK is pre-installed)
            val ndkSearchDirs = listOfNotNull(
                System.getenv("ANDROID_NDK_HOME"),
                System.getenv("ANDROID_NDK_ROOT"),
                System.getenv("NDK_HOME"),
                System.getenv("ANDROID_SDK_ROOT")?.let { "$it/ndk" }
                    ?.let { File(it).listFiles()?.maxByOrNull { f: File -> f.name }?.absolutePath }
            )
            val ndkSubPaths = listOf(
                "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so",
                "toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so",
                "toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
            )
            outer@ for (ndkHome in ndkSearchDirs.filterNotNull()) {
                for (sub in ndkSubPaths) {
                    val src = File("$ndkHome/$sub")
                    if (src.exists()) {
                        src.copyTo(libCppFile, overwrite = true)
                        println("[Devhive] Copied libc++_shared.so from NDK: ${src.absolutePath}")
                        done = true
                        break@outer
                    }
                }
            }

            // 2b. Fall back to downloading from OllamaServer repo
            if (!done) {
                println("[Devhive] NDK not found locally — downloading libc++_shared.so...")
                try {
                    val conn2 = URL(
                        "https://github.com/sunshine0523/OllamaServer/raw/master/android/app/src/main/jniLibs/arm64-v8a/libc++_shared.so"
                    ).openConnection() as HttpURLConnection
                    conn2.connectTimeout = 30_000
                    conn2.readTimeout    = 60_000
                    conn2.instanceFollowRedirects = true
                    conn2.connect()
                    if (conn2.responseCode == 200) {
                        conn2.inputStream.use { input ->
                            libCppFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        println("[Devhive] Downloaded libc++_shared.so: ${libCppFile.length()} bytes")
                        done = true
                    } else {
                        println("[Devhive] Warning: libc++_shared.so download returned HTTP ${conn2.responseCode}")
                    }
                    conn2.disconnect()
                } catch (e: Exception) {
                    println("[Devhive] Warning: could not download libc++_shared.so: ${e.message}")
                }
            }

            if (!done) {
                println("[Devhive] WARNING: libc++_shared.so not found. Daemon may fail to link on devices that lack it in /system/lib64.")
            }
        } else {
            println("[Devhive] libc++_shared.so already present (${libCppFile.length()} bytes), skipping.")
        }
    }
}

val downloadLlamaServer by tasks.registering {
    val jniDir     = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")
    val serverFile = jniDir.file("libllama_server.so").asFile
    outputs.file(serverFile)

    doFirst {
        jniDir.asFile.mkdirs()

        if (!serverFile.exists()) {
            println("[Devhive] Downloading llama-server ARM64 binary (~73 MB)...")
            val tarGz = File(temporaryDir, "llama-android.tar.gz")
            val conn = URL(
                "https://github.com/ggml-org/llama.cpp/releases/download/b9754/llama-b9754-bin-android-arm64.tar.gz"
            ).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 30_000
            conn.readTimeout    = 300_000
            conn.connect()
            conn.inputStream.use { input ->
                tarGz.outputStream().use { output -> input.copyTo(output) }
            }
            conn.disconnect()

            // Extract tar.gz manually
            println("[Devhive] Extracting llama-server and shared libraries...")
            val gzip = GZIPInputStream(tarGz.inputStream().buffered(65536))
            val headerBuf = ByteArray(512)
            while (true) {
                var read = 0
                while (read < 512) {
                    val n = gzip.read(headerBuf, read, 512 - read)
                    if (n == -1) break
                    read += n
                }
                if (read < 512) break
                if (headerBuf.all { it == 0.toByte() }) break

                val name     = String(headerBuf, 0, 100).trimEnd('\u0000').trim()
                val sizeStr  = String(headerBuf, 124, 12).trimEnd('\u0000').trim()
                val fileSize = if (sizeStr.isEmpty()) 0L else sizeStr.toLong(8)
                val typeflag = headerBuf[156].toInt().toChar()
                val baseName = name.substringAfterLast('/')

                val isServer    = baseName == "llama-server" && typeflag != '5'
                val isSharedLib = baseName.endsWith(".so") && typeflag != '5' && baseName.startsWith("lib")

                if (isServer || isSharedLib) {
                    val dest = if (isServer) serverFile else File(jniDir.asFile, baseName)
                    println("[Devhive] Extracting: $baseName -> ${dest.name}")
                    var written = 0L
                    val buf = ByteArray(65536)
                    dest.outputStream().use { out ->
                        while (written < fileSize) {
                            val toRead = minOf(buf.size.toLong(), fileSize - written).toInt()
                            val n = gzip.read(buf, 0, toRead)
                            if (n == -1) break
                            out.write(buf, 0, n)
                            written += n
                        }
                    }
                    dest.setExecutable(true, false)
                    val pad = ((fileSize + 511) / 512 * 512 - fileSize).toInt()
                    if (pad > 0) gzip.skip(pad.toLong())
                } else {
                    val toSkip = (fileSize + 511) / 512 * 512
                    var skipped = 0L
                    while (skipped < toSkip) {
                        val s = gzip.skip(toSkip - skipped)
                        if (s <= 0) break
                        skipped += s
                    }
                }
            }
            gzip.close()
            tarGz.delete()

            println("[Devhive] llama-server installed: ${serverFile.length() / 1024 / 1024} MB")
        } else {
            println("[Devhive] libllama_server.so already present (${serverFile.length() / 1024 / 1024} MB), skipping.")
        }
    }
}

afterEvaluate {
    tasks.named("preBuild") { dependsOn(downloadOllamaBinary, downloadLlamaServer) }
}
