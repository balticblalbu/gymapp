import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Backend URL is configuration, not code. Order of precedence:
 *   1. local.properties -> api.base.url=...
 *   2. Gradle property   -> -PapiBaseUrl=...
 *   3. Default           -> 10.0.2.2 = host machine as seen from the emulator
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun resolveApiUrl(default: String): String =
    localProperties.getProperty("api.base.url")
        ?: (project.findProperty("apiBaseUrl") as String?)
        ?: default

/**
 * Anthropic key baked in as a build-time default so both phones get it
 * pre-filled without typing it in. Comes only from local.properties, which is
 * gitignored — the key never touches the repository. Settings still lets the
 * user override or clear it; this is a starting value, not a hardcoded fact.
 */
fun resolveDefaultAnthropicKey(): String = localProperties.getProperty("anthropic.api.key") ?: ""

android {
    namespace = "com.gymapp.tracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gymapp.tracker"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            buildConfigField("String", "API_BASE_URL", "\"${resolveApiUrl("http://10.0.2.2:3000/")}\"")
            buildConfigField("String", "DEFAULT_ANTHROPIC_KEY", "\"${resolveDefaultAnthropicKey()}\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_BASE_URL", "\"${resolveApiUrl("https://dein-server.example/")}\"")
            buildConfigField("String", "DEFAULT_ANTHROPIC_KEY", "\"${resolveDefaultAnthropicKey()}\"")

            // A debug-signed release build is enough to install an APK by hand.
            // Replace with a real keystore before distributing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
}
