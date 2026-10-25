// Tracewall child-side app. JAVA ONLY - the course requires Java on Android,
// and mixing Kotlin in would be one more toolchain to explain.
plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
}

android {
    namespace = "tracewall.child"
    compileSdk = 34

    defaultConfig {
        applicationId = "tracewall.child"
        minSdk = 24          // VpnService is 14+, but the UI and Room want more
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation(project(":blocklist"))
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.room:room-runtime:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")
    testImplementation("junit:junit:4.13.2")

    // ZXing core: generate QR codes for the device-code pairing dialog.
    // No camera permission needed - this is the encode-only library.
    implementation("com.google.zxing:core:3.5.3")

    // Firebase - use the BOM so all Firebase libraries stay version-consistent.
    // Replace app/google-services.json with the real one from your Firebase console.
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-firestore")
}
