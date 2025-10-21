plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
    id("kotlin-parcelize")
}

kotlin {
    jvmToolchain(17)
}

kapt {
    arguments {
        arg("room.skipVerification", "true")
    }
}

android {
    namespace = "com.example.shieldshare"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.shieldshare"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    // use Jetpack Compose
    buildFeatures {
        viewBinding = false
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { 
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:suppressKotlinVersionCompatibilityCheck=true"
        )
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    // core & UI
    implementation("androidx.core:core-ktx:1.13.1")

    // Jetpack Compose
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui:1.5.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.5.4")
    implementation("androidx.compose.material3:material3:1.1.2")
    implementation("androidx.compose.material:material-icons-extended:1.5.4")
    
    // Compose Navigation
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // WorkManager & Room
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // security setting
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // internet
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // QR Code generation
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Test dependencies
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// Ensure kapt's worker JVM writes sqlite temp files to a user-writable directory
val userTempDir = System.getenv("TEMP") ?: System.getenv("TMP") ?: "${project.rootDir}/build/tmp"

// Create tmp directory if it doesn't exist
tasks.register("createTmpDir") {
    doLast {
        val tmpDir = file("${project.rootDir}/build/tmp")
        if (!tmpDir.exists()) {
            tmpDir.mkdirs()
            println("Created tmp directory: ${tmpDir.absolutePath}")
        }
    }
}

// Ensure tmp directory is created before any compilation tasks
tasks.withType(org.jetbrains.kotlin.gradle.internal.KaptWithoutKotlincTask::class.java).configureEach {
    dependsOn("createTmpDir")
    kaptProcessJvmArgs.addAll(listOf(
        "-Djava.io.tmpdir=${userTempDir}",
        "-Dorg.sqlite.tmpdir=${userTempDir}"
    ))
}

// Also ensure tmp directory is created for other compilation tasks
tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach {
    dependsOn("createTmpDir")
}
