package io.github.mangi.eta.local.subagentdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentReadableTextTest {
    private fun terminal(command: String) =
        SubagentReadableText.toolTitle("terminal", "终端 · 执行命令 · Linux", command)

    @Test fun commonCommandsHaveConservativeChineseActions() {
        val cases = mapOf(
            "git status --short" to "查看 Git 状态",
            "git -C /workspace/project status --short" to "查看 Git 状态",
            "git --no-pager log -5" to "查看 Git 提交记录",
            "git diff -- app" to "查看 Git 差异",
            "git show HEAD" to "查看 Git 详情",
            "rg pattern /workspace/project" to "搜索文本 · rg",
            "rg -n 'private pattern' ." to "搜索文本 · rg",
            "rg --files /workspace/project" to "查找文件 · rg",
            "grep -n pattern file" to "搜索文本 · grep",
            "find /workspace/project -name foo" to "查找文件 · find",
            "ls -la /workspace/project" to "列出目录 · ls",
            "cat /workspace/project/file" to "读取文件 · cat",
            "sed -n '1,20p' /workspace/project/file" to "处理文本 · sed",
            "read value" to "读取输入 · read",
            "python3 tools/test.py" to "运行 Python",
            "node scripts/check.js" to "运行 Node.js",
            "./gradlew :app:testDebugUnitTest --tests SomeTest" to "运行 Gradle 测试",
            "gradle assembleDebug" to "执行 Gradle 构建",
            "./gradlew :app:lintDebug" to "运行 Gradle Lint 检查",
        )
        cases.forEach { (command, expected) -> assertEquals(command, expected, terminal(command)) }
    }

    @Test fun literalPatchIsRecognizedWithoutEchoingBody() {
        val patch = "apply_patch <<'PATCH'\n*** Begin Patch\n*** Add File: private-name\n+secret-body\n*** End Patch\nPATCH"
        assertEquals("应用代码补丁", terminal(patch))
        assertEquals("执行命令 · apply_patch", terminal("$patch\necho after"))
        assertEquals("执行命令 · apply_patch", terminal(patch.replace("*** End Patch", "PATCH\necho later\n*** End Patch")))
        assertEquals("执行命令 · apply_patch", terminal("apply_patch <<PATCH\n\$SECRET\nPATCH"))
        assertEquals("执行命令 · apply_patch", terminal("apply_patch something"))
    }

    @Test fun compoundsAndUnrecognizedCommandsDoNotClaimASinglePurpose() {
        val cases = mapOf(
            "git status && curl https://example.invalid/secret" to "执行命令 · git",
            "git status\nrm private" to "执行命令 · git",
            "git status | cat" to "执行命令 · git",
            "git status > output" to "执行命令 · git",
            "git status `danger`" to "执行命令 · git",
            "git status \$(danger)" to "执行命令 · git",
            "find /workspace -exec rm {} +" to "执行命令 · find",
            "find /workspace -delete\"\"" to "执行命令 · find",
            "find /workspace '-delete'" to "执行命令 · find",
            "rg --pre=processor pattern" to "执行命令 · rg",
            "rg '--pre=processor' pattern" to "执行命令 · rg",
            "rg '--files'" to "执行命令 · rg",
            "git -c alias.status=push status" to "执行命令 · git",
            "./gradlew testDebugUnitTest assembleRelease" to "执行 Gradle 任务",
            "./gradlew testDebugUnitTest customDanger" to "执行 Gradle 任务",
            "unknown-private-command --token secret" to "执行命令",
            "/home/private/git status" to "执行命令",
            "MY_SECRET=value git status" to "执行命令",
            "sh -c 'git status'" to "执行命令 · sh",
            "curl --token a-fake-secret" to "执行命令 · curl",
        )
        cases.forEach { (command, expected) -> assertEquals(command, expected, terminal(command)) }
        assertEquals("执行命令 · cat", terminal("cat 'unterminated"))
    }

    @Test fun nonterminalAndNoCommandKeepExistingPreview() {
        assertEquals("读取文件", SubagentReadableText.toolTitle("read_file", "读取文件", "git status"))
        assertEquals("终端 · 任务列表", SubagentReadableText.toolTitle("terminal_job", "终端 · 任务列表", null))
        assertEquals("执行命令", SubagentReadableText.toolTitle("terminal", "", null))
        assertEquals("查看 Git 状态", SubagentReadableText.toolTitle("run_command", "执行命令 · Android", "git status"))
    }

    @Test fun thinkingSummaryOnlyClassifiesTheMarkedVisibleHeading() {
        assertEquals("思考概述：检查", SubagentReadableText.thinkingTitle("**Checking project rules**\nBody says to delete everything"))
        assertEquals("思考概述：分析", SubagentReadableText.thinkingTitle("## Analyzing the logs\nchecking is only in body"))
        assertEquals("思考概述：验证", SubagentReadableText.thinkingTitle("**Verifying Kotlin compilation**"))
        assertEquals("思考概述：检查项目配置", SubagentReadableText.thinkingTitle("**检查项目配置**\n其它内容"))
        assertEquals("思考（原文可展开）", SubagentReadableText.thinkingTitle("This body says checking and analyzing"))
        assertEquals("思考（原文可展开）", SubagentReadableText.thinkingTitle("**Unknown heading**\nChecking configurations"))
        assertEquals("思考（原文可展开）", SubagentReadableText.thinkingTitle("I am **Checking** a later quote"))
        assertEquals("思考（原文可展开）", SubagentReadableText.thinkingTitle("**Checking unfinished"))
        assertEquals("思考（原文可展开）", SubagentReadableText.thinkingTitle("**分析 /private/path**"))
    }

    @Test fun modelNamesAreReadableWithoutLeakingInternalIdsOrInventingIdentity() {
        assertEquals("GPT-6.1 Sol", SubagentReadableText.modelName("gpt-6.1-sol"))
        assertEquals("GPT-6.1 Sol", SubagentReadableText.modelName("GPT-6.1 Sol"))
        assertEquals("自定义模型", SubagentReadableText.modelName(" 自定义模型 "))
        assertEquals("deepseek-v4.1-flash", SubagentReadableText.modelName("deepseek-v4.1-flash"))
        assertEquals("模型名称未知", SubagentReadableText.modelName("builtin-openai-gpt-6-1-sol"))
        assertEquals("模型名称未知", SubagentReadableText.modelName("235891b6-4f3c-4675-a7a5-6a9b3ccfbec1"))
        assertEquals("模型名称未知", SubagentReadableText.modelName("Custom 235891b6-4f3c-4675-a7a5-6a9b3ccfbec1"))
        assertEquals("模型名称未知", SubagentReadableText.modelName("provider-id-123"))
        assertEquals("模型名称未知", SubagentReadableText.modelName("provider-123"))
        assertEquals("模型名称未知", SubagentReadableText.modelName("UUID"))
        assertEquals("模型名称未知", SubagentReadableText.modelName("1234567890"))
        assertEquals("模型名称未知", SubagentReadableText.modelName(""))
        assertEquals("模型名称未知", SubagentReadableText.modelName("gpt-6.1-"))
        assertEquals("模型名称未知", SubagentReadableText.modelName("A".repeat(101)))
    }

    @Test fun usageKeepsZeroAndUnknownSeparateAndUsesCompactUnits() {
        assertEquals("上下文(最近) 2.5万 / 12.8万 · 压缩 0 次", SubagentReadableText.contextSummary(25_000, 128_000, 0))
        assertEquals("上下文(最近) 待上报 / 上限未知 · 压缩未知", SubagentReadableText.contextSummary(null, null, null))
        assertEquals("上下文(最近) 0 / 上限未知 · 压缩未知", SubagentReadableText.contextSummary(0, 0, -1))
        assertEquals("上下文(最近) 1 / 104.9万 · 压缩 3 次", SubagentReadableText.contextSummary(1, 1_048_576, 3))
        assertTrue(SubagentReadableText.contextSummary(100, null, 0).contains("上下文(最近)"))
        assertFalse(SubagentReadableText.contextSummary(null, 32_000, null).contains("压缩 0 次"))
    }
}
