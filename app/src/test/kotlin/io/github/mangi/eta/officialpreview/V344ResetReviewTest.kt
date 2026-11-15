package io.github.mangi.eta.officialpreview

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.local.codexusage.*
import io.github.mangi.eta.local.imagegen.ImageGenPage
import io.github.mangi.eta.local.imagegen.ImageGenGroup
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.window.WindowDialog

/** Candidate only: existing shared usage component hosted for the new image account slot. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w360dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V344ResetReviewTest(private val style: AppearanceUiStyle) {
    companion object {
        @JvmStatic @Parameters(name = "{0}") fun styles() = AppearanceUiStyle.entries.map { arrayOf(it) }
    }
    @Test fun existingSharedQuotaAndResetControlsPreviewForChatAndImageAccounts() {
        for (dark in listOf(false, true)) {
            var expanded by mutableStateOf(true)
            var confirm by mutableStateOf(false)
            ProductionUiHarness(style, dark, fontScale = 1.15f) {
                ImageGenPage("Codex 订阅", {}) {
                  item {
                   ImageGenGroup("当前固定账号") {
                    Text("Codex 订阅 · 当前固定账号")
                    Text("聊天供应商与独立生图分别绑定账号，复用以下额度组件")
                    CodexUsageContent(
                        snapshot = CodexUsage(listOf(UsageWindow("codex", 42.0, 18000, 2000000000)),
                            updatedAt = 1794585600000, resetCreditsAvailable = 2),
                        expanded = expanded, nowSeconds = 1999996400,
                        loading = false, resetting = false, pending = false, fresh = true,
                        usable = true, cacheLoaded = true, canReset = true, canRefresh = true,
                        error = null, notice = null, canManage = true,
                        onToggleDetails = { expanded = !expanded }, onRefresh = {},
                        onReset = { confirm = true }, onManageUsage = {},
                    )
                   }
                  }
                }
                if (confirm) {
                    if (style == AppearanceUiStyle.COUI) {
                        CouiConfirmDialog(visible = true, title = "重置额度",
                            message = "目标账号：当前固定账号\n将消耗 1 次重置机会，当前可用 2 次。",
                            confirmText = "确认重置", onConfirm = { confirm = false },
                            onDismissRequest = { confirm = false })
                    } else {
                        WindowDialog(show = true, title = "重置额度", onDismissRequest = { confirm = false }) {
                            Column {
                                Text("目标账号：当前固定账号\n将消耗 1 次重置机会，当前可用 2 次。")
                                MiuixDialogActions(confirmText = "确认重置", onConfirm = { confirm = false },
                                    onCancel = { confirm = false })
                            }
                        }
                    }
                }
            }.use { ui ->
                ui.capture("${style.name.lowercase()}-${if (dark) "dark" else "light"}-reset-candidate")
                ui.click("重置额度")
                ui.advance(12)
                ui.capture("${style.name.lowercase()}-${if (dark) "dark" else "light"}-confirm-candidate")
            }
        }
    }
}
