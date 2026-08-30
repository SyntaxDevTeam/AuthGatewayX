plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
}

repositories { mavenCentral() }
dependencies {
    api(project(":authgatewayx-domain"))
    implementation(project(":authgatewayx-security"))
}
kotlin { jvmToolchain(25) }
