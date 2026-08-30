plugins { kotlin("jvm") version libs.versions.kotlin.get() }
repositories { mavenCentral() }
dependencies { api(project(":authgatewayx-domain")) }
kotlin { jvmToolchain(25) }
