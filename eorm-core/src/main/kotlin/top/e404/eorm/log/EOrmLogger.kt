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
    fun info(message: String)

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
object ConsoleLogger : EOrmLogger {
    override fun logSql(sql: String, args: List<Any?>) {
        if (args.isEmpty()) {
            println("[EOrm] SQL: $sql")
        } else {
            println("[EOrm] SQL: $sql | args: $args")
        }
    }
    override fun info(message: String) = println("[EOrm] INFO: $message")
    override fun error(message: String, e: Throwable?) { println("[EOrm] ERROR: $message"); e?.printStackTrace() }
}
