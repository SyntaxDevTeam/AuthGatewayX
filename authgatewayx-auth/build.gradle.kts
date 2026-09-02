plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
}

repositories { mavenCentral() }

dependencies {
    implementation(project(":authgatewayx-domain"))
    implementation(project(":authgatewayx-security"))
    implementation(project(":authgatewayx-storage-api"))
    compileOnly(libs.jetbrains.annotations)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.argon2.jvm)
}

kotlin { jvmToolchain(25) }
tasks.test { useJUnitPlatform() }
