package top.e404.eorm

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import top.e404.eorm.annotations.Table
import top.e404.eorm.annotations.Id
import top.e404.eorm.annotations.Column
import top.e404.eorm.generator.IdStrategy

class SubQueryTest : BaseTest() {

    @Table("user_role")
    data class SubTestUserRole(
        @Id(strategy = IdStrategy.AUTO)
        var id: Long = 0,
        var userId: Long = 0,
        var roleId: Long = 0
    )

    @Table("roles")
    data class SubTestRole(
        @Id(strategy = IdStrategy.AUTO)
        var id: Long = 0,
        @Column(length = 50)
        var name: String? = null
    )

    override fun setup() {
        super.setup()
        db.createTable<SubTestUserRole>()
        db.createTable<SubTestRole>()

        // 准备数据
        val alice = TestUser(name = "Alice", age = 20); db.insert(alice)
        val bob = TestUser(name = "Bob", age = 25); db.insert(bob)
        val charlie = TestUser(name = "Charlie", age = 30); db.insert(charlie)
        val dave = TestUser(name = "Dave", age = 35); db.insert(dave)

        val adminRole = SubTestRole(name = "Admin"); db.insert(adminRole)
        val userRole = SubTestRole(name = "User"); db.insert(userRole)

        // Alice -> Admin, Bob -> User, Charlie -> Admin + User, Dave -> 无角色
        db.insert(SubTestUserRole(userId = alice.id, roleId = adminRole.id))
        db.insert(SubTestUserRole(userId = bob.id, roleId = userRole.id))
        db.insert(SubTestUserRole(userId = charlie.id, roleId = adminRole.id))
        db.insert(SubTestUserRole(userId = charlie.id, roleId = userRole.id))
    }

    @Test
    fun `IN subquery - find users with Admin role`() {
        // SELECT * FROM sys_user u WHERE u.id IN (
        //   SELECT ur.user_id FROM user_role ur
        //   INNER JOIN roles r ON r.id = ur.role_id
        //   WHERE r.name = 'Admin'
        // )
        val adminUserIds = db.subQuery<SubTestUserRole>("ur")
            .select(SubTestUserRole::userId)
            .innerJoin<SubTestRole>("r") { eqCol(SubTestRole::id, SubTestUserRole::roleId) }
            .where { eq(SubTestRole::name, "Admin") }

        val admins = db.from<TestUser>("u")
            .where { inSub(TestUser::id, adminUserIds) }
            .list()

        assertEquals(2, admins.size)
        assertTrue(admins.any { it.name == "Alice" })
        assertTrue(admins.any { it.name == "Charlie" })
    }

    @Test
    fun `NOT IN subquery - find users without any role`() {
        // SELECT * FROM sys_user u WHERE u.id NOT IN (
        //   SELECT ur.user_id FROM user_role ur
        // )
        val usersWithRoles = db.subQuery<SubTestUserRole>("ur")
            .select(SubTestUserRole::userId)

        val noRoleUsers = db.from<TestUser>("u")
            .where { notInSub(TestUser::id, usersWithRoles) }
            .list()

        assertEquals(1, noRoleUsers.size)
        assertEquals("Dave", noRoleUsers[0].name)
    }

    @Test
    fun `EXISTS subquery - find users that have at least one role`() {
        // SELECT * FROM sys_user u WHERE EXISTS (
        //   SELECT 1 FROM user_role ur WHERE ur.user_id = u.id
        // )
        val usersWithRoles = db.from<TestUser>("u")
            .where {
                exists(db.subQuery<SubTestUserRole>("ur")) { sub ->
                    sub.select("1")
                        .where { eqCol(SubTestUserRole::userId, TestUser::id) }
                }
            }
            .list()

        assertEquals(3, usersWithRoles.size)
        assertTrue(usersWithRoles.none { it.name == "Dave" })
    }

    @Test
    fun `NOT EXISTS subquery - find users without any role`() {
        val noRoleUsers = db.from<TestUser>("u")
            .where {
                notExists(db.subQuery<SubTestUserRole>("ur")) { sub ->
                    sub.select("1")
                        .where { eqCol(SubTestUserRole::userId, TestUser::id) }
                }
            }
            .list()

        assertEquals(1, noRoleUsers.size)
        assertEquals("Dave", noRoleUsers[0].name)
    }

    @Test
    fun `scalar subquery comparison - gtSub`() {
        // SELECT * FROM sys_user u WHERE u.age > (SELECT AVG(u2.age) FROM sys_user u2)
        // AVG(20, 25, 30, 35) = 27.5
        val avgAge = db.subQuery<TestUser>("u2")
            .select("AVG(u2.`age`)")

        val aboveAvg = db.from<TestUser>("u")
            .where { gtSub(TestUser::age, avgAge) }
            .list()

        assertEquals(2, aboveAvg.size)
        assertTrue(aboveAvg.any { it.name == "Charlie" })
        assertTrue(aboveAvg.any { it.name == "Dave" })
    }

    @Test
    fun `scalar subquery comparison - ltSub`() {
        // SELECT * FROM sys_user u WHERE u.age < (SELECT AVG(u2.age) FROM sys_user u2)
        val avgAge = db.subQuery<TestUser>("u2")
            .select("AVG(u2.`age`)")

        val belowAvg = db.from<TestUser>("u")
            .where { ltSub(TestUser::age, avgAge) }
            .list()

        assertEquals(2, belowAvg.size)
        assertTrue(belowAvg.any { it.name == "Alice" })
        assertTrue(belowAvg.any { it.name == "Bob" })
    }

    @Test
    fun `combined conditions with subquery`() {
        // SELECT * FROM sys_user u WHERE u.age > 22 AND u.id IN (
        //   SELECT ur.user_id FROM user_role ur
        //   INNER JOIN roles r ON r.id = ur.role_id
        //   WHERE r.name = 'Admin'
        // )
        val adminUserIds = db.subQuery<SubTestUserRole>("ur")
            .select(SubTestUserRole::userId)
            .innerJoin<SubTestRole>("r") { eqCol(SubTestRole::id, SubTestUserRole::roleId) }
            .where { eq(SubTestRole::name, "Admin") }

        val result = db.from<TestUser>("u")
            .where {
                gt(TestUser::age, 22)
                inSub(TestUser::id, adminUserIds)
            }
            .list()

        assertEquals(1, result.size)
        assertEquals("Charlie", result[0].name)
    }

    @Test
    fun `nested subquery - subquery within subquery`() {
        // SELECT * FROM sys_user u WHERE u.id IN (
        //   SELECT ur.user_id FROM user_role ur WHERE ur.role_id IN (
        //     SELECT r.id FROM roles r WHERE r.name = 'Admin'
        //   )
        // )
        val adminRoleIds = db.subQuery<SubTestRole>("r")
            .select(SubTestRole::id)
            .where { eq(SubTestRole::name, "Admin") }

        val adminUserIds = db.subQuery<SubTestUserRole>("ur")
            .select(SubTestUserRole::userId)
            .where { inSub(SubTestUserRole::roleId, adminRoleIds) }

        val admins = db.from<TestUser>("u")
            .where { inSub(TestUser::id, adminUserIds) }
            .list()

        assertEquals(2, admins.size)
        assertTrue(admins.any { it.name == "Alice" })
        assertTrue(admins.any { it.name == "Charlie" })
    }
}
