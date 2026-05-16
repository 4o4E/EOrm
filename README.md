# EOrm

该项目设计了一个轻量级的orm框架，旨在简化数据库操作，提高开发效率。EOrm设计为提供多种数据库支持、易于扩展和高性能的解决方案。

## 特性

- 使用HikariCP进行数据库连接池管理，确保高效的连接复用和性能优化。
- 支持多种数据库类型，包括MySQL、PostgreSQL、SQLite等，方便开发者根据需求选择合适的数据库。
- 提供MybatisPlus风格的dsl查询构建器，简化复杂查询的编写。
- 支持 `useLiterals` 模式，直接将参数值拼入 SQL 语句，方便调试和日志记录。
- 内置 Snowflake / UUID / 自增 / 手动赋值等多种主键策略。
- 插入前自动校验非空约束和字符串长度限制。
- 支持自动填充字段（如创建时间）。
- 支持 `LocalDateTime`、`LocalDate`、`LocalTime` 等 Java 8 时间类型的自动映射。
- 默认驼峰转下划线命名策略，支持自定义。

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

使用注解简单映射表结构。支持 `Snowflake`、`UUID`、`AUTO` 和 `MANUAL` 等多种主键策略。

```kotlin
@Table("sys_user") // 映射表名
data class User(
    @Id(strategy = IdStrategy.AUTO) // 自增主键
    var id: Long = 0,

    @Column(name = "user_name", length = 50) // 自定义列名
    var name: String? = null,

    var age: Int = 0, // 默认映射列名 'age'

    @Column(nullable = false)
    var email: String? = null,

    @Transient // 不映射到数据库
    var tempFlag: Boolean = false
)
```

#### 注解说明

| 注解 | 目标 | 说明 |
|---|---|---|
| `@Table(name)` | 类 | 指定表名。不标注则使用 `NameConverter` 转换类名 |
| `@Id(strategy)` | 字段 | 标记主键。策略：`AUTO`（自增）、`SNOWFLAKE`（雪花算法 Long）、`UUID`（32位无横线字符串）、`MANUAL`（手动赋值） |
| `@Column(name, length, nullable, unique, sqlType, json)` | 字段 | 自定义列名、长度、是否可空、单列唯一索引、显式 SQL 类型、JSON 自动映射 |
| `@Index / @Indexes` | 类 | 声明普通索引或唯一索引，支持复合索引 |
| `@Transient` | 字段 | 标记不映射到数据库的字段 |

### 2. 初始化 (Initialization)

EOrm 构造函数支持多个可选配置项：

```kotlin
val dataSource: DataSource = ... // HikariCP 或其他数据源

val db = EOrm(
    dataSource = dataSource,
    dialect = MySqlDialect(),                    // 方言 (MySQL, Postgres, SQLite, H2)
    nameConverter = CamelToSnakeConverter,        // 命名转换策略（默认驼峰转下划线）
    logger = ConsoleLogger,                      // 日志实现（默认控制台输出）
    useLiterals = false,                         // true 时直接拼接参数值到 SQL，方便调试
    dataFiller = NoOpDataFiller()                // 自动填充器（如创建时间等）
)

// (可选) 创建表
db.createTable<User>()

// (可选) 仅生成 DDL 字符串，不执行
val ddl = db.generateDdl(User::class.java)
```

#### useLiterals 模式

开启后，SQL 中的 `?` 占位符会被替换为实际参数值，生成的 SQL 可直接复制到数据库客户端执行，方便调试：

```kotlin
val db = EOrm(dataSource, MySqlDialect(), useLiterals = true)
// 日志输出: SELECT * FROM `sys_user` u WHERE u.`age` = 18
// 而非:     SELECT * FROM `sys_user` u WHERE u.`age` = ?
```

#### 自定义 DataFiller

实现 `DataFiller` 接口，在插入前自动填充字段：

```kotlin
class MyDataFiller : DataFiller {
    override fun insertFill(entity: Any, converter: NameConverter) {
        // 自动填充创建时间
        setFieldValByName(entity, "createdAt", LocalDateTime.now(), converter)
    }
}

val db = EOrm(dataSource, MySqlDialect(), dataFiller = MyDataFiller())
```

### 3. 基础 CRUD

```kotlin
// 插入
val user = User(name = "Peter", age = 52)
db.insert(user)

// 批量插入
val users = listOf(User(name = "A"), User(name = "B"))
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

// 删除 (DSL 风格)
db.delete<User>()
    .where { lt(User::age, 10) }
    .exec()
```

插入时会自动执行以下流程：ID 生成（Snowflake/UUID）→ DataFiller 填充 → 实体校验（非空约束、字符串长度）→ 批量执行 → 回填自增 ID。

#### Upsert

基于唯一索引或主键冲突执行插入或更新：

```kotlin
db.upsert(user)
    .on(User::email)
    .update(User::name, User::age)
    .exec()
```

如果不显式指定 `update(...)`，默认更新所有非主键、非冲突列。

#### 唯一索引

单列唯一索引：

```kotlin
@Column(unique = true)
var email: String = ""
```

复合唯一索引：

```kotlin
@Table("sys_user")
@Index(name = "uk_tenant_email", columns = ["tenantId", "email"], unique = true)
data class User(...)
```

#### JSON / JSONB 字段

字段标记为 `json = true` 后会自动序列化和反序列化，可以直接把实体类作为 JSON 字段存储：

```kotlin
data class Profile(
    var city: String = "",
    var score: Int = 0
)

data class User(
    @Id
    var id: Long = 0,
    @Column(json = true)
    var profile: Profile? = null
)
```

PostgreSQL 方言默认使用 `JSONB`，MySQL 方言默认使用 `JSON`，其他方言默认使用 `TEXT`。也可以显式指定：

```kotlin
@Column(json = true, sqlType = "VARCHAR(1000)")
var profile: Profile? = null
```

### 4. 强大的 DSL 查询

支持类似 MyBatis-Plus 的流畅查询，但更加符合 Kotlin 语法习惯。

`from<T>(alias)` 中的 `alias` 是表别名，用于多表查询时区分列来源。

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

// 获取单条记录（内部自动 limit(1)）
val user = db.from<User>("u")
    .where { eq(User::id, 1) }
    .firstOrNull()

// nest 嵌套：生成 AND (...)
val list2 = db.from<User>("u")
    .where {
        nest {
            lt(User::age, 15)
            or()
            gt(User::age, 50)
        }
    }
    .list()

// select 支持字符串和属性引用两种方式
val list3 = db.from<User>("u")
    .select(User::name, User::age)       // 属性引用（推荐）
    .select("COUNT(*) AS cnt")           // 字符串（用于聚合等场景）
    .listMaps()
```

#### 联表查询 (Joins)

类型安全的 Join 操作，支持 `innerJoin`、`leftJoin`、`rightJoin`。

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

#### 分页查询 (Pagination)

内置智能分页支持，自动处理 Count 查询优化。

```kotlin
// 查询第 1 页，每页 10 条
// 默认会自动执行 SELECT COUNT(*)
val page = db.from<User>("u")
    .where { gt(User::age, 18) }
    .page(1, 10)

println("Total: ${page.total}, Records: ${page.records.size}")
println("Pages: ${page.pages}") // 自动计算总页数

// 性能优化：在复杂查询中跳过 COUNT (searchCount = false)
// total 将返回 -1
val fastPage = db.from<User>("u").page(1, 10, searchCount = false)

// Page.map 转换记录类型
val dtoPage = page.map { UserDto(it.name) }
```

#### DTO 投影 (DTO Projection)

直接将结果映射为 DTO，支持 `@Column` 自动匹配列别名。

```kotlin
data class UserDto(
    @Column(name = "user_name") // 自动匹配 SQL 中的字段
    var userName: String? = null
)

val dtoList = db.from<User>("u")
    .select(User::name) // 自动识别为 user_name
    .listAs<UserDto>()
```

#### 子查询 (SubQuery)

支持将子查询封装为对象，用于 `IN`、`EXISTS`、标量比较等复杂嵌套场景。

```kotlin
// IN 子查询：查询拥有 Admin 角色的用户
val adminUserIds = db.subQuery<UserRole>("ur")
    .select(UserRole::userId)
    .innerJoin<Role>("r") { eqCol(Role::id, UserRole::roleId) }
    .where { eq(Role::name, "Admin") }

val admins = db.from<User>("u")
    .where { inSub(User::id, adminUserIds) }
    .list()

// EXISTS 子查询（lambda 重载，自动注入外层别名）
val usersWithRoles = db.from<User>("u")
    .where {
        exists(db.subQuery<UserRole>("ur")) { sub ->
            sub.select("1")
                .where { eqCol(UserRole::userId, User::id) }
        }
    }
    .list()

// 标量子查询比较
val avgAge = db.subQuery<User>("u2").select("AVG(u2.age)")
val aboveAvg = db.from<User>("u")
    .where { gtSub(User::age, avgAge) }
    .list()

// NOT IN 子查询
val nonAdmins = db.from<User>("u")
    .where { notInSub(User::id, adminUserIds) }
    .list()
```

### 5. 类型映射

EOrm 自动处理 Java/Kotlin 类型与 JDBC 类型之间的转换：

| Kotlin 类型 | JDBC 类型 | 说明 |
|---|---|---|
| `Int` | INT | |
| `Long` | BIGINT | |
| `Double` | DOUBLE | |
| `Boolean` | BOOLEAN / TINYINT | 兼容 Number 和 String 反序列化 |
| `String` | VARCHAR(n) | 长度由 `@Column(length)` 控制 |
| `LocalDateTime` | DATETIME / TIMESTAMP | 自动与 `java.sql.Timestamp` 互转 |
| `LocalDate` | DATE | 自动与 `java.sql.Date` 互转 |
| `LocalTime` | TIME | 自动与 `java.sql.Time` 互转 |
| `java.util.Date` | DATETIME | |

### 6. 命名转换

默认使用 `CamelToSnakeConverter`，将驼峰命名转为下划线命名：

```
userName  → user_name
createdAt → created_at
```

可通过实现 `NameConverter` 接口自定义转换策略：

```kotlin
object MyConverter : NameConverter {
    override fun convert(name: String): String = name.lowercase()
}

val db = EOrm(dataSource, MySqlDialect(), nameConverter = MyConverter)
```

### 7. 事务支持

支持普通线程和协程两种事务模式。

#### 编程式事务（推荐）

```kotlin
db.transaction {
    val user = User(name = "Alice", age = 20)
    insert(user)
    update<User>().set(User::age, 21).where { eq(User::id, user.id) }.exec()
    // 异常自动回滚，正常结束自动提交
}
```

同步事务支持传播策略，默认 `REQUIRED` 会在嵌套调用时复用当前事务：

```kotlin
db.transaction {
    userRepository.create(user) // 内部再次调用 transaction { ... } 时复用外层事务
}

db.transaction(TransactionPropagation.REQUIRES_NEW) {
    auditRepository.save(record) // 使用独立事务
}
```

#### 协程事务

协程切换线程时事务连接会自动跟随，通过 `CoroutineContext` 维护。

```kotlin
suspend fun createUser() {
    db.suspendTransaction {
        insert(User(name = "Bob", age = 25))
        withContext(Dispatchers.IO) {
            insert(User(name = "Charlie", age = 30)) // 切换线程后事务仍有效
        }
    }
}
```

#### 手动事务（高级用法）

```kotlin
db.beginTransaction()
try {
    db.insert(user1)
    db.insert(user2)
    db.commitTransaction()
} catch (e: Exception) {
    db.rollbackTransaction()
    throw e
}
```

## 📦 支持数据库

- MySQL
- PostgreSQL
- SQLite
- H2

需要添加其他数据库支持？参考 [添加数据库方言集成指南](DIALECT_GUIDE.md)。

## 文档

- [DI 与大型项目改造计划](docs/DI_REFACTOR_PLAN.md)
