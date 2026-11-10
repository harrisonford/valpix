plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

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
