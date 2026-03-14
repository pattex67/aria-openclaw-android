import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}

android {
    namespace = "io.openclaw.aria"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.openclaw.aria"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../aria-release.keystore")
            storePassword = localProps.getProperty("ARIA_KEYSTORE_PASSWORD", "")
            keyAlias = "aria"
            keyPassword = localProps.getProperty("ARIA_KEY_PASSWORD", "")
        }
    }

    flavorDimensions += "version"
    productFlavors {
        create("personal") {
            dimension = "version"
            applicationId = "io.openclaw.aria.personal"
        }
        create("github") {
            dimension = "version"
            applicationId = "io.openclaw.aria"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    // HTTP client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("org.json:json:20240303")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Media player for audio
    implementation("androidx.media:media:1.7.0")

    // Image loading
    implementation("io.coil-kt:coil:2.6.0")
    implementation("io.coil-kt:coil-video:2.6.0")

    // Activity Result API
    implementation("androidx.activity:activity-ktx:1.9.3")

    // Room database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Markdown rendering
    val markwonVersion = "4.6.2"
    implementation("io.noties.markwon:core:$markwonVersion")
    implementation("io.noties.markwon:linkify:$markwonVersion")
    implementation("io.noties.markwon:ext-strikethrough:$markwonVersion")

    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Lifecycle (background/foreground detection)
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

// Biometric authentication
    implementation("androidx.biometric:biometric:1.1.0")

    // Encrypted storage for API keys
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Ed25519 for OpenClaw device identity
    implementation("net.i2p.crypto:eddsa:0.3.0")
}
