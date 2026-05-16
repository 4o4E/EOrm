package top.e404.eorm.dialect

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MySqlDialectTest {
    private val dialect = MySqlDialect()

    @Test
    fun `json type and upsert sql`() {
        assertEquals("JSON", dialect.getJsonSqlType())
        assertEquals(
            "INSERT INTO `users` (`email`, `name`) VALUES (?, ?) ON DUPLICATE KEY UPDATE `name` = VALUES(`name`)",
            dialect.buildUpsertSql(
                "`users`",
                listOf("`email`", "`name`"),
                listOf("`email`"),
                listOf("`name`")
            )
        )
    }
}
