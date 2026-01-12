package top.e404.eorm

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import top.e404.eorm.annotations.Table
import top.e404.eorm.annotations.Id
import top.e404.eorm.annotations.Column
import top.e404.eorm.generator.IdStrategy

@Table("user_role")
data class UserRole(
    @Id(strategy = IdStrategy.AUTO)
    var id: Long = 0,
    var userId: Long = 0,
    var roleId: Long = 0
)

@Table("roles")
data class Role(
    @Id(strategy = IdStrategy.AUTO)
    var id: Long = 0,
    @Column(length = 50)
    var name: String? = null
)

class JoinTest : BaseTest() {

    override fun setup() {
        super.setup()
        db.createTable<UserRole>()
        db.createTable<Role>()
        
        // Prepare data
        val u1 = TestUser(name = "Alice", age = 20); db.insert(u1)
        val u2 = TestUser(name = "Bob", age = 25); db.insert(u2)
        
        val r1 = Role(name = "Admin"); db.insert(r1)
        val r2 = Role(name = "User"); db.insert(r2)
        
        db.insert(UserRole(userId = u1.id, roleId = r1.id))
        db.insert(UserRole(userId = u2.id, roleId = r2.id))
    }

    @Test
    fun `inner join`() {
        // Find users with role 'Admin' logic:
        // SELECT u.* FROM sys_user u 
        // INNER JOIN user_role ur ON ur.user_id = u.id 
        // INNER JOIN roles r ON r.id = ur.role_id 
        // WHERE r.name = 'Admin'
        
        val users = db.from(TestUser::class.java, "u")
            .select(TestUser::name)
            .innerJoin<UserRole>("ur") {
                eqCol(UserRole::userId, TestUser::id)
            }
            .innerJoin<Role>("r") {
                eqCol(Role::id, UserRole::roleId)
            }
            .where {
                eq(Role::name, "Admin")
            }
            .list()
            
        assertEquals(1, users.size)
        assertEquals("Alice", users[0].name)
    }

    @Test
    fun `left join with params`() {
        // Find users and their roles, filtering users older than 22
        // SELECT u.* FROM sys_user u LEFT JOIN user_role ur ON ur.user_id = u.id AND ur.id > 0 ...
        // Testing params inside ON clause
        
        val users = db.from(TestUser::class.java, "u")
            .leftJoin<UserRole>("ur") {
                eqCol(UserRole::userId, TestUser::id)
                gt(UserRole::id, 0) // Param: 0
            }
            .where {
                gt(TestUser::age, 22) // Param: 22
            }
            .list()
            
        assertEquals(1, users.size)
        assertEquals("Bob", users[0].name)
    }
    
    @Test
    fun `select columns from joined tables`() {
        // Select distinct columns to avoid map key collision
        val result = db.from(TestUser::class.java, "u")
            .select(TestUser::name)
            .select(TestUser::age)
            .select(Role::name)
            .innerJoin<UserRole>("ur") { eqCol(UserRole::userId, TestUser::id) }
            .innerJoin<Role>("r") { eqCol(Role::id, UserRole::roleId) }
            .where { eq(Role::name, "Admin") }
            .listMaps()
            
        assertEquals(1, result.size)
        // Since we have two 'name' columns, the map will only contain one 'name' key (usually the last one).
        // This is standard behavior for simple Map<String, Any>.
        // To support duplicate column names, we would need to support aliases in select() or return a different structure.
        // For this test, we verify that we got the result row.
        // Normalize keys to lowercase to handle H2 returning uppercase labels
        val row = result[0].mapKeys { it.key.lowercase() }
        assertTrue(row.containsKey("name"))
        assertTrue(row.containsKey("age"))
        assertEquals(20, row["age"]) // Alice is 20
    }
}
