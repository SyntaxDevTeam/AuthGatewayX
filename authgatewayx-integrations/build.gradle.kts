plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
}

repositories { mavenCentral() }
dependencies {
    api(project(":authgatewayx-domain"))
    implementation(project(":authgatewayx-security"))
    testImplementation(kotlin("test"))
}
kotlin { jvmToolchain(25) }
tasks.test { useJUnitPlatform() }
