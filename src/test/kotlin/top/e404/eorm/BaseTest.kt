package top.e404.eorm

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import top.e404.eorm.annotations.Column
import top.e404.eorm.annotations.Id
import top.e404.eorm.annotations.Table
import top.e404.eorm.dialect.DbType
import top.e404.eorm.generator.IdStrategy
import java.io.Closeable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Date

@Table("sys_user")
data class TestUser(
    @Id(strategy = IdStrategy.AUTO)
    var id: Long = 0,
    @Column(length = 50)
    var name: String? = null,
    var age: Int = 0,
    var active: Boolean = true,
    var score: Double = 0.0,
    var birthDate: Date? = null,
    var createdAt: LocalDateTime? = null,
    var updateDate: LocalDate? = null,
    var loginTime: LocalTime? = null
)

abstract class BaseTest {
    protected lateinit var dataSource: HikariDataSource
    protected lateinit var db: EOrm

    @BeforeEach
    open fun setup() {
        val config = HikariConfig()
        // 使用 MySQL 模式以获得最大的兼容性测试
        config.jdbcUrl = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=MySQL"
        config.username = "sa"
        config.password = ""
        config.driverClassName = "org.h2.Driver"
        dataSource = HikariDataSource(config)
        db = EOrm(dataSource, DbType.H2, useLiterals = false)

        try { db.executor.execute("DROP TABLE sys_user") } catch (e: Exception) {}
        db.createTable<TestUser>()
    }

    @AfterEach
    open fun tearDown() {
        if (::dataSource.isInitialized && !dataSource.isClosed) {
            dataSource.close()
        }
    }
}
