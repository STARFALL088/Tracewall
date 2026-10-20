// Tracewall root build - JAVA ONLY, no Kotlin anywhere.
//
// Modules:
//   blocklist - pure JVM, no Android imports. Unit-testable without the SDK.
//   app       - the child's phone (VpnService, Room, Firebase, UI)     [needs SDK]
//   parent    - the parent's phone (pairing, remote rules)              [needs SDK]
//
// This machine has only JDK 27 (/usr/lib/jvm/java-27-openjdk). The foojay
// resolver in settings.gradle.kts provisions a real JDK 17 toolchain into
// ~/.gradle/jdks without sudo. Once `sudo pacman -S jdk17-openjdk` is done,
// delete the foojay plugin from settings.gradle.kts - it is then redundant.

plugins {
    id("com.android.application") version "8.5.2" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}