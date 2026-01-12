package top.e404.eorm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.LocalTime
import java. time.format.DateTimeFormatter

class TypeMappingTest : BaseTest() {

    @Test
    fun `date time types`() {
        val now = LocalDateTime.now().withNano(0) // H2 default precision might vary
        val today = LocalDate.now()
        val nowTime = LocalTime.now().withNano(0)

        val user = TestUser(
            name = "TimeLord", 
            createdAt = now,
            updateDate = today,
            loginTime = nowTime
        )
        db.insert(user)

        val fetched = db.from<TestUser>("u").where { eq(TestUser::id, user.id) }.firstOrNull()
        assertNotNull(fetched)
        
        // Compare string representations to avoid equality issues with specific DB implementation details (nano precision etc)
        // Ideally we should compare objects but for broad compat, this is safer basics check
        assertEquals(now.toString(), fetched.createdAt.toString().replace(" ", "T"))
        assertEquals(today.toString(), fetched.updateDate.toString())
        assertEquals(nowTime.toString(), fetched.loginTime.toString())
    }
}
