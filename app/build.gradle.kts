// Tracewall child-side app. JAVA ONLY - the course requires Java on Android,
// and mixing Kotlin in would be one more toolchain to explain.
plugins {
    id("com.android.application")
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
    testImplementation("junit:junit:4.13.2")
}
