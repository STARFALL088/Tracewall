// Tracewall parent-side app. JAVA ONLY - mirrors the child module convention.
plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
}

android {
    namespace = "tracewall.parent"
    compileSdk = 34

    defaultConfig {
        applicationId = "tracewall.parent"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "2.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation(project(":auth"))
    implementation(project(":blocklist"))
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // OpenStreetMap rendering requested for the isolated child-tracking feature.
    // 6.1.20 is the final Maven Central release; upstream was archived in 2024.
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")

    // ZXing embedded scanner: scan the child's QR code during pairing.
    // Brings in zxing:core transitively and declares CAMERA in its manifest.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Firebase - same BOM as the child module so versions stay in sync.
    // Replace parent/google-services.json with the real file from your Firebase console.
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-firestore")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxHeapSize = "512m"
    maxParallelForks = 1
}
