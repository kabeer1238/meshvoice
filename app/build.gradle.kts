import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.meshvoice.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.meshvoice.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.2.0-beta1.2"
    }

    // Every CI build must be signed with the SAME key, or Android refuses to
    // install a new APK over an existing one with the same applicationId --
    // it just fails silently from the user's point of view, and the person
    // ends up still running whatever old build happened to install first.
    // Gradle's default debug signing auto-generates a NEW random keystore on
    // any machine where ~/.android/debug.keystore doesn't already exist,
    // which is every fresh GitHub Actions runner. Pinning an explicit,
    // checked-in keystore here is what makes "download the new APK and
    // install over the old one" actually work build after build.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("17")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-nearby:19.4.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.zxing:core:3.5.4")
    // Pure-Java Opus codec (no NDK/native build needed), published on Maven
    // Central. Used to compress voice audio before it goes over either
    // transport -- see AudioEngine's encode/decode wrapper.
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    testImplementation("junit:junit:4.13.2")
}
