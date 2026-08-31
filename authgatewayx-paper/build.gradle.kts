plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    alias(libs.plugins.paperweight.userdev)
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

repositories {
    mavenCentral()
    maven("https://nexus.syntaxdevteam.pl/repository/maven-snapshots/")
    maven("https://nexus.syntaxdevteam.pl/repository/maven-releases/")
}

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.api.get())
    implementation(project(":authgatewayx-api"))
    implementation(project(":authgatewayx-auth"))
    implementation(project(":authgatewayx-domain"))
    implementation(project(":authgatewayx-security"))
    implementation(project(":authgatewayx-storage-jdbc"))
    implementation(project(":authgatewayx-storage-api"))
    implementation(project(":authgatewayx-integrations"))
    implementation(libs.kotlin.stdlib)
    compileOnly("pl.syntaxdevteam:syntaxcore:1.4.1-R0.1-SNAPSHOT")
    compileOnly("pl.syntaxdevteam:messageHandler-paper:1.2.2-R0.4-SNAPSHOT")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(25) }

tasks {
    build { dependsOn(shadowJar) }

    runServer {
        minecraftVersion(libs.versions.minecraft.get())
        runDirectory(rootProject.file("run"))
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("paper-plugin.yml") { expand(props) }
    }

    shadowJar {
        archiveBaseName.set("AuthGatewayX")
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        archiveClassifier.set("")
    }

    test { useJUnitPlatform() }
}
