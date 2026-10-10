plugins { kotlin("jvm") }
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
    testRuntimeOnly(libs.mysql.jdbc)
    testRuntimeOnly(libs.mariadb.jdbc)
    testRuntimeOnly(libs.postgresql.jdbc)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.kotlin.test)
}
tasks.test { useJUnitPlatform() }
