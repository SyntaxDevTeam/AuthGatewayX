plugins {
    kotlin("jvm")
}

repositories { mavenCentral() }
dependencies {
    api(project(":authgatewayx-domain"))
    api(project(":authgatewayx-api"))
    implementation(project(":authgatewayx-security"))
    compileOnly(libs.gson)
    testImplementation(libs.gson)
    testImplementation(libs.kotlin.test)
}
tasks.test { useJUnitPlatform() }
