package top.e404.eorm.log

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * 基于 SLF4J 的日志适配器。
 *
 * SLF4J 是日志门面，实际输出级别、格式和目标由业务项目引入的 Logback、Log4j2 等绑定决定。
 */
class Slf4jEOrmLogger(
    private val logger: Logger = LoggerFactory.getLogger("EOrm")
) : EOrmLogger {

    override fun logSql(sql: String, args: List<Any?>) {
        if (args.isEmpty()) {
            logger.debug("SQL: {}", sql)
        } else {
            logger.debug("SQL: {} | args: {}", sql, args)
        }
    }

    override fun debug(message: String) {
        logger.debug(message)
    }

    override fun info(message: String) {
        logger.info(message)
    }

    override fun warn(message: String, e: Throwable?) {
        if (e == null) {
            logger.warn(message)
        } else {
            logger.warn(message, e)
        }
    }

    override fun error(message: String, e: Throwable?) {
        if (e == null) {
            logger.error(message)
        } else {
            logger.error(message, e)
        }
    }

    companion object {
        /**
         * 使用指定 logger 名称创建适配器，便于业务项目按包名或模块名配置日志级别。
         */
        @JvmStatic
        fun named(name: String): Slf4jEOrmLogger = Slf4jEOrmLogger(LoggerFactory.getLogger(name))

        /**
         * 使用指定类创建适配器，适合按调用方类型归类日志。
         */
        @JvmStatic
        fun forClass(clazz: Class<*>): Slf4jEOrmLogger = Slf4jEOrmLogger(LoggerFactory.getLogger(clazz))
    }
}
