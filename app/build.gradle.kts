plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// CI supplies an unchanged signing key through GitHub Actions Secrets.
// Never commit the keystore or passwords into this public repository.
val stableSigningFile = System.getenv("PSR_SIGNING_FILE")
val stableSigningPassword = System.getenv("PSR_SIGNING_PASSWORD")
val stableSigningReady = !stableSigningFile.isNullOrBlank() && !stableSigningPassword.isNullOrBlank()

android {
    namespace = "dev.automationse.pokesleeprater"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.automationse.pokesleeprater"
        minSdk = 30
        targetSdk = 35
        // 1000 + GitHub Actions run_number is used for signed CI releases.
        // A larger versionCode allows in-place APK updates.
        versionCode = providers.gradleProperty("ciVersionCode").orNull?.toIntOrNull() ?: 4
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (stableSigningReady) {
            create("stableRelease") {
                storeFile = file(stableSigningFile!!)
                storePassword = stableSigningPassword
                keyAlias = "pokesleeprater"
                keyPassword = stableSigningPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (stableSigningReady) {
                signingConfig = signingConfigs.getByName("stableRelease")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    testImplementation("junit:junit:4.13.2")
}
