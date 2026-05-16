package top.e404.eorm.log

/**
 * EOrm 日志接口，用于记录 SQL 执行和运行时信息。
 */
interface EOrmLogger {
    /**
     * 记录 SQL 语句及其参数。
     * @param sql SQL 语句
     * @param args 参数列表
     */
    fun logSql(sql: String, args: List<Any?> = emptyList())

    /**
     * 记录普通信息。
     * @param message 日志消息
     */
    /**
     * 记录调试信息，适合高频正常路径。
     * 默认转发到 info，保证旧实现兼容。
     */
    fun debug(message: String) = info(message)

    fun info(message: String)

    /**
     * 记录警告信息，适合可预期但需要关注的状态。
     * 默认按是否有异常转发，保证旧实现兼容。
     */
    fun warn(message: String, e: Throwable? = null) {
        if (e == null) info(message) else error(message, e)
    }

    /**
     * 记录错误信息。
     * @param message 错误消息
     * @param e 异常对象，可为 null
     */
    fun error(message: String, e: Throwable?)
}

/**
 * 基于控制台输出的日志实现。
 */
// TODO: 后续提供基于 SLF4J 的 EOrmLogger 适配器，生产环境优先交给业务项目的日志框架控制级别、格式和输出目标。
object ConsoleLogger : EOrmLogger {
    override fun logSql(sql: String, args: List<Any?>) {
        if (args.isEmpty()) {
            println("[EOrm] SQL: $sql")
        } else {
            println("[EOrm] SQL: $sql | args: $args")
        }
    }
    override fun debug(message: String) = println("[EOrm] DEBUG: $message")
    override fun info(message: String) = println("[EOrm] INFO: $message")
    override fun warn(message: String, e: Throwable?) { println("[EOrm] WARN: $message"); e?.printStackTrace() }
    override fun error(message: String, e: Throwable?) { println("[EOrm] ERROR: $message"); e?.printStackTrace() }
}
