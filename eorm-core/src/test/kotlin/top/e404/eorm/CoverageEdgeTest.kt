package top.e404.eorm

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import top.e404.eorm.annotations.Column
import top.e404.eorm.annotations.Id
import top.e404.eorm.annotations.Table
import top.e404.eorm.dialect.BaseDialect
import top.e404.eorm.exception.ValidationException
import top.e404.eorm.generator.DefaultIdGenerator
import top.e404.eorm.generator.IdStrategy
import top.e404.eorm.log.DefaultSqlFormatter
import top.e404.eorm.mapping.CamelToSnakeConverter
import top.e404.eorm.meta.MetaCache
import top.e404.eorm.validation.EntityValidator
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverageEdgeTest {
    private val dialect = object : BaseDialect() {
        override fun wrapName(name: String): String = "\"$name\""
        override fun getSqlType(type: Class<*>, length: Int): String = "VARCHAR($length)"
        override fun getPrimaryKeyDefinition(strategy: IdStrategy): String = "PRIMARY KEY"
    }

    @Test
    fun `default sql formatter handles placeholders and literals`() {
        val formatter = DefaultSqlFormatter(dialect)

        assertEquals("SELECT 1", formatter.format("SELECT 1", emptyList()))
        assertEquals(
            "SELECT * FROM t WHERE name = 'O''Brien' AND age > 18 AND note IS ?",
            formatter.format(
                "SELECT * FROM t WHERE name = ? AND age > ? AND note IS ?",
                listOf("O'Brien", 18)
            )
        )
    }

    @Test
    fun `base dialect default helpers cover literal and sql builders`() {
        assertEquals("NULL", dialect.valueToSql(null))
        assertEquals("1", dialect.valueToSql(1))
        assertEquals("true", dialect.valueToSql(true))
        assertEquals("'O''Brien'", dialect.valueToSql("O'Brien"))
        assertTrue(dialect.valueToSql(Date(0)).startsWith("'"))
        assertEquals("'2024-01-02 03:04:05'", dialect.valueToSql(LocalDateTime.of(2024, 1, 2, 3, 4, 5)))
        assertEquals("'2024-01-02'", dialect.valueToSql(LocalDate.of(2024, 1, 2)))
        assertEquals("'03:04:05'", dialect.valueToSql(LocalTime.of(3, 4, 5)))
        assertEquals("'custom'", dialect.valueToSql(object {
            override fun toString(): String = "custom"
        }))

        assertEquals("SELECT * FROM t LIMIT 10 OFFSET 20", dialect.buildPaginationSql("SELECT * FROM t", 20, 10))
        assertEquals("SELECT * FROM t LIMIT 5", dialect.buildLimitSql("SELECT * FROM t", 5))
        assertEquals("INSERT INTO \"t\" (\"a\", \"b\") VALUES (?, ?)", dialect.buildInsertSql("\"t\"", listOf("\"a\"", "\"b\"")))
        assertTrue(dialect.supportsIfNotExists())
        assertEquals(100, dialect.getInsertBatchSize())
    }

    @Test
    fun `default id generator encodes configured node ids`() {
        val generator = DefaultIdGenerator(workerId = 7, datacenterId = 3)
        val id = generator.nextSnowflakeId()

        assertTrue(id > 0)
        assertEquals(7L, (id shr 12) and 31L)
        assertEquals(3L, (id shr 17) and 31L)
        assertEquals(32, generator.nextUuid().length)
    }

    @Table("validator_entity")
    data class ValidatorEntity(
        @Id(strategy = IdStrategy.AUTO)
        var id: Long? = null,
        @Column(nullable = false, length = 3)
        var name: String? = null
    )

    @Table("manual_validator_entity")
    data class ManualValidatorEntity(
        @Id(strategy = IdStrategy.MANUAL)
        @Column(nullable = false)
        var id: Long? = null
    )

    @Test
    fun `entity validator covers nullable id and string length branches`() {
        val meta = MetaCache.get(ValidatorEntity::class.java, CamelToSnakeConverter)

        EntityValidator.validate(ValidatorEntity(id = null, name = "abc"), meta)
        assertThrows<ValidationException> {
            EntityValidator.validate(ValidatorEntity(id = null, name = null), meta)
        }
        assertThrows<ValidationException> {
            EntityValidator.validate(ValidatorEntity(id = null, name = "abcd"), meta)
        }

        val manualMeta = MetaCache.get(ManualValidatorEntity::class.java, CamelToSnakeConverter)
        assertThrows<ValidationException> {
            EntityValidator.validate(ManualValidatorEntity(id = null), manualMeta)
        }
    }
}
