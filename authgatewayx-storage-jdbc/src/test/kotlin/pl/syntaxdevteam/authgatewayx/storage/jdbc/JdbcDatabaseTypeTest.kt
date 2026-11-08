package pl.syntaxdevteam.authgatewayx.storage.jdbc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JdbcDatabaseTypeTest {
    @Test
    fun `database type is resolved only for supported jdbc schemes`() {
        assertEquals(JdbcDatabaseType.SQLITE, JdbcDatabaseType.fromJdbcUrl("jdbc:sqlite:test.db"))
        assertEquals(JdbcDatabaseType.MYSQL, JdbcDatabaseType.fromJdbcUrl("jdbc:mysql://localhost/db"))
        assertEquals(JdbcDatabaseType.MARIADB, JdbcDatabaseType.fromJdbcUrl("jdbc:mariadb://localhost/db"))
        assertEquals(JdbcDatabaseType.POSTGRESQL, JdbcDatabaseType.fromJdbcUrl("jdbc:postgresql://localhost/db"))
        assertFailsWith<IllegalArgumentException> { JdbcDatabaseType.fromJdbcUrl("jdbc:h2:mem:test") }
    }

    @Test
    fun `database types expose explicit jdbc driver classes`() {
        assertEquals("org.sqlite.JDBC", JdbcDatabaseType.SQLITE.driverClassName)
        assertEquals("com.mysql.cj.jdbc.Driver", JdbcDatabaseType.MYSQL.driverClassName)
        assertEquals("org.mariadb.jdbc.Driver", JdbcDatabaseType.MARIADB.driverClassName)
        assertEquals("org.postgresql.Driver", JdbcDatabaseType.POSTGRESQL.driverClassName)
    }
}
