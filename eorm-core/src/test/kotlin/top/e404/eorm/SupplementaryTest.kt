package top.e404.eorm

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import top.e404.eorm.annotations.Column
import top.e404.eorm.annotations.Id
import top.e404.eorm.annotations.Table
import top.e404.eorm.exception.ValidationException
import top.e404.eorm.executor.SqlExecutor
import top.e404.eorm.filler.DataFiller
import top.e404.eorm.generator.EOrmIdGenerator
import top.e404.eorm.generator.IdStrategy
import top.e404.eorm.mapping.NameConverter
import top.e404.eorm.transaction.TransactionManager
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 补充测试：覆盖边界条件、异常路径、遗漏的功能点。
 */
class SupplementaryTest : BaseTest() {

    // ==================== 空列表 / 边界条件 ====================

    @Test
    fun `insert empty list does nothing`() {
        db.insert(emptyList<TestUser>())
        assertEquals(0, db.from<TestUser>("u").list().size)
    }

    // ==================== 无 @Id 实体异常 ====================

    data class NoIdEntity(var name: String = "", var value: Int = 0)

    @Test
    fun `update entity without Id throws exception`() {
        assertThrows<IllegalStateException> {
            db.update(NoIdEntity("test", 1))
        }
    }

    @Test
    fun `delete entity without Id throws exception`() {
        assertThrows<IllegalStateException> {
            db.delete(NoIdEntity("test", 1))
        }
    }

    // ==================== EntityValidator ====================

    @Test
    fun `insert with string exceeding column length throws ValidationException`() {
        // TestUser.name 有 @Column(length = 50)
        val longName = "A".repeat(51)
        assertThrows<ValidationException> {
            db.insert(TestUser(name = longName, age = 20))
        }
    }

    @Test
    fun `insert with string at exact column length succeeds`() {
        val exactName = "A".repeat(50)
        db.insert(TestUser(name = exactName, age = 20))
        val fetched = db.from<TestUser>("u").firstOrNull()
        assertEquals(exactName, fetched?.name)
    }

    // ==================== Snowflake / UUID / MANUAL 主键策略 ====================

    @Table("snowflake_entity")
    data class SnowflakeEntity(
        @Id(strategy = IdStrategy.SNOWFLAKE)
        var id: Long = 0,
        @Column(length = 50)
        var name: String? = null
    )

    @Table("uuid_entity")
    data class UuidEntity(
        @Id(strategy = IdStrategy.UUID)
        @Column(length = 64)
        var id: String = "",
        @Column(length = 50)
        var name: String? = null
    )

    @Table("manual_entity")
    data class ManualEntity(
        @Id(strategy = IdStrategy.MANUAL)
        var id: Long = 0,
        @Column(length = 50)
        var name: String? = null
    )

    @Test
    fun `snowflake id strategy generates id on insert`() {
        db.executor.execute("DROP TABLE IF EXISTS snowflake_entity")
        db.createTable<SnowflakeEntity>()

        val entity = SnowflakeEntity(name = "Snow")
        assertEquals(0L, entity.id)
        db.insert(entity)
        assertNotEquals(0L, entity.id)
        assertTrue(entity.id > 0)
    }

    @Test
    fun `uuid id strategy generates id on insert`() {
        db.executor.execute("DROP TABLE IF EXISTS uuid_entity")
        db.createTable<UuidEntity>()

        val entity = UuidEntity(name = "Uuid")
        assertEquals("", entity.id)
        db.insert(entity)
        assertTrue(entity.id.isNotEmpty())
        assertEquals(32, entity.id.length) // UUID 去横线后 32 位
    }

    @Test
    fun `manual id strategy uses provided id`() {
        db.executor.execute("DROP TABLE IF EXISTS manual_entity")
        db.createTable<ManualEntity>()

        val entity = ManualEntity(id = 42, name = "Manual")
        db.insert(entity)
        assertEquals(42L, entity.id)

        val fetched = db.from<ManualEntity>("m")
            .where { eq(ManualEntity::id, 42L) }
            .firstOrNull()
        assertNotNull(fetched)
        assertEquals("Manual", fetched.name)
    }

    @Test
    fun `custom id generator is used on insert`() {
        db.executor.execute("DROP TABLE IF EXISTS snowflake_entity")
        db.createTable<SnowflakeEntity>()

        val generator = object : EOrmIdGenerator {
            override fun nextSnowflakeId(): Long = 123456L
            override fun nextUuid(): String = "fixeduuid"
        }
        val dbWithGenerator = EOrm(dataSource, db.dialect, idGenerator = generator)

        val entity = SnowflakeEntity(name = "Custom")
        dbWithGenerator.insert(entity)

        assertEquals(123456L, entity.id)
    }

    @Test
    fun `custom transaction manager and executor can be injected`() {
        val transactionManager = TransactionManager(dataSource)
        val executor = SqlExecutor(dataSource, db.logger, db.useLiterals, transactionManager, db.dialect)
        val injectedDb = EOrm(
            dataSource = dataSource,
            dialect = db.dialect,
            transactionManager = transactionManager,
            executor = executor
        )

        assertSame(transactionManager, injectedDb.transactionManager)
        assertSame(executor, injectedDb.executor)
    }

    // ==================== DataFiller ====================

    @Test
    fun `custom data filler is called on insert`() {
        val filler = object : DataFiller {
            override fun insertFill(entity: Any, converter: NameConverter) {
                setFieldValByName(entity, "createdAt", LocalDateTime.of(2024, 1, 1, 0, 0), converter)
            }
        }
        val dbWithFiller = EOrm(dataSource, db.dialect, dataFiller = filler)

        val user = TestUser(name = "Filled", age = 20)
        dbWithFiller.insert(user)

        val fetched = dbWithFiller.from<TestUser>("u")
            .where { eq(TestUser::name, "Filled") }
            .firstOrNull()
        assertNotNull(fetched)
        assertEquals(LocalDateTime.of(2024, 1, 1, 0, 0), fetched.createdAt)
    }

    // ==================== generateDdl ====================

    @Test
    fun `generateDdl produces correct DDL`() {
        val ddl = db.generateDdl(TestUser::class.java)
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS"))
        assertTrue(ddl.contains("`sys_user`"))
        assertTrue(ddl.contains("`id`"))
        assertTrue(ddl.contains("PRIMARY KEY AUTO_INCREMENT"))
        assertTrue(ddl.contains("`name`"))
        assertTrue(ddl.contains("VARCHAR(50)"))
        assertTrue(ddl.contains("`age`"))
    }

    // ==================== limit ====================

    @Test
    fun `query with limit`() {
        db.insert(listOf(
            TestUser(name = "A", age = 1),
            TestUser(name = "B", age = 2),
            TestUser(name = "C", age = 3)
        ))

        val limited = db.from<TestUser>("u").limit(2).list()
        assertEquals(2, limited.size)
    }

    // ==================== listMaps 基础测试 ====================

    @Test
    fun `listMaps returns map with column labels`() {
        db.insert(TestUser(name = "MapTest", age = 42))

        val maps = db.from<TestUser>("u")
            .select(TestUser::name, TestUser::age)
            .listMaps()

        assertEquals(1, maps.size)
        val row = maps[0].mapKeys { it.key.lowercase() }
        assertEquals("MapTest", row["name"])
        assertEquals(42, row["age"])
    }

    // ==================== 手动事务 API ====================

    @Test
    fun `manual transaction commit`() {
        db.beginTransaction()
        db.insert(TestUser(name = "Manual", age = 10))
        db.commitTransaction()

        assertEquals(1, db.from<TestUser>("u").list().size)
    }

    @Test
    fun `manual transaction rollback`() {
        db.beginTransaction()
        db.insert(TestUser(name = "Manual", age = 10))
        db.rollbackTransaction()

        assertEquals(0, db.from<TestUser>("u").list().size)
    }

    @Test
    fun `commit without active transaction throws exception`() {
        assertThrows<IllegalStateException> {
            db.commitTransaction()
        }
    }

    @Test
    fun `rollback without active transaction is silent`() {
        // 不应抛异常
        db.rollbackTransaction()
    }
}
