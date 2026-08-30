plugins { kotlin("jvm") version libs.versions.kotlin.get() }
repositories { mavenCentral() }
dependencies {
    implementation(project(":authgatewayx-storage-api"))
    implementation(project(":authgatewayx-security"))
    implementation(libs.hikari)
    runtimeOnly(libs.sqlite.jdbc)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(kotlin("test"))
}
kotlin { jvmToolchain(25) }
tasks.test { useJUnitPlatform() }
