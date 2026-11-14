plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.utar.runner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.utar.runner"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"

        // arm64-v8a only: the bundled Godot runtime and the custom Android
        // extension build from natives/android/<abi>/ both have to match.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }



    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            // The Godot AAR ships vendored Java helpers (APK expansion downloader)
            // that R8 would flag; keep shrinking off unless those rules are added.
            isMinifyEnabled = false
        }
    }

    sourceSets["main"].jniLibs.srcDir(rootProject.file("natives/android"))
    sourceSets["main"].assets.srcDir(rootProject.file("natives/utap_shims"))
}

// Always use the official Godot 4.1.4 export template from Maven. A locally built
// (custom) engine AAR broke font loading (its TextServer/FreeType path); until that
// template is rebuilt with font support, the official one is the only supported engine.
dependencies {
    implementation("org.godotengine:godot:4.1.4.stable")

    // FMOD's Java bridge (org.fmod.FMOD) must be dexed into the APK so the native
    // FMOD libraries can reach the JVM on Android, matching UTAP's FmodPlugin.
    implementation(files("libs/fmod.jar"))

    implementation("androidx.core:core-ktx:1.12.0")
    // The Godot library extends FragmentActivity but only declares androidx.fragment at
    // runtime scope, so it has to be added explicitly for compilation.
    implementation("androidx.fragment:fragment:1.6.2")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    implementation(platform("androidx.compose:compose-bom:2024.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
