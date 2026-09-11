package pl.syntaxdevteam.authgatewayx.integrations.network

import java.net.InetAddress
import java.util.concurrent.CompletionStage

/** Nullable fields mean unavailable, not a negative determination. */
data class IpIntelligence(
    val vpn: Boolean?, val proxy: Boolean?, val tor: Boolean?,
    val countryCode: String?, val asn: String?,
) {
    val suspicious: Boolean get() = vpn == true || proxy == true || tor == true
}

fun interface IpIntelligenceLookup {
    fun lookup(address: InetAddress): CompletionStage<IpIntelligence?>
}
