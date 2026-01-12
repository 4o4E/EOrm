# EOrm

该项目设计了一个轻量级的orm框架，旨在简化数据库操作，提高开发效率。EOrm设计为提供多种数据库支持、易于扩展和高性能的解决方案。

## 特性

- 使用HikariCP进行数据库连接池管理，确保高效的连接复用和性能优化。
- 支持多种数据库类型，包括MySQL、PostgreSQL、SQLite等，方便开发者根据需求选择合适的数据库。
- 提供MybatisPlus风格的dsl查询构建器，简化复杂查询的编写。
- 不使用sql template占位符，直接构建查询语句，对打印sql语句提供原生支持，方便调试和日志记录。
## 🚀 快速开始 (Get Started)

```kotlin
repositories {
    maven("https://nexus.e404.top:3443/repository/maven-snapshots/")
}

dependencies {
    // EOrm 核心库
    implementation("top.e404.eorm:eorm-core:1.0.0-SNAPSHOT")
    // MySQL 方言支持
    implementation("top.e404.eorm:eorm-dialect-mysql:1.0.0-SNAPSHOT")
}
```

### 1. 定义实体 (Entity)

使用注解简单映射表结构。支持 `Snowflake`、`UUID` 和 `AUTO` 等多种主键策略。

```kotlin
@Table("sys_user") // 映射表名
data class User(
    @Id(strategy = IdStrategy.AUTO) // 自增主键
    var id: Long = 0,

    @Column(name = "user_name", length = 50) // 自定义列名
    var name: String? = null,

    var age: Int = 0, // 默认映射列名 'age'

    @Column(nullable = false)
    var email: String? = null
)
```

### 2. 初始化 (Initialization)

```kotlin
val dataSource: DataSource = ... // HikariCP 或其他数据源

// 初始化 EOrm 实例，指定方言 (MySQL, Postgres, SQLite, H2)
val db = EOrm(dataSource, MysqlDialect())

// (可选) 创建表
db.createTable<User>()
```

### 3. 基础 CRUD

```kotlin
// 插入
val user = User(name = "Peter", age = 52)
db.insert(user)

// 批量插入
val users = listOf(User("A"), User("B"))
db.insert(users)

// 更新 (根据 ID)
user.age = 53
db.update(user)

// 更新 (DSL 风格)
db.update<User>()
    .set(User::age, 18)
    .where { eq(User::name, "Peter") }
    .exec()

// 删除
db.deleteById<User>(user.id)
```

### 4. 强大的 DSL 查询

支持类似 MyBatis-Plus 的流畅查询，但更加符合 Kotlin 语法习惯。

#### 基础查询

```kotlin
val list = db.from<User>("u")
    .where {
        eq(User::age, 18)
        and {
            like(User::name, "Li%")
            or { gt(User::age, 100) } // 自动处理括号嵌套
        }
    }
    .list()
```

#### 联表查询 (Joins)

类型安全的 Join 操作，支持多种 Join 类型。

```kotlin
val result = db.from<User>("u")
    .select(User::name, Role::roleName) // 自动处理别名
    .leftJoin<UserRole>("ur") {
        eqCol(User::id, UserRole::userId)
    }
    .innerJoin<Role>("r") {
        eqCol(UserRole::roleId, Role::id) // r.id = ur.role_id
    }
    .where { eq(Role::roleName, "Admin") }
    .listMaps() // 返回 Map 结果
```

#### 分页查询 (Pagination) (New ✨)

内置智能分页支持，自动处理 Count 查询优化。

```kotlin
// 查询第 1 页，每页 10 条
// 默认会自动执行 SELECT COUNT(*)
val page = db.from<User>("u")
    .where { gt(User::age, 18) }
    .page(1, 10)

println("Total: ${page.total}, Records: ${page.records.size}")

// 性能优化：在复杂查询中跳过 COUNT (searchCount = false)
// total 将返回 -1
val fastPage = db.from<User>("u").page(1, 10, searchCount = false)
```

#### DTO 投影 (DTO Projection) (New ✨)

直接将结果映射为 DTO，支持 `@Column` 自动匹配列别名。

```kotlin
data class UserDto(
    @Column(name = "user_name") // 自动匹配 SQL 中的字段
    var userName: String? = null
)

val dtoList = db.from<User>("u")
    .select(User::name) // 自动识别为 user_name (假设 User 定义) 或需手动别名
    .listAs<UserDto>()
```

## 📦 支持数据库

- MySQL
- PostgreSQL
- SQLite
- H2
