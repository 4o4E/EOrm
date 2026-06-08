package top.e404.eorm.migration

/**
 * SQL 脚本拆分器，按分号拆分多语句脚本，同时避开字符串和注释内的分号。
 */
object SqlScriptParser {
    fun splitStatements(script: String): List<String> {
        val statements = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        var inSingleQuote = false
        var inDoubleQuote = false
        var inLineComment = false
        var inBlockComment = false
        var dollarQuoteDelimiter: String? = null

        while (i < script.length) {
            val c = script[i]
            val next = script.getOrNull(i + 1)

            dollarQuoteDelimiter?.let { delimiter ->
                if (script.startsWith(delimiter, i)) {
                    current.append(delimiter)
                    dollarQuoteDelimiter = null
                    i += delimiter.length
                } else {
                    current.append(c)
                    i++
                }
                continue
            }

            if (inLineComment) {
                current.append(c)
                if (c == '\n') inLineComment = false
                i++
                continue
            }

            if (inBlockComment) {
                current.append(c)
                if (c == '*' && next == '/') {
                    current.append(next)
                    inBlockComment = false
                    i += 2
                } else {
                    i++
                }
                continue
            }

            if (!inSingleQuote && !inDoubleQuote && c == '-' && next == '-') {
                current.append(c).append(next)
                inLineComment = true
                i += 2
                continue
            }

            if (!inSingleQuote && !inDoubleQuote && c == '/' && next == '*') {
                current.append(c).append(next)
                inBlockComment = true
                i += 2
                continue
            }

            // PostgreSQL dollar quote 块内的分号属于块内容，不能作为语句分隔符。
            if (!inSingleQuote && !inDoubleQuote && c == '$') {
                val delimiter = dollarQuoteDelimiterAt(script, i)
                if (delimiter != null) {
                    current.append(delimiter)
                    dollarQuoteDelimiter = delimiter
                    i += delimiter.length
                    continue
                }
            }

            if (c == '\'' && !inDoubleQuote) {
                current.append(c)
                if (inSingleQuote && next == '\'') {
                    current.append(next)
                    i += 2
                    continue
                }
                inSingleQuote = !inSingleQuote
                i++
                continue
            }

            if (c == '"' && !inSingleQuote) {
                current.append(c)
                inDoubleQuote = !inDoubleQuote
                i++
                continue
            }

            if (c == ';' && !inSingleQuote && !inDoubleQuote) {
                addStatement(statements, current)
            } else {
                current.append(c)
            }
            i++
        }

        addStatement(statements, current)
        return statements
    }

    private fun addStatement(statements: MutableList<String>, current: StringBuilder) {
        val statement = current.toString().trim()
        if (statement.isNotEmpty()) statements.add(statement)
        current.clear()
    }

    private fun dollarQuoteDelimiterAt(script: String, start: Int): String? {
        if (start > 0 && isDollarQuoteTagChar(script[start - 1])) return null
        var end = start + 1
        while (end < script.length && script[end] != '$') {
            val c = script[end]
            if (!isDollarQuoteTagChar(c)) return null
            end++
        }
        if (end >= script.length) return null
        if (end == start + 1) return "$$"
        if (!isDollarQuoteTagStart(script[start + 1])) return null
        return script.substring(start, end + 1)
    }

    private fun isDollarQuoteTagStart(c: Char): Boolean = c == '_' || c.isLetter()

    private fun isDollarQuoteTagChar(c: Char): Boolean = c == '_' || c.isLetterOrDigit()
}
