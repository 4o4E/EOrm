package top.e404.eorm.log

interface EOrmLogger {
    fun logSql(sql: String)
    fun info(message: String)
    fun error(message: String, e: Throwable?)
}

class ConsoleLogger : EOrmLogger {
    override fun logSql(sql: String) = println("[EOrm] SQL: $sql")
    override fun info(message: String) = println("[EOrm] INFO: $message")
    override fun error(message: String, e: Throwable?) { println("[EOrm] ERROR: $message"); e?.printStackTrace() }
}
