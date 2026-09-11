package pl.syntaxdevteam.authgatewayx.velocity

/** Defaults also apply when upgrading an existing authgatewayx.yml. */
data class VelocityRiskConfiguration(
    val storageEnabled: Boolean = false,
    val jdbcUrl: String = "jdbc:mysql://127.0.0.1:3306/authgatewayx",
    val databaseUsername: String = "authgatewayx_reader",
    val databasePassword: String = "",
    val multiAction: RiskAction = RiskAction.ALERT,
    val networkEnabled: Boolean = false,
    val apiKey: String = "",
    val networkAction: RiskAction = RiskAction.DENY,
    val failClosed: Boolean = true,
    val maximumConcurrent: Int = 16,
    val alertCooldownSeconds: Long = 300,
    val consoleAlerts: Boolean = true,
    val proofSecret: String = "",
    val networkTimeoutMillis: Long = 2000,
    val cacheTtlSeconds: Long = 3600,
    val failureTtlSeconds: Long = 60,
    val maximumCacheSize: Int = 10000,
    val networkConcurrent: Int = 2,
    val requestsPerMinute: Int = 30,
    val showGeo: Boolean = true,
    val alertsEnabled: Boolean = true,
) {
    init {
        require(networkTimeoutMillis in 100..10000 && cacheTtlSeconds in 1..86400 && failureTtlSeconds in 1..3600)
        require(maximumCacheSize in 1..100000 && networkConcurrent in 1..32 && requestsPerMinute in 1..1000)
        require(maximumConcurrent in 1..128 && alertCooldownSeconds in 1..86400)
        require(!storageEnabled || listOf("jdbc:mysql:", "jdbc:mariadb:", "jdbc:postgresql:", "jdbc:sqlite:").any(jdbcUrl::startsWith))
        require(proofSecret.isEmpty() || proofSecret.toByteArray().size >= 32) { "staff-proof.secret needs at least 32 bytes" }
    }
    companion object {
        fun parse(root: Map<String, Any>): VelocityRiskConfiguration {
            fun value(path: String): Any? = path.split('.').fold(root as Any?) { item, part -> (item as? Map<*, *>)?.get(part) }
            fun string(path: String, default: String) = value(path)?.let { require(it is String) { "$path must be text" }; it } ?: default
            fun bool(path: String, default: Boolean) = value(path)?.let { require(it is Boolean) { "$path must be boolean" }; it } ?: default
            fun number(path: String, default: Long) = value(path)?.let { require(it is Number) { "$path must be numeric" }; it.toLong() } ?: default
            val concurrent = number("risk.maximum-concurrent", 16)
            require(concurrent in 1..128)
            val failure = string("risk.failure-strategy", "FAIL_CLOSED")
            require(failure in listOf("FAIL_OPEN", "FAIL_CLOSED"))
            fun bounded(path: String, default: Long, limit: Long): Int = number(path, default).also {
                require(it in 1..limit) { "$path is out of range" }
            }.toInt()
            return VelocityRiskConfiguration(
                bool("storage.enabled", false), string("storage.jdbc-url", "jdbc:mysql://127.0.0.1:3306/authgatewayx"),
                string("storage.username", "authgatewayx_reader"), System.getenv("AUTHGATEWAYX_DB_PASSWORD") ?: string("storage.password", ""),
                RiskAction.valueOf(string("multi-account.action", "ALERT")), bool("ip-intelligence.enabled", false),
                System.getenv("AUTHGATEWAYX_PROXYCHECK_API_KEY") ?: string("ip-intelligence.api-key", ""),
                RiskAction.valueOf(string("ip-intelligence.action", "DENY")), failure == "FAIL_CLOSED", concurrent.toInt(),
                number("alerts.cooldown-seconds", 300), bool("alerts.console", true),
                System.getenv("AUTHGATEWAYX_STAFF_PROOF_SECRET") ?: string("staff-proof.secret", ""),
                number("ip-intelligence.timeout-millis", 2000), number("ip-intelligence.cache-ttl-seconds", 3600),
                number("ip-intelligence.failure-ttl-seconds", 60), bounded("ip-intelligence.maximum-cache-size",10000,100000),
                bounded("ip-intelligence.maximum-concurrent",2,32), bounded("ip-intelligence.requests-per-minute",30,1000),
                bool("ip-intelligence.show-geo", true), bool("alerts.enabled", true),
            )
        }
    }
}
