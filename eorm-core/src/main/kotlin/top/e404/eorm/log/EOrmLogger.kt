package top.e404.eorm.log

interface EOrmLogger {
    fun logSql(sql: String, args: List<Any?> = emptyList())
    fun info(message: String)
    fun error(message: String, e: Throwable?)
}

object ConsoleLogger : EOrmLogger {
    override fun logSql(sql: String, args: List<Any?>) {
        if (args.isEmpty()) {
            println("[EOrm] SQL: $sql")
        } else {
            println("[EOrm] SQL: ${DefaultSqlFormatter.format(sql, args)}")
        }
    }
    override fun info(message: String) = println("[EOrm] INFO: $message")
    override fun error(message: String, e: Throwable?) { println("[EOrm] ERROR: $message"); e?.printStackTrace() }
}
