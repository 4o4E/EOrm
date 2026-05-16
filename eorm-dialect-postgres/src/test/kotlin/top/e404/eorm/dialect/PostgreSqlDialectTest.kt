package top.e404.eorm.dialect

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostgreSqlDialectTest {
    private val dialect = PostgreSqlDialect()

    @Test
    fun `json type and upsert sql`() {
        assertEquals("JSONB", dialect.getJsonSqlType())
        assertTrue(dialect.bindJsonAsOther())
        assertEquals(
            """INSERT INTO "users" ("email", "name") VALUES (?, ?) ON CONFLICT ("email") DO UPDATE SET "name" = EXCLUDED."name"""",
            dialect.buildUpsertSql(
                """"users"""",
                listOf(""""email"""", """"name""""),
                listOf(""""email""""),
                listOf(""""name"""")
            )
        )
    }
}
