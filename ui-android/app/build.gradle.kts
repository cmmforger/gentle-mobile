import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}


// Release signing reads a properties file kept OUTSIDE the repository
// (~/.config/gentle-companion/signing.properties, mode 600). When the file is
// absent the release build is unsigned, so the project still builds anywhere.
val signingPropsFile = file("${System.getProperty("user.home")}/.config/gentle-companion/signing.properties")
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
    namespace = "com.gentlemanprogramming.gentlecompanion"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gentlemanprogramming.gentlecompanion"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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
    // Ed25519 keypair generation for the device identity key (the key must be
    // exportable in OpenSSH format, so AndroidKeyStore is not usable here).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // QR pairing: CameraX preview + ML Kit barcode scanning behind the
    // design's Pair screen. Only used while the scanner is open.
    val camerax = "1.3.4"
    testImplementation("junit:junit:4.13.2")
}
