// Tracewall root build.
//
// Modules:
//   blocklist - pure JVM, no Android imports. Unit-testable without the SDK.
//   app       - the child's phone (VpnService, Room, Firebase, UI)     [needs SDK]
//   parent    - the parent's phone (pairing, remote rules)              [needs SDK]
//
// Toolchain: Gradle runs on JDK 17 (/usr/lib/jvm/java-17-openjdk, pinned in
// gradle.properties) because AGP 8.5 is tested against 17 - the machine
// default JDK 27 breaks its bytecode tooling. Gradle 8.9 is pinned in the
// wrapper for the same reason: Gradle 9 removed APIs AGP 8.5 still calls.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Tracewall"

include(":blocklist")
include(":app")
include(":parent")
