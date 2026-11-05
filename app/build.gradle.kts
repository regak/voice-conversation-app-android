import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "com.regak.voiceorb"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.regak.voiceorb"
        minSdk = 24
        targetSdk = 34
        versionCode = 30
        versionName = "0.26.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // Gemini API key, read from local.properties (git-ignored) at build time.
        val geminiApiKey = (localProperties.getProperty("GEMINI_API_KEY")
            ?: project.findProperty("GEMINI_API_KEY") as String?
            ?: "")
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")

        // YouTube Data API v3 key, used to resolve a spoken search query into
        // a real, exact video (instead of trusting the model to guess a URL).
        val youtubeApiKey = (localProperties.getProperty("YOUTUBE_API_KEY")
            ?: project.findProperty("YOUTUBE_API_KEY") as String?
            ?: "")
        buildConfigField("String", "YOUTUBE_API_KEY", "\"$youtubeApiKey\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // NOTE: deliberately NOT depending on org.json:json here. Android's
    // bootclasspath provides its own stripped-down org.json implementation
    // that wins at runtime regardless of what's on the compile classpath --
    // compiling against the desktop org.json library let code call methods
    // (e.g. JSONObject.put(String, Collection)) that don't exist on-device,
    // causing a NoSuchMethodError at runtime despite a clean compile. Code
    // must only use the methods available in Android's actual org.json.

    debugImplementation("androidx.compose.ui:ui-tooling")
}
