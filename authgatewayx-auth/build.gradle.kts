plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
}

repositories { mavenCentral() }

dependencies {
    implementation(project(":authgatewayx-domain"))
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(25) }
tasks.test { useJUnitPlatform() }
