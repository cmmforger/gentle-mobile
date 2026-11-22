import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}


// Release signing reads a properties file kept OUTSIDE the repository
// (~/.config/gentle-mobile/signing.properties, mode 600). When the file is
// absent the release build is unsigned, so the project still builds anywhere.
val signingPropsFile = file("${System.getProperty("user.home")}/.config/gentle-mobile/signing.properties")
val signingProps = Properties().apply {
    if (signingPropsFile.exists()) signingPropsFile.inputStream().use { load(it) }
}

android {
    signingConfigs {
        if (signingPropsFile.exists()) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }
}

android {
    namespace = "com.gentlemanprogramming.gentlemobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gentlemanprogramming.gentlemobile"
        minSdk = 29
        targetSdk = 34
        versionCode = 11
        versionName = "0.9.2"

        // Ship only the ABIs the core is built for: a real phone is arm64-v8a,
        // the emulator is x86_64. Everything else only adds bytes.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingPropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Required by the generated UniFFI Kotlin bindings (com.sun.jna.*).
    // The AAR carries the Android libjnidispatch.so natives; the plain jar
    // does not, and JNA fails with UnsatisfiedLinkError on device.
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    // Biometric gate (T18): the prompt that guards opening a session and
    // using the device key; BIOMETRIC_WEAK with device credential fallback.
    implementation("androidx.biometric:biometric:1.1.0")
    // Ed25519 keypair generation for the device identity key (the key must be
    // exportable in OpenSSH format, so AndroidKeyStore is not usable here).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // QR pairing: CameraX preview + the bundled ML Kit barcode model behind
    // the design's Pair screen. Only used while the scanner is open; the
    // paste field stays the fallback when the camera is missing or denied.
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    testImplementation("junit:junit:4.13.2")
}
