package pl.syntaxdevteam.authgatewayx.paper.privacy

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent

/** Preserve the language-file URL unless configuration explicitly overrides it. */
internal fun consentPolicyLink(label: Component, policyUrl: String): Component {
    if (policyUrl.isEmpty()) return label
    // Nested MiniMessage click tags must not shadow the configured URL.
    fun withoutClicks(component: Component): Component =
        component.clickEvent(null).children(component.children().map(::withoutClicks))
    return withoutClicks(label).clickEvent(ClickEvent.openUrl(policyUrl))
}
