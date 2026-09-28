package pl.syntaxdevteam.authgatewayx.paper.dialog

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminReportFormattingTest {
    @Test
    fun `timestamp is compact and drops unreadable fractional seconds`() {
        val rendered = Instant.parse("2026-09-22T18:10:10.543801915Z").displayInAdminReport()

        assertEquals("2026-09-22 18:10:10 UTC", rendered)
        assertTrue(rendered.length < Instant.parse("2026-09-22T18:10:10.543801915Z").toString().length)
    }

    @Test
    fun `missing timestamp has an explicit fallback`() {
        assertEquals("brak danych", (null as Instant?).displayInAdminReport())
    }

    @Test
    fun `dialog body stays narrow enough for ordinary gui scales`() {
        assertEquals(480, ADMIN_REPORT_BODY_WIDTH)
    }
}
