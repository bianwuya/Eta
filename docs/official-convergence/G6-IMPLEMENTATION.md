# G6：获批 v2 的正式 UI 实施

用户在本任务明确回复「可以开始 g6 了」。批准范围和资产见 `UI-APPROVAL.md`，官方基线仍为 Eta 3.4.2 `db0004ca`，依赖 Miuix 0.9.4、COUI 0.0.1-alpha7，不升级工具链或修改基础版本号。

## 已接入的行为

- 生图独立 URL / Key / 模型目录：点击模型进入完整选择页，读取同一连接的目录，可搜索、刷新、选择；失败保留原选择，目录未列出时可手动添加。输入支持根 URL、`/v1` 和标准完整 endpoint，高级弹窗保留特殊路径覆盖。选择仍在当前草稿，只有保存后启用；保存不产生生图请求。API Key 仍由原 `ImageGenCredentials` 加密存储，不进入导航状态和 profile JSON。
- 子代理模型优先级：官方 Preference 行增加 48dp 长按拖动柄，松手原子更新现有 `modelPriority`，取消不保存，提供上移/下移可访问性动作。原子操作只改此字段，不以旧 UI 快照覆盖其他设置。按当前连接的已启用工具模型列出；运行期仍由 G3 的同账号能力/协议安全策略过滤，已运行实例不切换。
- 设置汇总与实例详情：直接读取既有有界 `SubagentStatsStore`，显示真实输入/输出/缓存，缺失显示未知；实例按子代理 ID 过滤，最新模型由同一记录取出。原 Inspector 的步骤、任务信息和历史入口保留。统计详情页增加 COUI 分支，Miuix 沿用原控件。
- 导航与圆角：全局 `AgentAppRoot` 维持既有官方 `NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius())`。生图选择页的局部官方导航同样传设备半径，未添加固定生产半径或自制全局转场。测试宿主没有物理圆角，导航截图明确使用 32dp 设备夹具。
- 引导：G6 不改 G2/G3 已实现运行语义；子代理继续原实例，主代理在完整工具协议边界立即处理引导，返回结果恰好一次。定向与最终全量回归包含真实 Messenger 链。

## 复用与必要修改点

| 目标 | 官方入口 / API | 最小增量原因 |
| --- | --- | --- |
| 生图页面与模型目录 | `MiuixScaffoldPage` / `CouiLazyScaffoldPage`、`BasicComponent` / `CouiPreference`、`Card` / `Group`、原文本框与按钮 | 官方无独立生图连接；仅本地页面组合现有控件 |
| 模型选择与高级弹窗 | 官方 `NavDisplay`、`WindowDialog` / `CouiConfirmDialog`、`EtaPreferenceGroupItem` | 目录与长列表需要二级页面；特殊路径沿获批弹窗 |
| 排序 | 官方 `SubagentSettingsScreen` 的 Card / Group 和 Preference 行 | 原生页面没有拖动；局部 Compose 长按手势与已有配置字段，未加依赖或第二调度器 |
| 持久化 | `SettingsDataStore.dataStore.edit`、`RuntimeConfigRepository` | 仅在同一事务更新 `modelPriority`，避免覆盖并发统计/思考设置 |
| 汇总、明细 | 原 `SubagentStatsStore`、`SubagentCacheTotals`、`SubagentInspectorContent` | 同源统计需要在已批准页面显示，COUI 使用已有原控件 |

本阶段触及 5 个已有官方路径：SettingsDataStore、RuntimeConfigRepository、SubagentSettingsScreen、SubagentActivitySheet、既有 steering 测试。必要性如上；其余修改集中个人 local 目录。未改全局主题、导航主入口、Runtime 状态机、上游 main 或稳定 local。

## 动画合同

- 页面：独立连接 → 模型选择 → 返回。使用 Miuix 0.9.4 NavDisplay 的既有 motion/裁剪；不新增统一时长，保留原返回和偏好控制。草稿保留在同一设置 composition。定向验证进入/返回初中终帧及进入被返回打断后再进入。
- 弹窗：高级 endpoint 与手动模型使用各主题原 Dialog；取消不写草稿，确认后才更新草稿，保存连接才落盘。
- 排序：长按右柄 → 根据实际行高交换位置并跟随手指 → 松手写原顺序；取消还原。单个列表手势不引入外部调度或后台网络请求。长列表边缘自动滚动和实体手机 TalkBack 手势不在当前宿主验证范围。

## 测试与证据解释

`runs/g6-acceptance-03`：7 suites / 33 tests / 0 failed / 0 errors / 0 skipped，源码树 `78324fd89c388e8f9f6052d555197a379fc377bd`。真实生产 Compose、原 Runtime 候选配置、真实长按 MotionEvent 与既有主子代理引导链均执行。图片在任务 `ui-validation/acceptance-03`；最终全量与签名候选回执另见 G6 最终验证报告。

新增 UI 验证使用 native Robolectric Canvas，不是实体 Android 设备。软件宿主关闭既有不支持的 contour/squircle shader；测试假凭据存储通过默认值保持原加密实现的注入点提供，未替换生产 Keystore。逐帧绘制是必要的：仅 measure/layout 不能触发 NavDisplay 的 draw-time observation。弹窗输入定位必须选当前窗口，不能写底层搜索框。

并发 steering 测试原断言将单/双子代理都固定成 3 次主请求；两子代理先后完成时合法为 4 次。修订用独立闩锁确保先完成一个且另一个仍活跃，再验证每个结果只有一次、父运行未结束、原子请求未取消/重启；没有放宽生产规则或删除唯一性断言。

手机 IME、物理系统返回/OEM圆角、长列表边缘滚动、TalkBack 与动画帧率尚未设备实测；缓存证据只证明客户端身份/前缀和 usage 真实显示，不代表真实服务端费用/命中率。本任务仍不安装、不 push、不合并稳定分支、不改密钥、不发付费请求。
