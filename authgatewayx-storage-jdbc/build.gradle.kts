plugins { kotlin("jvm") version libs.versions.kotlin.get() }
repositories { mavenCentral() }
dependencies {
    implementation(project(":authgatewayx-storage-api"))
    implementation(project(":authgatewayx-security"))
    compileOnly(libs.hikari)
    compileOnly(libs.sqlite.jdbc)
    compileOnly(libs.mysql.jdbc)
    compileOnly(libs.mariadb.jdbc)
    compileOnly(libs.postgresql.jdbc)
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite.jdbc)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(kotlin("test"))
}
kotlin { jvmToolchain(25) }
tasks.test { useJUnitPlatform() }
