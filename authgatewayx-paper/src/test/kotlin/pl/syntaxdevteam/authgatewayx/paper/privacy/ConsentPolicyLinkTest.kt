package pl.syntaxdevteam.authgatewayx.paper.privacy

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsentPolicyLinkTest {
    private val messages = javaClass.getResourceAsStream("/lang/messages_pl.yml")!!.reader().use {
        YamlConfiguration.loadConfiguration(it)
    }

    private fun message(key: String) = MiniMessage.miniMessage().deserialize(messages.getString("privacy.$key")!!)

    private fun clicks(component: Component): List<ClickEvent<*>> =
        listOfNotNull(component.clickEvent()) + component.children().flatMap(::clicks)

    @Test
    fun defaultLinkOpensExampleWebsiteWithoutConfigurationOverride() {
        assertEquals(listOf(ClickEvent.openUrl("https://example.com/privacy/")),
            clicks(consentPolicyLink(message("link"), "")))
    }

    @Test
    fun configuredUrlOverridesNestedLanguageFileClicks() {
        val label = MiniMessage.miniMessage().deserialize(
            "<gray>Polityka <click:open_url:'https://example.com/privacy/'><aqua>otwórz</aqua></click></gray>",
        )
        val rendered = consentPolicyLink(label, "https://server.example/privacy")
        assertEquals(listOf(ClickEvent.openUrl("https://server.example/privacy")), clicks(rendered))
        assertEquals(
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(label),
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(rendered),
        )
    }

    @Test
    fun defaultButtonsRunConsentCommands() {
        assertEquals(listOf(ClickEvent.runCommand("/privacy accept")), clicks(message("accept_button")))
        assertEquals(listOf(ClickEvent.runCommand("/privacy reject")), clicks(message("reject_button")))
        assertTrue(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(message("summary")).isNotBlank())
    }
}
