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
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

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
