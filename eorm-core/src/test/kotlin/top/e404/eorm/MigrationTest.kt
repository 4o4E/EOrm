package top.e404.eorm

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import top.e404.eorm.migration.SqlScriptParser
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationTest : BaseTest() {
    @TempDir
    lateinit var migrationDir: Path

    @BeforeEach
    override fun setup() {
        super.setup()
        db.executor.execute("DROP TABLE IF EXISTS eorm_schema_history")
        db.executor.execute("DROP TABLE IF EXISTS migration_user")
        db.executor.execute("DROP TABLE IF EXISTS checksum_table")
    }

    @Test
    fun `migrator applies versioned sql scripts in order and skips applied scripts`() {
        migrationDir.resolve("V2__insert_more_users.sql").writeText(
            """
            INSERT INTO migration_user(id, name) VALUES (2, 'Bob');
            """.trimIndent()
        )
        migrationDir.resolve("V1__create_user.sql").writeText(
            """
            CREATE TABLE migration_user (
                id BIGINT PRIMARY KEY,
                name VARCHAR(50)
            );
            INSERT INTO migration_user(id, name) VALUES (1, 'Alice');
            """.trimIndent()
        )

        val result = db.migrator().locations(migrationDir.toString()).migrate()

        assertEquals(listOf("1", "2"), result.applied.map { it.version })
        assertEquals(2, db.executor.queryMap("SELECT * FROM migration_user", emptyList()).size)

        val second = db.migrator().locations(migrationDir.toString()).migrate()
        assertEquals(0, second.applied.size)
        assertEquals(listOf("1", "2"), second.skipped.map { it.version })
        assertEquals(2, db.executor.queryMap("SELECT * FROM eorm_schema_history", emptyList()).size)
    }

    @Test
    fun `migrator rejects changed applied script by checksum`() {
        val script = migrationDir.resolve("V1__create_checksum_table.sql")
        script.writeText("CREATE TABLE checksum_table(id BIGINT);")

        db.migrator().locations(migrationDir.toString()).migrate()
        script.writeText("CREATE TABLE checksum_table(id BIGINT, name VARCHAR(20));")

        assertThrows<IllegalStateException> {
            db.migrator().locations(migrationDir.toString()).migrate()
        }
    }

    @Test
    fun `failed migration is not recorded as applied`() {
        migrationDir.resolve("V1__broken.sql").writeText("INSERT INTO missing_table(id) VALUES (1);")

        assertThrows<Exception> {
            db.migrator().locations(migrationDir.toString()).migrate()
        }

        val historyRows = db.executor.queryMap("SELECT * FROM eorm_schema_history", emptyList())
        assertEquals(0, historyRows.size)
    }

    @Test
    fun `sql script parser keeps semicolons inside strings and comments`() {
        val statements = SqlScriptParser.splitStatements(
            """
            CREATE TABLE parser_test(id BIGINT, text VARCHAR(100));
            INSERT INTO parser_test(id, text) VALUES (1, 'a;b');
            -- comment ; should not split
            INSERT INTO parser_test(id, text) VALUES (2, 'c');
            /* block ; comment */
            INSERT INTO parser_test(id, text) VALUES (3, "d;e");
            """.trimIndent()
        )

        assertEquals(4, statements.size)
        assertTrue(statements[1].contains("'a;b'"))
        assertTrue(statements[3].contains("\"d;e\""))
    }

    @Test
    fun `migrator rejects invalid file names`() {
        migrationDir.resolve("001_create_bad.sql").writeText("SELECT 1;")

        assertThrows<IllegalArgumentException> {
            db.migrator().locations(migrationDir.toString()).migrate()
        }
    }
}
