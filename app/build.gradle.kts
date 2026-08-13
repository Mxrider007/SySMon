import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Upload-key credentials live outside VCS (see PLAY_STORE.md §3). When the
// file is absent the release build still configures — it just comes out
// unsigned, so debug work on other machines is never blocked.
val keystoreProperties = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}

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
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
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

    // Two Play listings, one codebase (s16): "free" is the main app and keeps
    // the original applicationId — it inherits every artifact and Console
    // answer prepared before the free/supporter split. "supporter" is the
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
            // install — run-as does not work on release builds. Safe since
            // s14: the FileProvider authority derives from applicationId
            // (landmines #32/#33), so the two variants no longer conflict.
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
            // findByName returns null when keystore.properties is absent,
            // which would silently produce an UNSIGNED release that only fails
            // at Play upload. Fail at build time instead.
            signingConfig = signingConfigs.findByName("release")
                ?: throw GradleException(
                    "Release signing config missing - keystore.properties not found. " +
                        "Restore it from your password manager before building a release.",
                )
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
