plugins {
    kotlin("jvm")
    alias(libs.plugins.shadow)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "paperPublic"
        content { includeGroup("com.velocitypowered") }
    }
    maven("https://nexus.syntaxdevteam.pl/repository/maven-snapshots/") {
        name = "syntaxSnapshots"
        content { includeGroup("pl.syntaxdevteam") }
    }
    maven("https://nexus.syntaxdevteam.pl/repository/maven-releases/") {
        name = "syntaxReleases"
        content { includeGroup("pl.syntaxdevteam") }
    }
}

dependencies {
    compileOnly(libs.velocity.api)
    implementation(project(":authgatewayx-domain"))
    implementation(project(":authgatewayx-storage-api"))
    implementation(project(":authgatewayx-storage-jdbc"))
    implementation(libs.hikari)
    implementation(libs.sqlite.jdbc)
    implementation(libs.mysql.jdbc)
    implementation(libs.mariadb.jdbc)
    implementation(libs.postgresql.jdbc)
    implementation(libs.gson)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.velocity.api)
    implementation(project(":authgatewayx-security"))
    implementation(project(":authgatewayx-integrations"))
    implementation(libs.syntaxcore)
    implementation(libs.messagehandler.velocity)
    implementation(libs.snakeyaml)

    implementation(libs.kotlin.stdlib)
}

tasks {
    jar {
        // Never let the plain JAR race with shadowJar for the same output path.
        // CI verifies and publishes only the shaded artifact.
        archiveClassifier.set("plain")
    }

    processResources {
        val props = mapOf("version" to version.toString())
        inputs.properties(props)
        filesMatching("velocity-plugin.json") { expand(props) }
    }
    shadowJar {
        archiveBaseName.set("AuthGatewayX-Velocity")
        archiveClassifier.set("")
        mergeServiceFiles()
        filesMatching("META-INF/*.kotlin_module") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
        filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    build { dependsOn(shadowJar) }
}

tasks.test { useJUnitPlatform() }
