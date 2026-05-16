package top.e404.eorm

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import top.e404.eorm.annotations.Column
import top.e404.eorm.annotations.Id
import top.e404.eorm.annotations.Index
import top.e404.eorm.annotations.Indexes
import top.e404.eorm.annotations.Table
import top.e404.eorm.generator.IdStrategy
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdvancedMappingTest : BaseTest() {
    data class JsonProfile(
        var city: String = "",
        var score: Int = 0
    )

    @Table("advanced_user")
    @Indexes(
        Index(name = "uk_advanced_user_tenant_email", columns = ["tenantId", "email"], unique = true)
    )
    data class AdvancedUser(
        @Id(strategy = IdStrategy.AUTO)
        var id: Long = 0,
        var tenantId: Long = 0,
        @Column(length = 80, unique = true)
        var email: String = "",
        var name: String = "",
        @Column(json = true, sqlType = "VARCHAR(1000)")
        var profile: JsonProfile? = null
    )

    @Test
    fun `ddl includes unique indexes and json sql type`() {
        val ddl = db.generateDdl(AdvancedUser::class.java)

        assertTrue(ddl.contains("`profile` VARCHAR(1000)"))
        assertTrue(ddl.contains("CREATE UNIQUE INDEX IF NOT EXISTS `uk_advanced_user_email`"))
        assertTrue(ddl.contains("CREATE UNIQUE INDEX IF NOT EXISTS `uk_advanced_user_tenant_email`"))
    }

    @Test
    fun `create table applies unique index constraints`() {
        db.executor.execute("DROP TABLE IF EXISTS advanced_user")
        db.createTable<AdvancedUser>()

        db.insert(AdvancedUser(tenantId = 1, email = "a@example.com", name = "A"))
        assertThrows<SQLException> {
            db.insert(AdvancedUser(tenantId = 2, email = "a@example.com", name = "B"))
        }
    }

    @Test
    fun `json field stores entity object and reads it back`() {
        db.executor.execute("DROP TABLE IF EXISTS advanced_user")
        db.createTable<AdvancedUser>()

        val user = AdvancedUser(
            tenantId = 1,
            email = "json@example.com",
            name = "Json",
            profile = JsonProfile(city = "Hangzhou", score = 9)
        )
        db.insert(user)

        val fetched = db.from<AdvancedUser>("u")
            .where { eq(AdvancedUser::email, "json@example.com") }
            .firstOrNull()

        assertNotNull(fetched)
        assertEquals(JsonProfile("Hangzhou", 9), fetched.profile)
    }

    @Test
    fun `upsert inserts and updates by conflict column`() {
        db.executor.execute("DROP TABLE IF EXISTS advanced_user")
        db.createTable<AdvancedUser>()

        db.upsert(AdvancedUser(tenantId = 1, email = "upsert@example.com", name = "First", profile = JsonProfile("A", 1)))
            .on(AdvancedUser::email)
            .update(AdvancedUser::name, AdvancedUser::profile)
            .exec()

        db.upsert(AdvancedUser(tenantId = 1, email = "upsert@example.com", name = "Second", profile = JsonProfile("B", 2)))
            .on(AdvancedUser::email)
            .update(AdvancedUser::name, AdvancedUser::profile)
            .exec()

        val users = db.from<AdvancedUser>("u")
            .where { eq(AdvancedUser::email, "upsert@example.com") }
            .list()

        assertEquals(1, users.size)
        assertEquals("Second", users[0].name)
        assertEquals(JsonProfile("B", 2), users[0].profile)
    }
}
