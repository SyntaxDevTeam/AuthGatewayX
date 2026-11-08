package pl.syntaxdevteam.authgatewayx.paper.privacy

import kotlin.test.Test
import kotlin.test.assertEquals

class ConsentPresentationSelectorTest {
    @Test
    fun earlierVersionsUseChat() {
        for (version in listOf("1.20.6", "1.21", "1.21.1", "1.21.6")) {
            assertEquals(ConsentPresentation.CHAT, ConsentPresentationSelector.select(version, true), version)
        }
    }

    @Test
    fun modernVersionsUseNativeDialog() {
        for (version in listOf("1.21.7", "1.21.7-R0.1-SNAPSHOT", "1.21.8", "1.21.10", "1.22", "26.1", "26.3")) {
            assertEquals(ConsentPresentation.MINECRAFT_DIALOG, ConsentPresentationSelector.select(version, true), version)
        }
    }

    @Test
    fun disabledDialogsForceChat() {
        assertEquals(ConsentPresentation.CHAT, ConsentPresentationSelector.select("26.3", false))
    }

    @Test
    fun unknownVersionsFailSafeToChat() {
        for (version in listOf("", "snapshot", "Paper 26.3", "1.21.6.1", "999999999999.2")) {
            assertEquals(ConsentPresentation.CHAT, ConsentPresentationSelector.select(version, true), version)
        }
    }
}
