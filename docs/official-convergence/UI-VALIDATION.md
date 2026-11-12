# G6 v2 正式 UI 验证

实施 SHA：`2765b907b85c68ccf7bedf856b3fd7f76208a758`，批准记录 `UI-APPROVAL.md`；布局/官方映射与交互合同 `G6-IMPLEMENTATION.md`。

## 实际证据

- `runs/g6-acceptance-03`：7 suites / 33 tests / 0 failed，真实生产 Compose 页面；真实长按 MotionEvent 排序、取消、可访问性移动、保存/重新打开、Runtime 读取同一优先顺序。目录与模型选择保存、目录失败的手动添加、窄屏大字体/主题切换均通过。
- `runs/g6-full-01`：443 suites / 2752 tests / 0 failed / 0 errors / 1 既有 shader skip；Lint 0 errors / 437 warnings / 2 hints。当前 SHA 的应用/测试源码与该次受控快照无差异，唯一新增为实施说明文档。1378 个文件逐字节同步通过，普通用户 `eta-test`。
- 47 个实际截图/分镜资产与哈希：任务 `ui-validation/acceptance-03/ASSETS.json`，内容审核过的 v2 来源是 `ui-preview/v2/ASSETS.json`。新图是生产页，不再是 G5 中独立 fake review composition。

![正式模型选择与持久化排序](F:/eta/projects/eta-official-g0-g5-20261112/ui-validation/acceptance-03/overview.png)

![Miuix 进入/返回关键帧](F:/eta/projects/eta-official-g0-g5-20261112/ui-validation/acceptance-03/miuix-navigation-keyframes.gif)

GIF 只是实际帧的顺序展示，停留时间为便于审阅设置，不能当实时帧率测量。COUI 亦有同名分镜；初/中/终帧独立保留。

## 与获批预览的关系

- 模型选择沿用双主题官方标题/原输入框/PreferenceGroup，搜索、刷新、选中与目录未列出的手动添加保持 v2 信息层级。真实配置页额外保留已有多连接、订阅账号及尺寸/质量/背景选项，没有删除既有能力；新增独立 API 连接初始不静默选择模型。
- 拖动行沿用 v2 官方 Preference 控件和 48dp 拖动柄，实际行高用于移动阈值；本阶段新增落盘和 Runtime 接线。当前模型和历史统计分别保留，不让排序改变正在运行实例。
- 全局导航代码未改。进入/返回复用同一官方 NavDisplay 与设备半径读取；测试无物理圆角，用标注的 32dp fixture。截图可见过渡圆角，不代表已经读取用户设备半径。
- 原子代理 Inspector 保留已有步骤、信息展开与历史入口，仅增加同源用量显示。`SubagentInspectorInteractionTest` 原滚动锚点/展开交互均通过；未借 G6 重绘执行时间线。

## 明确未验证

本轮不安装 APK。截图/指针是在 Robolectric native Canvas 运行，软件宿主关闭既有不支持的 shader contour/squircle 特效。没有物理手机触摸、OEM 圆角、IME、TalkBack、系统返回手势、长列表边缘自动滚动和动画性能/帧率数据；相关项目标为未测。保留原图片查看器，完整测试含现有缩放/对话框行为，未进行手机图片手势验收。

测试使用独立 fake 模型/用量与内存凭据储存，不调用真实收费服务、账号登录或生图。生产默认仍为原 Android 加密存储；真机 Key 操作未测。

回退：在新 checkout 打开此前 `1c4d61e163b311dc8e3d1b07523c923259ea497c`（G5 v2）即可对照，不能 reset/clean 原 local/integration。可审实现检查点为 `codex/eta-official-g6-implementation-checkpoint`；最终候选包/交付验证另见 `G6-FINAL-VALIDATION.md`。
