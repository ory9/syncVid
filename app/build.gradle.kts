plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.synclyrics"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.synclyrics"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Override in ~/.gradle/gradle.properties or -P. 10.0.2.2 = host machine from the Android emulator.
        val backendUrl = (project.findProperty("synclyrics.backendUrl") as String?)?.takeIf { it.isNotBlank() } ?: "http://10.0.2.2:8080"
        val backendKey = (project.findProperty("synclyrics.apiKey") as String?) ?: ""
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
        buildConfigField("String", "BACKEND_API_KEY", "\"$backendKey\"")
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

val copyDebugApkToProjectRoot = tasks.register<Copy>("copyDebugApkToProjectRoot") {
    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("*.apk")
        rename { "synclyrics-debug.apk" }
    }
    into(rootProject.layout.projectDirectory)
    doFirst {
        check(fileTree(layout.buildDirectory.dir("outputs/apk/debug")) { include("*.apk") }.files.isNotEmpty()) {
            "No debug APK was produced to copy."
        }
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(copyDebugApkToProjectRoot)
}
