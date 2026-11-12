# G5 集中预览 v2

本包后续状态：**APPROVED v2**，用户明确回复「可以开始 g6 了」。实施与验证见 `G6-IMPLEMENTATION.md`、`UI-VALIDATION.md`，批准来源见 `UI-APPROVAL.md`。下面保留 G5 当时的候选说明与限制；其中“尚未接线/等待审核/G6 未开始”是历史状态，不替代已获授权的 G6 实施结果。v1 图片和审核原文保留在 `ui-preview/v1` 与 `UI-REVIEW-v1.md`。

## 按本轮反馈修订

1. 生图模型默认从独立连接的模型目录选择，可搜索、刷新；只在目录未列出时手动添加。不要求先配置聊天供应商，不自动生图验证。模型选择使用完整二级页，避免目录较多时挤入小弹窗。
2. 子代理候选顺序由用户长按右侧拖动柄调整，提供 48dp 触摸区域和上移/下移可访问性动作。松手后计划写入现有 `modelPriority`；本预览只在假状态中改变顺序，不改真实配置。
3. 页面进入/返回沿官方 `NavDisplay` 与 `rememberNavSystemCornerRadius()`。v1 漏传半径，默认 0dp，确实丢了动画圆角；与 shader 光效限制分开处理。v2 无物理屏幕圆角的测试宿主使用明确标注的 32dp fixture，生产页面仍读取设备值，未修改全局导航。

![模型选择与拖动后顺序](F:/eta/projects/eta-official-g0-g5-20261112/ui-preview/v2/revision-overview.png)

![真实拖动手势关键帧](F:/eta/projects/eta-official-g0-g5-20261112/ui-preview/v2/drag-overview.png)

![进入和返回的圆角裁剪](F:/eta/projects/eta-official-g0-g5-20261112/ui-preview/v2/corner-overview.png)

[其余页面总览](F:/eta/projects/eta-official-g0-g5-20261112/ui-preview/v2/overview.png)；[详情](F:/eta/projects/eta-official-g0-g5-20261112/ui-preview/v2/detail-overview.png)；[状态](F:/eta/projects/eta-official-g0-g5-20261112/ui-preview/v2/states-overview.png)。逐文件哈希在 `UI-ASSETS.json` 与任务 `ui-preview/v2/ASSETS.json`。

## 官方组件与验证载体

官方参照仍是 Eta v3.4.2 `db0004ca`，Miuix 0.9.4 / COUI 0.0.1-alpha7，未升级。沿用 `AgentAppTheme`、`MiuixScaffoldPage` / `CouiScaffoldPage`、Miuix `SmallTitle` / `Card` / `BasicComponent` 与 COUI `Group` / `CouiPreference` / `Footer`、原文本框与按钮。来源对照见 v1 审核包与 `ProviderComponents.kt:ProviderSection`、官方 `SubagentSettingsScreen`、`ModelProviderDetailScreen`。

排序手势在预览目录局部使用 Compose `detectDragGesturesAfterLongPress`，没有另造生产组件库或全局手势。Miuix/COUI 均注入真实 MotionEvent down/长按/move/up，并断言最终顺序改变。此版本没有把程序交换数据冒充实际拖动；真实手机上的长列表边缘滚动、取消拖动、连拖与持久化仍需获批后验证。

圆角过渡直接绘制原 NavDisplay，进入/返回提供初中终帧。32dp 仅为测试设备输入，不能声称已读取用户手机圆角。原 shader contour glow、squircle 与模糊在软件 Canvas 仍关闭；实体布局、主题与导航裁剪是实际 Compose 绘制。物理返回、手势中断、IME、TalkBack、系统减少动画和性能未做真机测试。

预览使用假 URL / Key / 模型 / 用量，无真实请求。底层目录假服务器与主代理引导修复证据见 `REVISION-V2.md`，它们不代表正式页面已经发布或安装。

本轮用户已明确批准运行语义「子代理继续执行，主代理立即响应」，实现与测试已按此推进；这不等于视觉批准。当前仍请一次审核本版：**通过 / 修改局部 / 否决**。只修反馈区域，不重绘其他页面，不自动开始 G6。
