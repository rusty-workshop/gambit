import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val gambit = Properties().apply {
    val f = rootProject.file("gambit.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(key: String) = "\"" + (gambit.getProperty(key) ?: "").replace("\"", "\\\"") + "\""

android {
    namespace = "com.rusty.gambit"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.rusty.gambit"
        minSdk = 30 // Wear OS 3+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "SERVER_LAN", prop("server.lan"))
        buildConfigField("String", "SERVER_FUNNEL", prop("server.funnel"))
        buildConfigField("String", "SERVER_KEY", prop("server.key"))
    }

    buildTypes {
        release {
            // Same as Wrist: the debug build is far too slow on the watch. Signed with the debug key so it updates in place.
            optimization { enable = true }
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.wear.complications)
}
