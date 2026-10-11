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
    testRuntimeOnly(libs.argon2.jvm)
    testRuntimeOnly(libs.hikari)
    testRuntimeOnly(libs.sqlite.jdbc)
    testRuntimeOnly(libs.mysql.jdbc)
    testRuntimeOnly(libs.mariadb.jdbc)
    testRuntimeOnly(libs.postgresql.jdbc)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.kotlin.test)
}
tasks.test { useJUnitPlatform() }


// This tool lives exclusively on the test classpath, outside distributable plugin JARs.
tasks.register<JavaExec>("createOfflineMigrationFixture") {
    group = "verification"
    description = "Creates a fresh isolated SQLite OFFLINE account for real-client premium migration tests."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("pl.syntaxdevteam.authgatewayx.storage.jdbc.fixture.OfflineMigrationFixture")
    val username = providers.gradleProperty("fixtureUsername")
    val destination = rootProject.layout.buildDirectory.dir("test-fixtures/premium-migration")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(username.orNull ?: error("Specify -PfixtureUsername=YourPremiumNickname"), destination.get().asFile.absolutePath)
    })
}
