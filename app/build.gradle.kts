plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.xomel45.openschool"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.xomel45.openschool"
        minSdk = 26 // the same API level the native library is built for (scripts/build-bridge.sh)
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // A real release key, when given (CI secrets or a local keystore): every build then updates the same installed app.
    // Without it the release build is signed with the debug key of the machine that built it.
    val keystore = System.getenv("OPENSCHOOL_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = keystore
            storePassword = System.getenv("OPENSCHOOL_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("OPENSCHOOL_KEY_ALIAS")
            keyPassword = System.getenv("OPENSCHOOL_KEY_PASSWORD") ?: System.getenv("OPENSCHOOL_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            // R8 + resource shrinking: a smaller, much faster app (Compose in a debug build is slow on cheap phones like a Redmi 9A).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    sourceSets["main"].kotlin.srcDir("src/main/kotlin-gen")
    packaging { jniLibs.useLegacyPackaging = false }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    val compose = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(compose)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.webkit:webkit:1.14.0") // proxy override for the login window
    implementation("androidx.glance:glance-appwidget:1.1.1") // the home-screen widgets (schedule, homework)
    implementation("androidx.work:work-runtime-ktx:2.10.5") // the periodic check for new marks and homework
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517") // the real org.json: the Android one is a stub in unit tests
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.android.gms:play-services-auth-api-phone:18.3.1") // SMS User Consent: the code from the SMS without the READ_SMS permission
    implementation("net.java.dev.jna:jna:5.17.0@aar") // UniFFI's bindings call the Rust library through JNA
    // Installs the baseline profiles (Compose ships them) also when the APK is installed by hand, not from a store: the hot code is
    // compiled ahead, so start and scrolling are smoother on slow phones.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
