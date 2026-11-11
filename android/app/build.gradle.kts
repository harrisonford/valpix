import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The upload key and its password live outside the repo (see README). Without
// them a release build is still made, but left unsigned.
val keystoreProperties = file(
    providers.gradleProperty("valpix.keystore").orNull ?: "${System.getProperty("user.home")}/.config/valpix/keystore.properties"
)
val keystore = Properties().apply { if (keystoreProperties.exists()) keystoreProperties.inputStream().use(::load) }

android {
    namespace = "com.franky.valpix"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.franky.valpix"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Which program the app talks to, and where it reaches Solana:
        // access points are tried in order, after the person's own.
        buildConfigField("String", "PROGRAM_ID", "\"5AVhdmuAbx8HWJdR6h7QAPojfJHt7Z6TAa9pwj12wqcx\"")
        buildConfigField("String", "ACCESS_POINTS", "\"https://api.devnet.solana.com\"")
    }

    signingConfigs {
        if (!keystore.isEmpty) {
            create("upload") {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Not shrunk: the app is small, and nothing can then be stripped by mistake.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload")
            // -Pvalpix.idSuffix=.rc lets a release build sit beside the debug app on a test phone.
            applicationIdSuffix = providers.gradleProperty("valpix.idSuffix").orNull
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/**") }
    lint { disable += "InvalidFragmentVersionForActivityResult" }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.coroutines.android)
}
