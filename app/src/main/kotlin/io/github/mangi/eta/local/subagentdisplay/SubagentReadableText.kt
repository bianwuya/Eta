package io.github.mangi.eta.local.subagentdisplay

import java.util.Locale

/** Display-only labels; these do not change the original event, detail, or model request. */
internal object SubagentReadableText {
    private val terminalTools = setOf("terminal", "terminal_job", "terminal_daemon", "run_command")

    // A closed set prevents an arbitrary first shell word (which could itself be a secret)
    // or a private executable path from being echoed as a command name.
    private val safeCommandNames = setOf(
        "git", "rg", "grep", "find", "ls", "cat", "sed", "read", "apply_patch",
        "gradle", "gradlew", "python", "python3", "node", "npm", "npx", "java",
        "curl", "wget", "awk", "sort", "cut", "tr", "head", "tail", "tar", "zip",
        "unzip", "mkdir", "cp", "mv", "rm", "touch", "chmod", "du", "df", "date",
        "pwd", "whoami", "echo", "printf", "kill", "ps", "adb", "am", "pm", "settings",
        "sh", "bash", "zsh", "env", "sudo", "su", "test", "true", "false",
    )
    private val commandHead = Regex("^(?:(?:/usr/bin/|/bin/|/usr/local/bin/)|\\./)?([A-Za-z][A-Za-z0-9_.+-]{0,31})(?=\\s|$)")
    // Treat shell syntax as complex even inside quotes, without trying to parse shell grammar.
    private val shellSyntax = Regex("[\\n\\r;|&<>`$(){}\\\\]")
    private val quotedPatch = Regex("^apply_patch[ \\t]+<<(['\"])([A-Za-z_][A-Za-z0-9_]*)\\1[ \\t]*\\r?\\n")
    private val gradleTask = Regex("^(?::[A-Za-z0-9_.-]+)*:?(test|assemble|lint)[A-Za-z0-9_-]*$", RegexOption.IGNORE_CASE)

    /** Terminal input is the already-redacted ToolStarted.command, never raw tool arguments. */
    fun toolTitle(toolName: String, preview: String, command: String?): String {
        if (toolName !in terminalTools) return preview.ifBlank { toolName }
        if (command.isNullOrBlank()) return preview.ifBlank { "执行命令" }
        val input = command.trim()
        val head = commandHead.find(input)?.groupValues?.get(1)
        val safeName = head?.takeIf { it in safeCommandNames }
        fun fallback() = if (safeName == null) "执行命令" else "执行命令 · $safeName"

        // This one common literal here-document is data for apply_patch, not a shell pipeline.
        // An early delimiter or trailing command makes it a compound command instead.
        if (head == "apply_patch" && isLiteralPatch(input)) return "应用代码补丁"
        if (shellSyntax.containsMatchIn(input) || !quotesBalanced(input)) return fallback()
        if (head == null) return fallback()

        val words = input.split(Regex("\\s+"))
        return when (head) {
            "git" -> gitTitle(words) ?: fallback()
            "rg" -> when {
                words.any { it == "--pre" || it.startsWith("--pre=") || quotedOption(it) } -> fallback()
                "--files" in words -> "查找文件 · rg"
                else -> "搜索文本 · rg"
            }
            "grep" -> "搜索文本 · grep"
            "find" -> if (words.any { it in findSideEffects || quotedOption(it) }) fallback() else "查找文件 · find"
            "ls" -> "列出目录 · ls"
            "cat" -> "读取文件 · cat"
            "sed" -> "处理文本 · sed"
            "read" -> "读取输入 · read"
            "apply_patch" -> fallback()
            "gradle", "gradlew" -> gradleTitle(words)
            "python", "python3" -> "运行 Python"
            "node" -> "运行 Node.js"
            else -> fallback()
        }
    }

    private val findSideEffects = setOf("-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprint0", "-fprintf", "-fls")

    private fun quotedOption(word: String): Boolean =
        word.trimStart('\'', '"').startsWith('-') && ('\'' in word || '"' in word)

    private fun quotesBalanced(input: String): Boolean {
        var quote: Char? = null
        input.forEach { char ->
            if (quote == null && (char == '\'' || char == '"')) quote = char
            else if (char == quote) quote = null
        }
        return quote == null
    }

    private fun isLiteralPatch(input: String): Boolean {
        val match = quotedPatch.find(input) ?: return false
        val delimiter = match.groupValues[2]
        val lines = input.substring(match.range.last + 1).lines()
        return lines.lastOrNull() == delimiter && lines.dropLast(1).none { it == delimiter }
    }

    private fun gitTitle(words: List<String>): String? {
        var index = 1
        // Only these options have unambiguous argument boundaries; anything else falls back.
        while (index < words.size) {
            when (words[index]) {
                "--no-pager", "--paginate", "--no-optional-locks" -> index++
                "-C" -> if (index + 1 < words.size && !words[index + 1].startsWith("-")) index += 2 else return null
                else -> break
            }
        }
        return when (words.getOrNull(index)) {
            "status" -> "查看 Git 状态"
            "log" -> "查看 Git 提交记录"
            "diff" -> "查看 Git 差异"
            "show" -> "查看 Git 详情"
            else -> null
        }
    }

    private fun gradleTitle(words: List<String>): String {
        val categories = mutableSetOf<String>()
        var index = 1
        while (index < words.size) {
            val word = words[index]
            val task = gradleTask.matchEntire(word)
            when {
                task != null -> categories += task.groupValues[1].lowercase(Locale.ROOT)
                word in setOf("--no-daemon", "--offline", "--stacktrace", "--info", "--quiet", "-q", "-i", "-s") -> Unit
                word == "--tests" && index + 1 < words.size -> index++
                else -> return "执行 Gradle 任务"
            }
            index++
        }
        return when (categories.singleOrNull()) {
            "test" -> "运行 Gradle 测试"
            "assemble" -> "执行 Gradle 构建"
            "lint" -> "运行 Gradle Lint 检查"
            else -> "执行 Gradle 任务"
        }
    }

    /** Only a marked, visible heading is classified; the thinking body is never inspected. */
    fun thinkingTitle(detail: String): String {
        val firstLine = detail.trimStart().lineSequence().firstOrNull().orEmpty()
        val heading = Regex("^\\*\\*(.+?)\\*\\*").find(firstLine)?.groupValues?.get(1)
            ?: Regex("^#{1,3}\\s+(.+?)\\s*#*$").matchEntire(firstLine)?.groupValues?.get(1)
            ?: return "思考（原文可展开）"
        val title = heading.trim()
        if (title.length !in 1..80) return "思考（原文可展开）"
        if (title.any { it in '\u4e00'..'\u9fff' } &&
            title.matches(Regex("[\\p{L}\\p{N}\\s，。！？：:、（）()·-]+"))
        ) return "思考概述：$title"

        val firstWord = title.lowercase(Locale.ROOT).substringBefore(' ').trimEnd(':', '.', '…')
        val action = when (firstWord) {
            "check", "checking", "inspect", "inspecting", "examine", "examining" -> "检查"
            "review", "reviewing" -> "审阅"
            "read", "reading" -> "阅读"
            "search", "searching", "find", "finding", "locate", "locating" -> "查找"
            "investigate", "investigating", "diagnose", "diagnosing" -> "排查"
            "analyze", "analyzing", "analyse", "analysing" -> "分析"
            "plan", "planning" -> "规划"
            "design", "designing" -> "设计"
            "implement", "implementing" -> "实施"
            "update", "updating", "modify", "modifying", "edit", "editing" -> "修改"
            "verify", "verifying", "validate", "validating" -> "验证"
            "test", "testing" -> "测试"
            "compare", "comparing" -> "对比"
            "explore", "exploring" -> "探索"
            "consider", "considering", "assess", "assessing" -> "评估"
            else -> null
        }
        return action?.let { "思考概述：$it" } ?: "思考（原文可展开）"
    }

    /** The caller supplies the display name if one exists, otherwise the configured model ID. */
    fun modelName(raw: String): String {
        val name = raw.trim()
        if (name.isEmpty() || name.length > 100 || name.endsWith("…") || name.endsWith("...") ||
            name.endsWith('-') || name.any { it.isISOControl() || it in "/\\{}<>" } ||
            Regex("(?i)(?:^|[^a-f0-9])[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}(?:$|[^a-f0-9])").containsMatchIn(name) ||
            Regex("(?i)^[a-f0-9]{24,}$").matches(name) ||
            name.all { it.isDigit() } ||
            Regex("(?i)(?:^|[^a-z0-9])(?:builtin|internal|uuid|provider[_-]?id|account[_-]?id|deployment[_-]?id|model[_-]?id)(?:$|[^a-z0-9])").containsMatchIn(name) ||
            Regex("(?i)^(?:provider|account|deployment)[_-]").containsMatchIn(name)
        ) return "模型名称未知"

        val gpt = Regex("(?i)^gpt-(\\d+(?:\\.\\d+)*)(?:-([a-z]+(?:-[a-z]+)*))?$").matchEntire(name)
            ?: return name
        val suffix = gpt.groupValues[2].split('-').filter { it.isNotBlank() }
            .joinToString(" ") { it.lowercase(Locale.ROOT).replaceFirstChar { char -> char.titlecase(Locale.ROOT) } }
        return "GPT-${gpt.groupValues[1]}" + if (suffix.isEmpty()) "" else " $suffix"
    }

    /** The source is the last reported usage, not a live recomputation of the context. */
    fun contextSummary(tokens: Int?, window: Int?, compactions: Int?): String {
        val used = tokens?.takeIf { it >= 0 }?.let(::tenThousandTokens) ?: "待上报"
        val maximum = window?.takeIf { it > 0 }?.let(::tenThousandTokens) ?: "上限未知"
        val count = compactions?.takeIf { it >= 0 }?.let { "压缩 $it 次" } ?: "压缩未知"
        return "上下文(最近) $used / $maximum · $count"
    }

    private fun tenThousandTokens(value: Int): String {
        if (value < 10_000) return value.toString()
        return String.format(Locale.ROOT, "%.1f", value / 10_000.0).removeSuffix(".0") + "万"
    }
}
