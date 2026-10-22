import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release-signing credentials live outside VCS (see README, "Release
// signing"): a keystore.properties file at the repo root, or SYSMON_*
// environment variables (CI). Without either, everything still configures and
// debug builds work on any machine; only packaging a release is refused (see
// checkReleaseSigning below).
val keystoreProperties = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    keystoreProperties.getProperty(key) ?: providers.environmentVariable(env).orNull

val releaseStoreFile = signingValue("storeFile", "SYSMON_KEYSTORE_FILE")

android {
    namespace = "com.mxrider.sysmon"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mxrider.sysmon"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("storePassword", "SYSMON_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SYSMON_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SYSMON_KEY_PASSWORD")
            }
        }
    }

    androidResources {
        // Package only the app's own locales; without this, library resources
        // (Compose, Glance) ship dozens of locales the app has no strings
        // for, and the system language picker would offer them anyway.
        // Keep in sync with res/xml/locales_config.xml.
        localeFilters += listOf("en", "de", "es", "fr", "it", "ja", "ko", "pl", "pt-rBR")
    }

    // Two Play listings, one codebase: "free" is the main app and keeps the
    // original applicationId. "supporter" is the
    // feature-identical paid donation edition; it differs ONLY in package
    // name, launcher label and the About card's edition line (gated on
    // R.bool.is_supporter_edition). Feature gating is deliberately absent —
    // the free app is complete, and with no INTERNET permission Play Billing
    // can never exist here, so a second paid listing is the only possible
    // donation mechanism. Both editions install side by side (FileProvider
    // authority already derives from ${applicationId}).
    flavorDimensions += "edition"
    productFlavors {
        create("free") {
            dimension = "edition"
        }
        create("supporter") {
            dimension = "edition"
            applicationIdSuffix = ".supporter"
        }
    }

    buildTypes {
        debug {
            // Installs ALONGSIDE production so run-as can inspect app-private
            // files (perf log, CSV export) without touching the release
            // install — run-as does not work on release builds. Safe because
            // the FileProvider authority derives from applicationId, so the
            // two variants never conflict.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Null when no credentials are configured. Throwing here would run
            // at configuration time and break EVERY build (debug included) on
            // machines without the keystore, so the check is deferred to
            // checkReleaseSigning, which only runs when a release is packaged.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// An unsigned release only fails much later, at Play upload. Refuse to package
// one instead - at execution time, so debug builds and IDE sync never need the
// keystore. -Psysmon.allowUnsignedRelease=true opts out (CI compile checks,
// builds that are signed separately afterwards).
val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    val signed = releaseStoreFile != null
    val allowUnsigned = providers.gradleProperty("sysmon.allowUnsignedRelease").orNull.toBoolean()
    doLast {
        if (!signed && !allowUnsigned) {
            throw GradleException(
                "Release signing is not configured. Add keystore.properties (see README) " +
                    "or pass -Psysmon.allowUnsignedRelease=true for an unsigned build.",
            )
        }
    }
}
tasks.configureEach {
    if (name.startsWith("package") && (name.endsWith("Release") || name.endsWith("ReleaseBundle"))) {
        dependsOn(checkReleaseSigning)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.glance.appwidget)
    // Explicit, only to lift Glance's transitive work-runtime 2.7.1 (and the
    // room 2.2.5 it drags along) to a maintained version. WorkManager is not
    // used directly anywhere in this app.
    implementation(libs.androidx.work.runtime)
    // debug-only: there are no @Preview functions in the release sources.
    debugImplementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
