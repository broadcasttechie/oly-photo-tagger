plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Sourced from native/PINS so the on-device extractor (AssetExtractor) and the
// native build pipeline that produces assets/perl5.tar can never drift apart.
val nativePins: Map<String, String> = rootProject.file("native/PINS").readLines()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
    .associate { line -> line.substringBefore("=").trim() to line.substringAfter("=").trim() }

android {
    namespace = "com.olyphototagger.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.olyphototagger.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "int",
            "PERL5_ASSET_VERSION",
            nativePins.getValue("PERL5_ASSET_VERSION")
        )

        ndk {
            // Only arm64-v8a has been built via CI so far (native/build.sh all covers
            // armeabi-v7a/x86_64/x86 too; not yet run). Restricting here avoids shipping
            // an APK that silently lacks the exiftool binaries for other ABIs.
            abiFilters += "arm64-v8a"
        }
    }

    packaging {
        jniLibs {
            // libperl.so is exec'd via ProcessBuilder, not dlopen'd — it must land as a
            // real executable file under nativeLibraryDir, not stay page-aligned inside
            // the APK zip (AGP's default since native libs stopped needing extraction).
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        getByName("debug") {
            // A checked-in debug keystore (same well-known credentials AGP's
            // auto-generated one uses) so every machine — this laptop, CI, anyone
            // else's — signs debug builds identically. Without this, each machine
            // gets its own random debug key, and installing a debug APK built
            // elsewhere over one built locally fails with "App not installed"
            // until the old one is uninstalled first.
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // keystore/release.jks itself is gitignored (real secret, unlike the debug
        // keystore above) — only its password ever needs to exist outside this repo,
        // via RELEASE_STORE_PASSWORD. Every future release must be signed with this
        // same key for Android to accept it as an update, so don't regenerate this
        // file once a real release has shipped.
        create("release") {
            storeFile = rootProject.file("keystore/release.jks")
            storePassword = System.getenv("RELEASE_STORE_PASSWORD")
            keyAlias = "release"
            keyPassword = System.getenv("RELEASE_STORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Falls back to unsigned when RELEASE_STORE_PASSWORD isn't set, rather
            // than failing the build — lets assembleRelease still work (unsigned) on
            // a machine/CI job that has no reason to hold the real release key.
            if (System.getenv("RELEASE_STORE_PASSWORD") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
