package pl.syntaxdevteam.authgatewayx.paper.dialog

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val ADMIN_REPORT_TIMESTAMP_FORMATTER = DateTimeFormatter
    .ofPattern("uuuu-MM-dd HH:mm:ss 'UTC'")
    .withZone(ZoneOffset.UTC)

internal fun Instant?.displayInAdminReport(): String =
    this?.let(ADMIN_REPORT_TIMESTAMP_FORMATTER::format) ?: "brak danych"
