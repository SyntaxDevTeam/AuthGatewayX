package pl.syntaxdevteam.logincorex.paper.loader

import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SyntaxLoaderTest {
    @Test fun `central marker respects Paper mirror configuration`() {
        assertEquals(MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR,
            SyntaxLoader.addressForCentral(mapOf("central" to "paper-default-mirror")))
    }

    @Test fun `explicit HTTPS central is preserved`() {
        assertEquals("https://mirror.example/maven2/",
            SyntaxLoader.addressForCentral(mapOf("central" to "https://mirror.example/maven2/")))
    }

    @Test fun `missing or insecure central is rejected`() {
        for (config in listOf(emptyMap<String, String>(), mapOf("central" to "http://mirror.example/"))) {
            assertFailsWith<IllegalStateException> { SyntaxLoader.addressForCentral(config) }
        }
    }
}
