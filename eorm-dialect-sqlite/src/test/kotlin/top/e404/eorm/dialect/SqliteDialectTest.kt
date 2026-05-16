package top.e404.eorm.dialect

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SqliteDialectTest {
    private val dialect = SqliteDialect()

    @Test
    fun `upsert sql`() {
        assertEquals(
            """INSERT INTO "users" ("email", "name") VALUES (?, ?) ON CONFLICT ("email") DO UPDATE SET "name" = excluded."name"""",
            dialect.buildUpsertSql(
                """"users"""",
                listOf(""""email"""", """"name""""),
                listOf(""""email""""),
                listOf(""""name"""")
            )
        )
    }
}
