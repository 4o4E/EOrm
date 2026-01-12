package top.e404.eorm

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import top.e404.eorm.annotations.Table
import top.e404.eorm.annotations.Column
import top.e404.eorm.generator.IdStrategy

data class AliasDto(
    @Column(name = "name") // Maps DB column "name" to field "userName"
    var userName: String? = null,
    var age: Int = 0
)

class DtoProjectionTest : BaseTest() {

    override fun setup() {
        super.setup()
        db.createTable<TestUser>()
        db.insert(TestUser(name = "Alice", age = 20))
        db.insert(TestUser(name = "Bob", age = 25))
    }

    @Test
    fun `test simple dto projection`() {
        // Simple mapping: field name matches column name (implicit)
        val simpleDtoList = db.from(TestUser::class.java, "u")
            .listAs<SimpleUserDto>()

        assertEquals(2, simpleDtoList.size)
        assertEquals("Alice", simpleDtoList[0].name)
    }

    @Test
    fun `test alias dto projection`() {
        // Annotation mapping: @Column("name") maps to field "userName"
        // Database has column "name".
        // Query returns column "name".
        // Mapper sees column "name", looks up in meta, finds field "userName".
        
        val dtoList = db.from(TestUser::class.java, "u")
            .select("u.name", "u.age") // Standard select, no "AS" needed
            .listAs<AliasDto>()

        assertEquals(2, dtoList.size)
        assertEquals("Alice", dtoList[0].userName)
        assertEquals(20, dtoList[0].age)
    }
}

data class SimpleUserDto(
    var name: String? = null,
    var age: Int = 0
)
