package top.e404.eorm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DslTest : BaseTest() {

    @Test
    fun `nested condition`() {
        val users = listOf(
            TestUser(name = "Alice", age = 20),
            TestUser(name = "Bob", age = 15),
            TestUser(name = "Charlie", age = 30)
        )
        db.insert(users)

        // (age > 18) AND (name = 'Alice' OR name = 'Charlie')
        val list = db.from<TestUser>("u")
            .where {
                gt(TestUser::age, 18)
                and {
                    eq(TestUser::name, "Alice")
                    or {
                        eq(TestUser::name, "Charlie")
                    }
                }
            }
            .list()

        assertEquals(2, list.size)
        assertTrue(list.any { it.name == "Alice" })
        assertTrue(list.any { it.name == "Charlie" })
    }

    @Test
    fun `explicit nest and or`() {
        val users = listOf(
            TestUser(name = "A", age = 10),
            TestUser(name = "B", age = 20),
            TestUser(name = "C", age = 30)
        )
        db.insert(users)

        // (age < 15 OR age > 25) AND age >= 1
        val list = db.from<TestUser>("u")
            .where {
                nest {
                    lt(TestUser::age, 15)
                    or()
                    gt(TestUser::age, 25)
                }
                and()
                ge(TestUser::age, 1)
            }
            .list()

        assertEquals(2, list.size)
        assertTrue(list.any { it.name == "A" })
        assertTrue(list.any { it.name == "C" })
    }

   @Test
    fun `update dsl with consumer API`() {
        db.insert(TestUser(name = "Original", age = 10))

        // Update where name = 'Original'
        val rows = db.update<TestUser>()
            .set(TestUser::name, "Changed")
            .where { eq(TestUser::name, "Original") }
            .exec()

        assertEquals(1, rows)
        
        val user = db.from<TestUser>("u").firstOrNull()
        assertEquals("Changed", user?.name)
    }

    @Test
    fun `delete dsl with consumer API`() {
        db.insert(TestUser(name = "ToDelete", age = 99))

        val rows = db.delete<TestUser>()
            .where { eq(TestUser::name, "ToDelete") }
            .exec()

        assertEquals(1, rows)
        assertEquals(0, db.from<TestUser>("u").list().size)
    }
}
