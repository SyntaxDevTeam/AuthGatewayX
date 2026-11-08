plugins {
    kotlin("jvm")
}

repositories { mavenCentral() }

dependencies {
    implementation(project(":authgatewayx-api"))
    implementation(project(":authgatewayx-domain"))
    implementation(project(":authgatewayx-integrations"))
    implementation(project(":authgatewayx-security"))
    implementation(project(":authgatewayx-storage-api"))
    compileOnly(libs.jetbrains.annotations)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.argon2.jvm)
}

tasks.test { useJUnitPlatform() }
