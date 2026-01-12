package top.e404.eorm

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import top.e404.eorm.annotations.Table
import top.e404.eorm.annotations.Id
import top.e404.eorm.generator.IdStrategy

class PaginationTest : BaseTest() {

    override fun setup() {
        super.setup()
        db.createTable<TestUser>()
        
        // Insert 15 users
        for (i in 1..15) {
            db.insert(TestUser(name = "User_$i", age = i + 10))
        }
    }

    @Test
    fun `test pagination`() {
        // Page 1: 0-9 (10 records)
        val page1 = db.from(TestUser::class.java, "u")
            .where { gt(TestUser::age, 0) }
            .page(1, 10)
            
        assertEquals(1, page1.current)
        assertEquals(10, page1.size)
        assertEquals(15, page1.total)
        assertEquals(2, page1.pages)
        assertEquals(10, page1.records.size)
        assertEquals("User_1", page1.records[0].name)
        assertEquals("User_10", page1.records[9].name)
        
        // Page 2: 10-14 (5 records)
        val page2 = db.from(TestUser::class.java, "u")
            .where { gt(TestUser::age, 0) }
            .page(2, 10)
            
        assertEquals(2, page2.current)
        assertEquals(10, page2.size)
        assertEquals(15, page2.total)
        assertEquals(5, page2.records.size)
        assertEquals("User_11", page2.records[0].name)
        assertEquals("User_15", page2.records[4].name)
        
        // Page 3: (0 records)
        val page3 = db.from(TestUser::class.java, "u")
            .where { gt(TestUser::age, 0) }
            .page(3, 10)
            
        assertEquals(3, page3.current)
        assertEquals(15, page3.total)
        assertTrue(page3.records.isEmpty())
        
        // Page Map
        val pageNames = page2.map { it.name }
        assertEquals("User_11", pageNames.records[0])
    }

    @Test
    fun `test pagination without count`() {
        val page = db.from(TestUser::class.java, "u")
            .page(1, 10, searchCount = false)
            
        assertEquals(1, page.current)
        assertEquals(10, page.size)
        assertEquals(-1, page.total) // Total should be -1
        assertEquals(10, page.records.size)
        assertEquals("User_1", page.records[0].name)
    }
}
