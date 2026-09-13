package pl.syntaxdevteam.authgatewayx.velocity

import java.sql.SQLException

/** Reports actionable categories without printing credentials or driver-provided connection URLs. */
internal object StorageStartupDiagnostic {
    fun describe(failure: Throwable): String {
        val causes = ArrayList<Throwable>()
        var current: Throwable? = failure
        while (current != null && causes.size < 16 && causes.none { it === current }) {
            causes.add(current)
            current = current.cause
        }
        val sql = causes.filterIsInstance<SQLException>().lastOrNull()
        val state = sql?.sqlState?.takeIf { it.matches(Regex("[A-Za-z0-9]{5}")) }
        val driverUnavailable = causes.any { cause ->
            cause is ClassNotFoundException ||
                cause is NoClassDefFoundError ||
                cause.message?.contains("No suitable driver", ignoreCase = true) == true ||
                cause.message?.contains("Failed to load driver class", ignoreCase = true) == true
        }
        val hint = when {
            driverUnavailable ->
                "JDBC driver is unavailable for the configured scheme: verify the distributable JAR and use a scheme matching the database (for example jdbc:mysql: or jdbc:mariadb:)."
            state == "42P01" || state == "42S02" || sql?.errorCode == 1146 ->
                "History schema is missing: start updated Paper against the same database to run migration v5 first."
            state?.startsWith("28") == true || sql?.errorCode == 1045 ->
                "Database authentication failed: check storage username/password and database grants."
            state == "42501" || sql?.errorCode == 1142 ->
                "Database access denied: the proxy needs SELECT on accounts and offline_account_addresses."
            state?.startsWith("08") == true || causes.any { it is java.net.ConnectException || it is java.net.UnknownHostException } ->
                "Cannot reach the database: check host, port, DNS, database service and firewall."
            else -> "Check database connectivity, credentials, SELECT grants and the shared Paper v5 schema."
        }
        return "${causes.lastOrNull()?.javaClass?.simpleName ?: "UnknownFailure"}" +
            (sql?.let { " (SQLState=${state ?: "unknown"}, code=${it.errorCode})" } ?: "") + ": " + hint
    }
}
