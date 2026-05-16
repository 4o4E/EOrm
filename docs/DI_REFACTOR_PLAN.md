# EOrm DI and Large Project Refactor Plan

本文档记录 EOrm 在接入 Koin 等依赖注入框架、并作为大型项目基础模块使用前，建议进行的结构改造。

当前结论：EOrm 可以作为 DI 容器中的模块注入，但更适合作为按数据源划分的 `EOrm` 单例使用。若要在大型项目中承载多数据源、审计、监控、复杂事务、分布式 ID、插件化或长期运行服务，需要进一步收敛模块边界和生命周期管理。

## 目标

- 让 `EOrm` 能被 Koin、Spring、Guice 等 DI 容器稳定管理。
- 让事务、执行器、ID 生成、日志、元数据缓存等内部能力可以按项目需要替换或配置。
- 避免复杂调用中出现跨线程事务丢失、全局状态串扰、类加载器泄漏、构建目标不一致等问题。
- 保持现有 API 尽量兼容，优先通过新增构造参数、接口和默认实现完成演进。

## 优先级总览

| 优先级 | 改造点 | 必要性 |
| --- | --- | --- |
| P0 | 统一 Gradle JVM target / toolchain | 当前测试无法编译，必须先修 |
| P0 | 明确 `EOrm` 的 DI 生命周期和多数据源 qualifier 规范 | 避免大型项目中实例串用 |
| P1 | 将 `TransactionManager`、`SqlExecutor` 改为可注入组件 | 便于测试、监控、扩展和替换 |
| P1 | 事务 API 增加嵌套策略和协程使用约束 | 避免复杂调用中事务冲突 |
| P1 | 将 `IdGenerator` 从全局单例改为可配置接口 | 避免多实例部署 ID 冲突 |
| P2 | 元数据缓存可配置、可清理 | 适配插件化、动态类加载和长生命周期服务 |
| P2 | 日志和异常处理接入项目日志体系 | 避免 `println` / `printStackTrace` 污染输出 |
| P2 | 方言模块依赖从 `implementation` 调整为 `api` | 保证模块作为库发布时 API 暴露正确 |
| P3 | 明确 Builder 不进入 DI 容器 | 防止复用有状态 DSL 对象 |

## 1. 统一 Gradle JVM Target

### 原因

当前根构建脚本中 Java target 是 1.8，但 Kotlin 编译目标在当前环境下被推导为 21，执行测试时报错：

```text
Inconsistent JVM-target compatibility detected for tasks 'compileJava' (1.8) and 'compileKotlin' (21)
```

这会直接影响项目作为库模块被大型项目消费。大型项目通常有统一 JDK、Gradle toolchain 和 CI 环境，如果库自身 target 不稳定，会导致本地可编译、CI 不可编译，或被下游项目拉取后出现 class 版本不兼容。

### 方案

在根 `build.gradle.kts` 中统一 Java 和 Kotlin toolchain。若项目仍需兼容 Java 8，建议显式配置 Kotlin JVM target 为 1.8；若可以提高运行基线，则统一到 17。

Java 8 兼容方案：

```kotlin
subprojects {
    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(8))
        }
    }

    kotlin {
        jvmToolchain(8)
    }
}
```

如果 Kotlin 插件版本要求使用 compilerOptions，可补充：

```kotlin
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}
```

### 改造后效果

- `./gradlew test` 在本地和 CI 中目标一致。
- 下游项目引入 EOrm 时不会因为 JDK 版本推导不同导致编译失败。
- 为后续 DI 改造建立可验证基础。

## 2. 明确 EOrm 的 DI 生命周期

### 原因

`EOrm` 当前是普通 class，构造参数包括 `DataSource`、`SqlDialect`、`NameConverter`、`EOrmLogger`、`DataFiller`，天然可以被 Koin 注入。

风险在于大型项目通常存在：

- 多数据源：业务库、日志库、报表库。
- 多方言：MySQL、PostgreSQL、SQLite 测试库。
- 多租户：不同租户可能使用不同连接池或 schema。
- 多模块：不同业务模块都依赖 ORM，但不能共享错误的数据源。

如果只注册一个无 qualifier 的 `EOrm`，后续复杂调用中容易拿错实例。

### 方案

约定 `EOrm` 默认以 `single` 注册，生命周期与 `DataSource` 一致。多数据源必须使用 qualifier。

改造前：

```kotlin
val db = EOrm(dataSource, MySqlDialect())
```

改造后：

```kotlin
val eormModule = module {
    single<DataSource>(named("mainDs")) {
        createMainDataSource()
    }

    single<SqlDialect>(named("mainDialect")) {
        MySqlDialect()
    }

    single(named("mainDb")) {
        EOrm(
            dataSource = get(named("mainDs")),
            dialect = get(named("mainDialect")),
            logger = getOrNull() ?: ConsoleLogger,
            dataFiller = getOrNull() ?: NoOpDataFiller()
        )
    }
}
```

调用侧推荐由 Koin module 显式绑定 qualifier：

```kotlin
single {
    UserRepository(db = get(named("mainDb")))
}

class UserRepository(private val db: EOrm) {
    fun findById(id: Long): User? {
        return db.from<User>("u")
            .where { eq(User::id, id) }
            .firstOrNull()
    }
}
```

### 改造后效果

- `EOrm` 实例和数据源绑定关系清晰。
- 多数据源项目中不会因为默认注入导致误用数据库。
- `DataSource` 仍由应用负责关闭，`EOrm` 不抢占连接池生命周期。

## 3. 将内部组件改为可注入

### 原因

当前 `EOrm` 内部直接创建组件：

```kotlin
val transactionManager = TransactionManager(dataSource)
val executor = SqlExecutor(dataSource, logger, useLiterals, transactionManager, dialect)
```

这意味着 DI 容器只能管理 `EOrm` 外层，不能单独替换：

- `TransactionManager`
- `SqlExecutor`
- SQL formatter
- 执行监控逻辑
- tracing / metrics / audit wrapper

大型项目中，SQL 执行通常需要统一埋点、耗时统计、慢 SQL 记录、调用链 traceId、异常转换。内部硬编码 new 对这些扩展不友好。

### 方案

保留现有构造方式，同时新增可注入构造参数。默认参数继续使用现有实现，避免破坏旧代码。

改造前：

```kotlin
class EOrm(
    val dataSource: DataSource,
    val dialect: SqlDialect,
    val nameConverter: NameConverter = CamelToSnakeConverter,
    val logger: EOrmLogger = ConsoleLogger,
    val useLiterals: Boolean = false,
    val dataFiller: DataFiller = NoOpDataFiller()
) {
    val transactionManager = TransactionManager(dataSource)
    val executor = SqlExecutor(dataSource, logger, useLiterals, transactionManager, dialect)
}
```

改造后：

```kotlin
class EOrm(
    val dataSource: DataSource,
    val dialect: SqlDialect,
    val nameConverter: NameConverter = CamelToSnakeConverter,
    val logger: EOrmLogger = ConsoleLogger,
    val useLiterals: Boolean = false,
    val dataFiller: DataFiller = NoOpDataFiller(),
    val transactionManager: TransactionManager = TransactionManager(dataSource),
    val executor: SqlExecutor = SqlExecutor(
        dataSource = dataSource,
        logger = logger,
        useLiterals = useLiterals,
        transactionManager = transactionManager,
        dialect = dialect
    )
)
```

Koin 注册：

```kotlin
single { TransactionManager(get<DataSource>()) }

single {
    SqlExecutor(
        dataSource = get(),
        logger = get(),
        useLiterals = false,
        transactionManager = get(),
        dialect = get()
    )
}

single {
    EOrm(
        dataSource = get(),
        dialect = get(),
        logger = get(),
        transactionManager = get(),
        executor = get()
    )
}
```

### 改造后效果

- 测试可以注入 fake executor 或 mock transaction manager。
- 生产可以包装 executor 做慢 SQL、metrics、traceId、审计。
- 保持现有 `EOrm(dataSource, dialect)` 用法不变。

## 4. 强化事务边界和嵌套策略

### 原因

当前同步事务基于 `ThreadLocal`，协程事务通过 `CoroutineTransaction` 把连接恢复到线程上下文。这个设计在基础场景可用，但大型项目中有几个风险：

- 手动 `beginTransaction()` 后如果业务异常没有 rollback，会占住连接。
- 同步事务不能跨线程传播。
- 协程中误用同步事务 API 会导致切线程后事务丢失。
- 当前嵌套事务直接抛错，不支持 `REQUIRED` 这类常见传播语义。

### 方案

第一阶段先明确约束并提供更安全 API：

- 同步代码统一使用 `transaction {}`。
- 协程代码统一使用 `suspendTransaction {}`。
- 标记 `beginTransaction()`、`commitTransaction()`、`rollbackTransaction()` 为高级 API，并在文档中限制使用。

第二阶段引入事务传播策略：

```kotlin
enum class TransactionPropagation {
    REQUIRED,
    REQUIRES_NEW,
    NEVER
}
```

示例 API：

```kotlin
fun <T> transaction(
    propagation: TransactionPropagation = TransactionPropagation.REQUIRED,
    block: EOrm.() -> T
): T
```

改造前：

```kotlin
db.transaction {
    userRepository.create(user)
    orderRepository.create(order)
}
```

如果内部再次调用：

```kotlin
fun create(user: User) {
    db.transaction {
        db.insert(user)
    }
}
```

当前会出现嵌套事务异常。

改造后示意：

```kotlin
fun <T> transaction(
    propagation: TransactionPropagation = TransactionPropagation.REQUIRED,
    block: EOrm.() -> T
): T {
    if (transactionManager.isInTransaction()) {
        return when (propagation) {
            TransactionPropagation.REQUIRED -> this.block()
            TransactionPropagation.NEVER -> error("Transaction already active")
            // suspendAndRunNew 表示先挂起当前连接，再用新连接执行内层事务。
            TransactionPropagation.REQUIRES_NEW -> transactionManager.suspendAndRunNew { this.block() }
        }
    }

    transactionManager.begin()
    return try {
        val result = this.block()
        transactionManager.commit()
        result
    } catch (e: Exception) {
        transactionManager.rollback()
        throw e
    }
}
```

### 改造后效果

- Repository 内部可以安全调用需要事务的方法。
- 外层 service 事务和内层 repository 事务不会意外冲突。
- 协程和同步事务边界清晰，减少复杂调用中的连接泄漏和事务丢失。

## 5. 将 IdGenerator 改为可配置接口

### 原因

当前 `IdGenerator` 是全局 `object`，Snowflake 的 `workerId` 和 `datacenterId` 固定为 1。单 JVM 内由 `@Synchronized` 保证并发安全，但多实例部署时，不同服务实例会使用相同 worker 信息，存在 ID 冲突风险。

大型项目中 ID 生成通常需要：

- 从配置中心读取 workerId。
- 按机房、节点、容器实例区分 datacenterId。
- 替换为数据库序列、Redis、Leaf、Segment、UUID v7 等方案。

### 方案

引入接口：

```kotlin
interface EOrmIdGenerator {
    fun nextSnowflakeId(): Long
    fun nextUuid(): String
}
```

默认实现保持现有行为：

```kotlin
class DefaultIdGenerator(
    private val workerId: Long = 1,
    private val datacenterId: Long = 1
) : EOrmIdGenerator {
    override fun nextSnowflakeId(): Long {
        // move current Snowflake implementation here
    }

    override fun nextUuid(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }
}
```

`EOrm` 注入：

```kotlin
class EOrm(
    // existing params
    val idGenerator: EOrmIdGenerator = IdGenerator
)
```

默认继续使用全局 `IdGenerator` 以保持既有行为和单 JVM 内的序列连续性；需要按节点配置时，再通过 DI 注入 `DefaultIdGenerator(workerId, datacenterId)` 或自定义实现。

插入逻辑改造前：

```kotlin
IdStrategy.SNOWFLAKE -> idField.set(entity, IdGenerator.nextSnowflakeId())
IdStrategy.UUID -> idField.set(entity, IdGenerator.nextUuid())
```

改造后：

```kotlin
IdStrategy.SNOWFLAKE -> idField.set(entity, idGenerator.nextSnowflakeId())
IdStrategy.UUID -> idField.set(entity, idGenerator.nextUuid())
```

Koin 示例：

```kotlin
single<EOrmIdGenerator> {
    DefaultIdGenerator(
        workerId = get<AppConfig>().workerId,
        datacenterId = get<AppConfig>().datacenterId
    )
}
```

### 改造后效果

- 多实例部署可以避免 Snowflake ID 冲突。
- 项目可以按自己的基础设施替换 ID 生成策略。
- 测试可以注入固定 ID 生成器，断言更稳定。

## 6. 元数据缓存可配置、可清理

### 原因

当前 `MetaCache` 是全局 `object`，内部使用 `ConcurrentHashMap`，并发读取没有明显问题。但全局缓存有两个长期风险：

- 插件化或动态类加载场景中，`Class<*>` 作为 key 可能阻止类卸载。
- 多租户或运行期动态模型场景中，缓存无法按租户或模块清理。

此外，`MetaKey` 使用 `NameConverter` 作为 key 的一部分。如果用户传入自定义 converter，但没有稳定的 `equals` / `hashCode`，可能造成重复缓存。

### 方案

引入 `MetaRegistry` 接口，保留默认全局实现：

```kotlin
interface MetaRegistry {
    fun get(clazz: Class<*>, converter: NameConverter): TableMeta
    fun clear()
    fun clear(clazz: Class<*>)
}
```

默认实现：

```kotlin
class DefaultMetaRegistry : MetaRegistry {
    private val cache = ConcurrentHashMap<MetaKey, TableMeta>()

    override fun get(clazz: Class<*>, converter: NameConverter): TableMeta {
        return cache.computeIfAbsent(MetaKey(clazz, converter)) {
            parseMeta(clazz, converter)
        }
    }

    override fun clear() {
        cache.clear()
    }

    override fun clear(clazz: Class<*>) {
        cache.keys.removeIf { it.clazz == clazz }
    }
}
```

`EOrm` 注入：

```kotlin
class EOrm(
    // existing params
    val metaRegistry: MetaRegistry = GlobalMetaRegistry
)
```

调用改造前：

```kotlin
val meta = MetaCache.get(clazz, nameConverter)
```

调用改造后：

```kotlin
val meta = metaRegistry.get(clazz, nameConverter)
```

### 改造后效果

- 默认使用方式不变，普通服务仍然享受缓存。
- 插件化项目可以按模块创建独立 `MetaRegistry`。
- 测试可以清理缓存，避免测试间状态残留。

## 7. 日志和异常处理接入项目日志体系

### 原因

当前默认 `ConsoleLogger` 使用 `println`，部分异常处理使用 `e.printStackTrace()`。大型项目中这会带来问题：

- 日志无法进入统一日志框架。
- 无法附加 traceId、requestId、tenantId。
- 标准输出被 SQL 和异常堆栈污染。
- 异常被打印后又抛出，可能导致重复日志。

### 方案

保留 `EOrmLogger` 接口，但提供更适合生产的实现入口，例如 slf4j adapter。核心库也应避免直接 `printStackTrace()`，统一通过 logger 记录或直接抛出。

改造前：

```kotlin
catch (e: Exception) {
    e.printStackTrace()
    throw e
}
```

改造后：

```kotlin
catch (e: Exception) {
    logger.error("Failed to execute batch insert", e)
    throw e
}
```

Koin 示例：

```kotlin
single<EOrmLogger> {
    Slf4jEOrmLogger(LoggerFactory.getLogger("EOrm"))
}
```

### 改造后效果

- SQL 日志进入统一日志系统。
- 生产环境可以按日志级别控制输出。
- 异常记录由应用统一处理，避免重复打印。

## 8. 方言模块使用 api 暴露 core

### 原因

当前方言模块依赖 core：

```kotlin
dependencies {
    implementation(project(":eorm-core"))
}
```

方言类公开继承了 `BaseDialect`，而 `BaseDialect` 来自 `eorm-core`。当方言模块作为独立库发布时，`eorm-core` 是它公开 API 的一部分。使用 `implementation` 可能导致下游只依赖 `eorm-dialect-mysql` 时，编译期无法正确访问公开父类或相关类型。

### 方案

将方言模块依赖改为 `api`：

```kotlin
dependencies {
    api(project(":eorm-core"))
}
```

适用模块：

- `eorm-dialect-mysql`
- `eorm-dialect-postgres`
- `eorm-dialect-sqlite`
- `eorm-dialect-h2`

### 改造后效果

- 下游引入方言模块时自动获得必要的 core API。
- Maven 发布后的依赖元数据更准确。
- 避免大型项目中显式重复声明 core 依赖。

## 9. 明确 DSL Builder 不能进入 DI 容器

### 原因

`Query`、`SubQuery`、`UpdateBuilder`、`DeleteBuilder` 都是有状态对象，内部维护 selections、whereBuilder、params、limit 等可变状态。它们应该是每次查询新建的短生命周期对象。

如果误注册为 Koin `single`，会出现：

- 查询条件串扰。
- 参数列表残留。
- 并发请求互相覆盖状态。
- SQL 生成结果不可预测。

### 方案

文档明确只注入 `EOrm`，不注入 Builder。

错误方式：

```kotlin
single { get<EOrm>().from<User>("u") }
```

正确方式：

```kotlin
class UserRepository(
    private val db: EOrm
) {
    fun activeUsers(): List<User> {
        return db.from<User>("u")
            .where { eq(User::active, true) }
            .list()
    }
}
```

如需封装查询模板，封装为函数，不缓存 Builder：

```kotlin
class UserQueries(
    private val db: EOrm
) {
    fun baseUserQuery() = db.from<User>("u")
        .select(User::id, User::name, User::active)
}
```

### 改造后效果

- 查询对象天然隔离到单次调用。
- 并发请求不会共享 mutable builder 状态。
- Repository API 更清晰。

## 10. DataSource 生命周期由应用管理

### 原因

`EOrm` 接收 `DataSource`，但不拥有它。大型项目中连接池通常由应用容器管理，例如 Koin、Spring 或自定义 bootstrap。若 `EOrm` 也尝试关闭 `DataSource`，会破坏容器生命周期。

### 方案

保持 `EOrm` 不实现 `Closeable`，或即使未来实现，也只在明确 owning 模式下关闭。推荐在应用层注册连接池关闭逻辑。

Koin 示例：

```kotlin
single(createdAtStart = true) {
    HikariDataSource(createHikariConfig())
} onClose { dataSource ->
    dataSource?.close()
}

single {
    EOrm(
        dataSource = get<HikariDataSource>(),
        dialect = get()
    )
}
```

### 改造后效果

- 连接池生命周期单一来源。
- 应用关闭时资源释放明确。
- 避免 ORM 和应用容器重复关闭资源。

## 建议落地顺序

1. 修复 Gradle JVM target，确保测试可运行。
2. 将方言模块依赖改为 `api(project(":eorm-core"))`。
3. 文档明确 Koin 注册方式、qualifier、多数据源和 Builder 禁止注入规则。
4. 给 `EOrm` 增加可注入的 `TransactionManager`、`SqlExecutor` 参数，并保持默认值兼容旧代码。
5. 引入 `EOrmIdGenerator`，替换全局 `IdGenerator` 直接调用。
6. 引入事务传播策略，优先支持 `REQUIRED`。
7. 引入 `MetaRegistry`，让元数据缓存可清理、可替换。
8. 提供 slf4j logger adapter，并移除核心代码中的 `printStackTrace()`。

## 验收标准

- `./gradlew test` 可以稳定通过。
- 单数据源项目可以继续使用 `EOrm(dataSource, dialect)`。
- Koin 中可以注册多个带 qualifier 的 `EOrm` 实例。
- Repository 层只注入 `EOrm`，不注入任何 Builder。
- 协程事务测试覆盖 dispatcher 切换场景。
- 嵌套事务在 `REQUIRED` 策略下不会抛出当前线程已有事务异常。
- 多实例部署可以通过配置不同 workerId/datacenterId 生成 Snowflake ID。
- 核心代码不再直接 `println` 或 `printStackTrace()`，默认 logger 除外。
