package pl.syntaxdevteam.authgatewayx.integrations.network

import java.net.InetAddress
import java.util.concurrent.CompletionStage

/** Nullable fields mean unavailable, not a negative determination. */
data class IpIntelligence(
    val vpn: Boolean?,
    val proxy: Boolean?,
    val tor: Boolean?,
    val countryCode: String?,
    val asn: String?,
    val continent: String? = null,
    val country: String? = null,
    val region: String? = null,
    val city: String? = null,
    val timezone: String? = null,
    val provider: String? = null,
    val organisation: String? = null,
    val networkType: String? = null,
    val operatorName: String? = null,
    val confidence: Int? = null,
    val riskScore: Int? = null,
) {
    val suspicious: Boolean get() = vpn == true || proxy == true || tor == true
}

fun interface IpIntelligenceLookup {
    fun lookup(address: InetAddress): CompletionStage<IpIntelligence?>
}
