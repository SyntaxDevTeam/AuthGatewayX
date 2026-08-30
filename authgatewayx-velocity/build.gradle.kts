plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    alias(libs.plugins.shadow)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://nexus.syntaxdevteam.pl/repository/maven-snapshots/")
    maven("https://nexus.syntaxdevteam.pl/repository/maven-releases/")
}

dependencies {
    compileOnly(libs.velocity.api)
    implementation(project(":authgatewayx-domain"))
    implementation(project(":authgatewayx-security"))
    implementation(project(":authgatewayx-integrations"))
    implementation("pl.syntaxdevteam:syntaxcore:1.4.1-R0.1-SNAPSHOT")
    implementation("pl.syntaxdevteam:messageHandler-velocity:1.2.2-R0.4-SNAPSHOT")
    implementation("org.yaml:snakeyaml:2.5")
    implementation(libs.kotlin.stdlib)
}

kotlin { jvmToolchain(25) }

tasks {
    processResources {
        val props = mapOf("version" to version.toString())
        inputs.properties(props)
        filesMatching("velocity-plugin.json") { expand(props) }
    }
    shadowJar {
        archiveClassifier.set("")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    build { dependsOn(shadowJar) }
}
