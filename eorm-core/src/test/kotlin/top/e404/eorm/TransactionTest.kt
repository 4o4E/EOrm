package top.e404.eorm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import top.e404.eorm.transaction.TransactionPropagation
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransactionTest : BaseTest() {

    // ==================== 普通线程事务 ====================

    @Test
    fun `transaction commit`() {
        db.transaction {
            insert(TestUser(name = "Alice", age = 20))
            insert(TestUser(name = "Bob", age = 25))
        }

        val users = db.from<TestUser>("u").list()
        assertEquals(2, users.size)
        assertTrue(users.any { it.name == "Alice" })
        assertTrue(users.any { it.name == "Bob" })
    }

    @Test
    fun `transaction rollback on exception`() {
        assertThrows<RuntimeException> {
            db.transaction {
                insert(TestUser(name = "Alice", age = 20))
                insert(TestUser(name = "Bob", age = 25))
                throw RuntimeException("Simulated error")
            }
        }

        val users = db.from<TestUser>("u").list()
        assertEquals(0, users.size)
    }

    @Test
    fun `transaction with mixed operations`() {
        // 先在事务外插入一条
        db.insert(TestUser(name = "Existing", age = 10))

        db.transaction {
            insert(TestUser(name = "New", age = 20))
            update<TestUser>()
                .set(TestUser::age, 99)
                .where { eq(TestUser::name, "Existing") }
                .exec()
            delete<TestUser>()
                .where { eq(TestUser::name, "New") }
                .exec()
        }

        val users = db.from<TestUser>("u").list()
        assertEquals(1, users.size)
        assertEquals("Existing", users[0].name)
        assertEquals(99, users[0].age)
    }

    @Test
    fun `transaction with dsl query inside`() {
        db.insert(TestUser(name = "Alice", age = 20))

        val result = db.transaction {
            insert(TestUser(name = "Bob", age = 25))
            // 事务内查询应能看到未提交的数据
            from<TestUser>("u").list()
        }

        assertEquals(2, result.size)
    }

    @Test
    fun `nested transaction with required joins existing transaction`() {
        db.transaction {
            insert(TestUser(name = "Alice", age = 20))
            transaction {
                insert(TestUser(name = "Bob", age = 25))
            }
        }

        val users = db.from<TestUser>("u").list()
        assertEquals(2, users.size)
        assertTrue(users.any { it.name == "Alice" })
        assertTrue(users.any { it.name == "Bob" })
    }

    @Test
    fun `nested transaction with never throws exception`() {
        assertThrows<IllegalStateException> {
            db.transaction {
                transaction(TransactionPropagation.NEVER) {
                    insert(TestUser(name = "Never", age = 20))
                }
            }
        }
    }

    @Test
    fun `requires new transaction commits independently`() {
        assertThrows<RuntimeException> {
            db.transaction {
                insert(TestUser(name = "Outer", age = 20))
                transaction(TransactionPropagation.REQUIRES_NEW) {
                    insert(TestUser(name = "Inner", age = 25))
                }
                throw RuntimeException("Rollback outer")
            }
        }

        val users = db.from<TestUser>("u").list()
        assertEquals(1, users.size)
        assertEquals("Inner", users[0].name)
    }

    @Test
    fun `non-transactional operations unaffected`() {
        // 不使用事务时行为与之前完全一致
        db.insert(TestUser(name = "Alice", age = 20))
        db.insert(TestUser(name = "Bob", age = 25))

        val users = db.from<TestUser>("u").list()
        assertEquals(2, users.size)

        db.update<TestUser>()
            .set(TestUser::age, 30)
            .where { eq(TestUser::name, "Alice") }
            .exec()

        val alice = db.from<TestUser>("u")
            .where { eq(TestUser::name, "Alice") }
            .firstOrNull()
        assertEquals(30, alice?.age)
    }

    // ==================== 协程事务 ====================

    @Test
    fun `suspend transaction commit`() = runBlocking {
        db.suspendTransaction {
            insert(TestUser(name = "Alice", age = 20))
            insert(TestUser(name = "Bob", age = 25))
        }

        val users = db.from<TestUser>("u").list()
        assertEquals(2, users.size)
        assertTrue(users.any { it.name == "Alice" })
        assertTrue(users.any { it.name == "Bob" })
    }

    @Test
    fun `suspend transaction rollback on exception`() = runBlocking {
        assertThrows<RuntimeException> {
            runBlocking {
                db.suspendTransaction {
                    insert(TestUser(name = "Alice", age = 20))
                    insert(TestUser(name = "Bob", age = 25))
                    throw RuntimeException("Simulated error")
                }
            }
        }

        val users = db.from<TestUser>("u").list()
        assertEquals(0, users.size)
    }

    @Test
    fun `suspend transaction survives dispatcher switch`() = runBlocking {
        db.suspendTransaction {
            insert(TestUser(name = "Before", age = 10))

            // 切换到 IO 调度器（可能切换线程），事务连接应跟随
            withContext(Dispatchers.IO) {
                insert(TestUser(name = "InIO", age = 20))
            }

            // 切回默认调度器
            insert(TestUser(name = "After", age = 30))
        }

        val users = db.from<TestUser>("u").list()
        assertEquals(3, users.size)
        assertTrue(users.any { it.name == "Before" })
        assertTrue(users.any { it.name == "InIO" })
        assertTrue(users.any { it.name == "After" })
    }
}
