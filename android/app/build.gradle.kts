plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "io.github.unfo17.reader"
    compileSdk = 35
    defaultConfig { applicationId = "io.github.unfo17.reader"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "0.2.0-dev"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    sourceSets {
        getByName("main") {
            manifest.srcFile("src/compose/AndroidManifest.xml")
            java.setSrcDirs(listOf("src/compose/kotlin", "../../third_party/juniversalchardet-2.0.4/src/main/java"))
            res.setSrcDirs(listOf("src/compose/res"))
            assets.setSrcDirs(emptyList<String>())
        }
        getByName("androidTest") {
            java.setSrcDirs(listOf("src/composeTest/java"))
            assets.setSrcDirs(listOf("src/composeTest/assets"))
        }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}
dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.readium.kotlin-toolkit:readium-shared:3.1.2")
    implementation("org.readium.kotlin-toolkit:readium-streamer:3.1.2")
    implementation("org.readium.kotlin-toolkit:readium-navigator:3.1.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
}

// The original local core keeps its package and implementation; Flutter adapters stay outside this build.
kotlin.sourceSets.getByName("main").kotlin.setSrcDirs(listOf(
    "src/compose/kotlin", "src/main/kotlin/org/mewx/wenku8_flutter/localcore"
))
