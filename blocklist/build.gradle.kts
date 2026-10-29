// Tracewall pure-JVM core. NO KOTLIN, NO ANDROID.
//
// Everything here is plain Java 17 and imports nothing from android.*, which
// is the whole point: the DNS wire codec, the IP/UDP codec and the blocklist
// engine are all testable on a laptop in milliseconds with no emulator, no
// device and no SDK.
//
// The Android app module depends on this; nothing here depends on the app.
//
// Toolchain: this machine only has JDK 27 (/usr/lib/jvm/java-27-openjdk), and
// the foojay resolver in settings.gradle.kts provisions a real JDK 17 into
// ~/.gradle/jdks without sudo. If you install jdk17-openjdk system-wide,
// drop foojay from settings.gradle.kts.
plugins {
    id("java-library")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    // Android supplies org.json at runtime; the JVM tests use the same API.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
