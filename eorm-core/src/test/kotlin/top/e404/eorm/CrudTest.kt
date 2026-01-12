package top.e404.eorm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import top.e404.eorm.generator.IdStrategy

class CrudTest : BaseTest() {

    @Test
    fun `insert and select`() {
        val user = TestUser(name = "Alice", age = 20)
        db.insert(user)
        
        // ID should be auto-generated and populated back
        assertTrue(user.id > 0)
        
        val fetched = db.from<TestUser>("u").where { eq(TestUser::id, user.id) }.firstOrNull()
        assertNotNull(fetched)
        assertEquals("Alice", fetched.name)
        assertEquals(20, fetched.age)
    }

    @Test
    fun `batch insert`() {
        val users = listOf(
             TestUser(name = "Bob", age = 10),
             TestUser(name = "Charlie", age = 15),
             TestUser(name = "David", age = 20)
        )
        db.insert(users)

        val count = db.from<TestUser>("u").list().size
        assertEquals(3, count)
    }

    @Test
    fun `update`() {
        val user = TestUser(name = "Eve", age = 30)
        db.insert(user)

        user.age = 31
        user.name = "Eve Updated"
        val rows = db.update(user)
        assertEquals(1, rows)

        val fetched = db.from<TestUser>("u").where { eq(TestUser::id, user.id) }.firstOrNull()
        assertNotNull(fetched)
        assertEquals("Eve Updated", fetched.name)
        assertEquals(31, fetched.age)
    }

    @Test
    fun `delete entity`() {
        val user = TestUser(name = "Frank", age = 40)
        db.insert(user)

        val rows = db.delete(user)
        assertEquals(1, rows)

        val fetched = db.from<TestUser>("u").where { eq(TestUser::id, user.id) }.firstOrNull()
        assertNull(fetched)
    }

    @Test
    fun `delete by id`() {
        val user = TestUser(name = "George", age = 50)
        db.insert(user)

        val rows = db.deleteById<TestUser>(user.id)
        assertEquals(1, rows)

        val fetched = db.from<TestUser>("u").where { eq(TestUser::id, user.id) }.firstOrNull()
        assertNull(fetched)
    }

    private fun assertTrue(condition: Boolean) {
        if (!condition) throw AssertionError("Expected true but was false")
    }
}
