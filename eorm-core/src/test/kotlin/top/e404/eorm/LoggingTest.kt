package top.e404.eorm

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import top.e404.eorm.annotations.Id
import top.e404.eorm.annotations.Table
import top.e404.eorm.generator.IdStrategy
import top.e404.eorm.log.EOrmLogger
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class CapturingLogger : EOrmLogger {
    val sql = ArrayList<Pair<String, List<Any?>>>()
    val debugs = ArrayList<String>()
    val infos = ArrayList<String>()
    val warns = ArrayList<Pair<String, Throwable?>>()
    val errors = ArrayList<Pair<String, Throwable?>>()

    override fun logSql(sql: String, args: List<Any?>) {
        this.sql.add(sql to args)
    }

    override fun debug(message: String) {
        debugs.add(message)
    }

    override fun info(message: String) {
        infos.add(message)
    }

    override fun warn(message: String, e: Throwable?) {
        warns.add(message to e)
    }

    override fun error(message: String, e: Throwable?) {
        errors.add(message to e)
    }
}

@Table("logging_upsert_user")
data class LoggingUpsertUser(
    @Id(strategy = IdStrategy.MANUAL)
    var id: Long = 0,
    var name: String? = null,
    var age: Int = 0
)

class LoggingTest : BaseTest() {
    @TempDir
    lateinit var migrationDir: Path

    private lateinit var logger: CapturingLogger
    private lateinit var loggedDb: EOrm

    @BeforeEach
    override fun setup() {
        super.setup()
        logger = CapturingLogger()
        loggedDb = EOrm(dataSource, db.dialect, logger = logger)
        loggedDb.executor.execute("DROP TABLE IF EXISTS eorm_schema_history")
        loggedDb.executor.execute("DROP TABLE IF EXISTS logging_migration")
        loggedDb.executor.execute("DROP TABLE IF EXISTS logging_upsert_user")
    }

    @Test
    fun `transaction logs commit rollback and manual lifecycle`() {
        loggedDb.transaction {
            insert(TestUser(name = "Log Commit", age = 18))
        }

        assertTrue(logger.debugs.any { it == "Transaction begin propagation=REQUIRED" })
        assertTrue(logger.debugs.any { it == "Transaction commit propagation=REQUIRED" })

        assertThrows<RuntimeException> {
            loggedDb.transaction {
                insert(TestUser(name = "Log Rollback", age = 19))
                throw RuntimeException("rollback")
            }
        }

        assertTrue(logger.errors.any { it.first.contains("Transaction rollback due to exception") && it.second is RuntimeException })

        assertThrows<IllegalStateException> {
            loggedDb.transaction {
                transaction(top.e404.eorm.transaction.TransactionPropagation.NEVER) {}
            }
        }
        assertTrue(logger.warns.any { it.first.contains("Transaction rejected propagation=NEVER") })

        loggedDb.beginTransaction()
        loggedDb.rollbackTransaction()

        assertTrue(logger.debugs.any { it == "Manual transaction begin" })
        assertTrue(logger.debugs.any { it == "Manual transaction rollback" })
    }

    @Test
    fun `migration logs apply skip checksum mismatch and completion`() {
        val script = migrationDir.resolve("V1__create_logging_migration.sql")
        script.writeText(
            """
            CREATE TABLE logging_migration (
                id BIGINT PRIMARY KEY,
                name VARCHAR(50)
            );
            """.trimIndent()
        )

        val first = loggedDb.migrator().locations(migrationDir.toString()).migrate()
        assertEquals(listOf("1"), first.applied.map { it.version })
        assertTrue(logger.infos.any { it == "Migration start scripts=1" })
        assertTrue(logger.infos.any { it.contains("Migration apply version=1") })
        assertTrue(logger.infos.any { it.contains("Migration applied version=1") })
        assertTrue(logger.infos.any { it == "Migration complete applied=1, skipped=0" })

        val second = loggedDb.migrator().locations(migrationDir.toString()).migrate()
        assertEquals(listOf("1"), second.skipped.map { it.version })
        assertTrue(logger.debugs.any { it.contains("Migration skip version=1") })
        assertTrue(logger.infos.any { it == "Migration complete applied=0, skipped=1" })

        script.writeText("CREATE TABLE logging_migration (id BIGINT PRIMARY KEY, name VARCHAR(100));")

        assertThrows<IllegalStateException> {
            loggedDb.migrator().locations(migrationDir.toString()).migrate()
        }

        assertTrue(logger.errors.any { it.first.contains("Migration checksum mismatch version=1") })
        assertTrue(logger.errors.any { it.first.contains("Migration failed version=1") })
    }

    @Test
    fun `batch insert and upsert log execution stats`() {
        logger.sql.clear()
        loggedDb.insert(
            listOf(
                TestUser(name = "Batch A", age = 20),
                TestUser(name = "Batch B", age = 21)
            )
        )

        assertTrue(logger.debugs.any { it == "Batch insert size: 2" })
        assertTrue(logger.debugs.any { it.contains("Batch insert affected rows: 2, batch size: 2") })
        val insertLogs = logger.sql.filter { it.first.startsWith("INSERT INTO") }
        assertEquals(2, insertLogs.size)
        assertTrue(insertLogs.any { it.second.contains("Batch A") })
        assertTrue(insertLogs.any { it.second.contains("Batch B") })

        logger.debugs.clear()
        loggedDb.createTable<LoggingUpsertUser>()
        loggedDb.insert(
            listOf(
                LoggingUpsertUser(id = 1, name = "Upsert A", age = 20),
                LoggingUpsertUser(id = 2, name = "Upsert B", age = 21)
            )
        )
        val users = loggedDb.from<LoggingUpsertUser>("u").list()
        users[0].age = 30
        users[1].age = 31

        logger.sql.clear()
        val affected = loggedDb.upsert(users).on(LoggingUpsertUser::id).update(LoggingUpsertUser::age).exec()

        assertEquals(2, affected)
        val upsertLogs = logger.sql.filter { it.first.startsWith("MERGE INTO") }
        assertEquals(2, upsertLogs.size)
        assertTrue(upsertLogs.any { it.second.contains("Upsert A") })
        assertTrue(upsertLogs.any { it.second.contains("Upsert B") })
        assertTrue(logger.debugs.any { it.contains("Upsert execute batch size: 2") })
        assertTrue(logger.debugs.any { it.contains("Batch update affected rows: 2, batch size: 2") })
        assertTrue(logger.debugs.any { it.contains("Upsert affected rows: 2, batch size: 2") })
    }
}
