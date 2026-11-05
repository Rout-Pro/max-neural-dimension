plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.max.neuraldimension"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.max.neuraldimension"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
        // CI-only UI-test hook. The distributed APK (Build workflow) is compiled
        // without -PND_TEST_HOOK, so the hook is absent from what users install.
        val testHook = (project.findProperty("ND_TEST_HOOK") as String?) ?: "false"
        buildConfigField("boolean", "ND_TEST_HOOK", testHook)
        // Mock-backend cleartext only when the CI hook is compiled in; the Build
        // workflow (distributed APK) gets the strict config - HTTPS-only.
        manifestPlaceholders["netSec"] = if (testHook == "true") "@xml/nd_debug_network" else "@xml/nd_strict_network"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
