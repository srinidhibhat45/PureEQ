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

// Release signing key (docs/key-rotation.md): the PUREEQ_* environment variables when any is set,
// otherwise a git-ignored keystore.properties at the repo root. With neither, release builds keep
// this computer's debug key, as before, so the copies already installed still accept updates.
val signingNames = mapOf(
    "storeFile" to "PUREEQ_KEYSTORE",
    "storePassword" to "PUREEQ_KEYSTORE_PASSWORD",
    "keyAlias" to "PUREEQ_KEY_ALIAS",
    "keyPassword" to "PUREEQ_KEY_PASSWORD",
)
val releaseKey: Map<String, String>? = run {
    val env = signingNames.mapValues { (_, name) -> providers.environmentVariable(name).orNull.orEmpty() }
    if (env.values.any { it.isNotBlank() }) {
        val missing = env.filterValues { it.isBlank() }.keys.map { signingNames.getValue(it) }
        if (missing.isNotEmpty()) throw GradleException("Set ${missing.joinToString()} too, or none of the PUREEQ_* variables (docs/key-rotation.md).")
        return@run env
    }
    if (!rootProject.file("keystore.properties").exists()) return@run null
    val file = props("keystore.properties").let { p -> signingNames.mapValues { (key, _) -> p.getProperty(key).orEmpty() } }
    val missing = file.filterValues { it.isBlank() }.keys
    if (missing.isNotEmpty()) throw GradleException("keystore.properties has no ${missing.joinToString()} (docs/key-rotation.md).")
    file
}

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
    }

    // Same app (applicationId) either way; only how it's updated differs.
    flavorDimensions += "distribution"
    productFlavors {
        // Sideloaded from GitHub Releases: updates itself (src/github, ota/publish.py).
        create("github") {
            dimension = "distribution"
            buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
            buildConfigField("boolean", "SELF_UPDATER", "true")
        }
        // Google Play updates it: no self-updater, install permissions or update checks (src/play).
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATER", "false")
        }
    }

    signingConfigs {
        releaseKey?.let { key ->
            create("release") {
                storeFile = rootProject.file(key.getValue("storeFile"))
                storePassword = key.getValue("storePassword")
                keyAlias = key.getValue("keyAlias")
                keyPassword = key.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The release key when one is configured (above), otherwise the local debug key as before.
            signingConfig = signingConfigs.getByName(if (releaseKey != null) "release" else "debug")
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
