import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun props(name: String) = Properties().apply {
    rootProject.file(name).takeIf { it.exists() }?.reader()?.use { load(it) }
}

val versionProps = props("version.properties")

// Where installed copies look for updates. ota/publish.py writes the GitHub repo to ota.properties
// (and passes -Pota.repo); -Pota.manifestUrl points a test build at a local server instead.
// Empty means this build can't update itself.
val otaRepo = providers.gradleProperty("ota.repo").orNull ?: props("ota.properties").getProperty("repo", "")
val updateUrl = providers.gradleProperty("ota.manifestUrl").orNull
    ?: otaRepo.trim().takeIf { it.isNotEmpty() }?.let { "https://github.com/$it/releases/latest/download/update.json" }
    ?: ""

android {
    namespace = "app.pureeq"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.pureeq"
        // DynamicsProcessing (per-channel system EQ) needs API 28.
        minSdk = 28
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the local debug key so the APK can be sideloaded.
            // Use a real keystore before distributing it anywhere.
            signingConfig = signingConfigs.getByName("debug")
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    // Pass -Dpureeq.wavOut=<file> to write the synthesised loop to a WAV for listening.
    System.getProperty("pureeq.wavOut")?.let { systemProperty("pureeq.wavOut", it) }
    testLogging { events("failed"); showStandardStreams = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // android.jar stubs org.json in local unit tests; use the real library there.
    testImplementation(libs.org.json)
}
