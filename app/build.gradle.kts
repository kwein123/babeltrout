import java.io.File
import java.io.FileInputStream
import java.util.Properties
import org.gradle.api.GradleException
plugins {
    id("com.android.application")
}

val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties: Properties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

fun requiredKeystoreProperty(name: String): String {
    return keystoreProperties.getProperty(name)
        ?: throw GradleException("Missing '$name' in keystore.properties")
}

android {
    namespace = "com.kevin.babeltrout"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kevin.babeltrout"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(requiredKeystoreProperty("storeFile"))
                storePassword = requiredKeystoreProperty("storePassword")
                keyAlias = requiredKeystoreProperty("keyAlias")
                keyPassword = requiredKeystoreProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinks the ~16 MB of dex to a fraction; ML Kit ships its own keep rules.
            isMinifyEnabled = true
            isShrinkResources = true
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Release APKs are split per CPU type: each carries only its own copy of the native ML Kit and
    // onnxruntime libraries (~47 MB for arm64 vs ~78 MB combined). Emulator (x86) ABIs are dropped.
    // Debug builds stay universal so they install on anything, including emulators.
    // Only when assembling APKs: AGP refuses ABI splits in the same run as an App Bundle (bundleRelease).
    val buildingReleaseApks = gradle.startParameter.taskNames.any {
        it.contains("assemble", ignoreCase = true) && it.contains("Release", ignoreCase = true)
    }
    splits {
        abi {
            isEnable = buildingReleaseApks
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // sherpa-onnx's JNI library links only libonnxruntime; its C and C++ API libraries are unused.
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")

    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")

    // ML Kit translate 17.0.3 (the latest release) still pulls in okhttp 3.0.0 and okio 1.6.0. Force
    // patched versions. OkHttp 4.12 keeps the okhttp3 package and the 3.x Java API; every OkHttp method
    // ML Kit calls was checked present with the same signature in 4.12.0 on 2026-09-29.
    constraints {
        implementation("com.squareup.okhttp3:okhttp:4.12.0") {
            because("CVE-2021-0341 (fixed in 4.9.2) and CVE-2016-2402 (fixed in 3.1.2) in okhttp 3.0.0 from ML Kit translate")
        }
        implementation("com.squareup.okio:okio:3.18.2") {
            because("CVE-2023-3635 in okio 1.6.0 from ML Kit translate (fixed in 1.17.6 / 3.4.0); 3.18.2 matches okhttp 4.12.0")
        }
    }

    // Built-in Piper voices: sherpa-onnx runs the models; commons-compress unpacks the .tar.bz2 downloads.
    // Android-only module. The JitPack AAR was checked byte-identical to k2-fsa's GitHub release
    // asset sherpa-onnx-1.13.8.aar (sha256 633c2432...bd96) on 2026-09-28; re-check when upgrading.
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")
    implementation("org.apache.commons:commons-compress:1.28.0")

    testImplementation("junit:junit:4.13.2")

    // Android Lint's own runtime (build tool only, not in the APK). The root build.gradle.kts pins don't
    // reach it, and AGP 9.4.1's lint still pulls in versions with known CVEs.
    constraints {
        "androidLintTool"("org.bouncycastle:bcprov-jdk18on:1.86") {
            because("CVE fixes in 1.84 and 1.85 (Dependabot #21, #53, #54); lint has 1.80.2")
        }
        "androidLintTool"("org.bouncycastle:bcpkix-jdk18on:1.86") {
            because("CVE fixed in 1.84 (Dependabot #20); kept in step with bcprov")
        }
        "androidLintTool"("org.bouncycastle:bcutil-jdk18on:1.86") {
            because("kept in step with bcprov and bcpkix")
        }
        "androidLintTool"("org.apache.commons:commons-lang3:3.21.0") {
            because("CVE fixed in 3.18.0 (Dependabot #10); lint has 3.16.0")
        }
        "androidLintTool"("org.apache.httpcomponents:httpclient:4.5.14") {
            because("CVE fixed in 4.5.13 (Dependabot #1); lint asks for 4.5.6")
        }
    }
}
