package top.e404.eorm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import top.e404.eorm.dialect.*
import java.time.LocalDateTime
import java.time.LocalDate

class DialectTest {

    @Test
    fun `Postgres dialect test`() {
        val dialect = PostgreSqlDialect()
        
        assertEquals("\"myTable\"", dialect.wrapName("myTable"))
        assertEquals("TIMESTAMP", dialect.getSqlType(LocalDateTime::class.java, 0))
        assertEquals("BOOLEAN", dialect.getSqlType(Boolean::class.java, 0))
        assertEquals("DATE", dialect.getSqlType(LocalDate::class.java, 0)) 
    }

    @Test
    fun `Sqlite dialect test`() {
        val dialect = SqliteDialect()
        
        assertEquals("\"myTable\"", dialect.wrapName("myTable"))
        assertEquals("TEXT", dialect.getSqlType(LocalDateTime::class.java, 0))
        assertEquals("INTEGER", dialect.getSqlType(Boolean::class.java, 0)) // We mapped boolean to Integer for safety
        assertEquals("TEXT", dialect.getSqlType(LocalDate::class.java, 0))
    }

    @Test
    fun `Mysql dialect test`() {
        val dialect = MySqlDialect()
        
        assertEquals("`myTable`", dialect.wrapName("myTable"))
        assertEquals("DATETIME", dialect.getSqlType(LocalDateTime::class.java, 0))
    }
}
