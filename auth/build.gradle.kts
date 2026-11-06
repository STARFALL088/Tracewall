// Shared Java-only authentication UI. Firebase configuration belongs to each app.
plugins {
    id("com.android.library")
}

android {
    namespace = "tracewall.auth"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
        targetSdk = 34
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.6.2")
    implementation("androidx.lifecycle:lifecycle-livedata:2.6.2")
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-auth")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxHeapSize = "512m"
    maxParallelForks = 1
    if (providers.gradleProperty("authEmulator").orNull == "true") {
        include("**/BoundAccountRecoveryEmulatorTest.class")
        systemProperty("tracewall.auth.emulator", "true")
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        // AGP assigns the unit-test report locations after task configuration. Archive the
        // actual reports after execution so the following regular suite cannot overwrite them.
        doLast {
            project.copy {
                from(reports.junitXml.outputLocation)
                include("TEST-*.xml")
                into(project.layout.buildDirectory.dir("test-results/authEmulator"))
            }
            project.copy {
                from(reports.html.outputLocation)
                into(project.layout.buildDirectory.dir("reports/tests/authEmulator"))
            }
        }
    } else {
        exclude("**/BoundAccountRecoveryEmulatorTest.class")
    }
}
