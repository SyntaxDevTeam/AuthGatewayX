package pl.syntaxdevteam.authgatewayx.paper.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MigrationIgnorePolicyTest {
    @Test
    fun `replacement is immediately visible and names are deduplicated case-insensitively`() {
        val policy = MigrationIgnorePolicy(listOf(" CoreProtect "))

        assertEquals(setOf("CoreProtect"), policy.snapshot())
        assertEquals(
            setOf("BeautyQuests", "itemsadder"),
            policy.replace(listOf("BeautyQuests", "itemsadder", "ItemsAdder")),
        )
        assertEquals(setOf("BeautyQuests", "itemsadder"), policy.snapshot())
    }

    @Test
    fun `unsafe directory names are rejected without replacing current policy`() {
        val policy = MigrationIgnorePolicy(listOf("CoreProtect"))

        assertFailsWith<IllegalArgumentException> { policy.replace(listOf("../world")) }
        assertEquals(setOf("CoreProtect"), policy.snapshot())
    }
}
