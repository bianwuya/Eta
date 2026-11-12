# G5 集中预览 v1

状态：**REVIEWING / WAITING_UI_APPROVAL**。仅测试目录中的隔离 Compose；未接入应用页面、设置或账号。G6 未开始。

本次只审核独立生图配置、子代理设置/详情与相关弹窗、导航和列表换位。不扩展到其他页面。请一次选择「通过 / 修改局部 / 否决」，局部反馈可直接指出页面与区域。任何批准仅记录用户明确认可的范围。

## 预览

所有图片使用假 URL、假 Key、假模型与假统计。截图由 Robolectric 4.17 native graphics 绘制真实 Compose 组件；不是设备截图，也不是 HTML 仿制。

![页面总览](F:/eta/projects/eta-official-g0-g5-20261112/ui-preview/v1/overview.png)

![子代理详情](F:/eta/projects/eta-official-g0-g5-20261112/ui-preview/v1/detail-overview.png)

![关键状态](F:/eta/projects/eta-official-g0-g5-20261112/ui-preview/v1/states-overview.png)

![转场分镜](F:/eta/projects/eta-official-g0-g5-20261112/ui-preview/v1/motion-overview.png)

原始图片、分镜和逐文件 SHA256：`F:/eta/projects/eta-official-g0-g5-20261112/ui-preview/v1/ASSETS.json`。`navigation.gif` 是相同关键帧的循环展示，播放间隔为便于查看而设，不能证明实际动画速度。

## 信息层级

- 生图：连接 URL / 独立遮盖 Key / 模型为第一组；标准协议明确显示。高级完整 endpoint 为次级入口。唯一主操作是保存，保存不发起付费请求。
- 子代理设置：先看实际模型与原统计源汇总，再看人工候选顺序，最后是已有压缩/提示词配置入口。降级态同时更新实际模型、优先列表与原因。
- 子代理详情：当前模型、状态和实例 Token 优先，执行记录次之。缺失的缓存字段显示未知；已完成结果不因恢复而再次执行。

## 官方组件来源

官方依据：Eta v3.4.2 `db0004ca186a5f29d392089b622e0003a44abff1`。项目依赖 Miuix 0.9.4 / COUI 0.0.1-alpha7，未升级依赖。

| 区域 | 实际复用 API | 已读取官方同类参照 |
| --- | --- | --- |
| 页面主题/骨架 | `AgentAppTheme`、`LocalUiStyle`、`MiuixScaffoldPage` / `CouiScaffoldPage` | `ui/screens/subagent/SubagentSettingsScreen.kt`、`ui/pages/providers/ModelProviderDetailScreen.kt` |
| 标题、分组、行 | Miuix `SmallTitle` / `Card` / `BasicComponent`；COUI `Group` / `CouiPreference` / `Footer` | `ui/pages/providers/ProviderComponents.kt:ProviderSection` 的真实双样式分支 |
| 输入、主操作 | Miuix `TextField` / `TextButton` / `ButtonDefaults`；`CouiTextField` / `CouiButton` | 原供应商详情与子代理设置页；Key 使用 `PasswordVisualTransformation` |
| 弹窗 | Miuix `WindowDialog` / `MiuixDialogActions`；`CouiConfirmDialog` | `ui/components/coui/CouiControls.kt` 与原详情页 |
| 导航 | Miuix `NavDisplay` / `rememberNavBackStack` / `NavDisplayEffects` | `ui/app/AgentAppRoot.kt`，保持官方导航栈和转场 |
| 列表换位 | Compose `LazyColumn` 稳定 key + `Modifier.animateItem()` 默认 spec | 官方 `ui/screens/terminal/SharedFoldersScreen.kt` |

COUI scaffold / rows / controls 与该官方 SHA 无代码差异。Miuix scaffold 继承的两行个人长截图挂接在此保留；不冒称整页和主题都与官方逐字相同。预览包装函数仅存在于 `app/src/test/.../OfficialUiReviewTest.kt`，不新增生产组件系统。未自定颜色、字体或全局动效时长。

## 状态和动画合同

| 交互 | 已有证据 | 触发/状态与限制 |
| --- | --- | --- |
| 页面进入/返回 | `miuix-light-navigation-{start,mid,end,back-start,back-mid,return}.png` | 程序 push/pop 原 `NavDisplay`；默认动效。可逆返回分镜已生成；物理返回、手势中断和连点未实测 |
| 高级 endpoint 弹窗 | Miuix/COUI × 浅深色 `*-image-dialog-{start,mid,end,return}.png` | 原组件 show/dismiss，初始、约 8 个 16ms 虚拟帧后的中间态、稳定态；无自定 easing。IME 与外部点击未实测 |
| 排序 | `miuix-light-reorder-{start,mid,end,back-start,back-mid,return}.png` | 稳定 key 的真实 `animateItem` 位移动画；程序交换数据，未实现长按拖拽手势。最终上移/下移与拖动入口待批准后实现 |
| 启动前降级 | `coui-light-failover*.png` | 同一列表位置更新模型/原因，无装饰性动画；实际模型显示为 model-low。没有发起真实请求 |
| 加载/错误/不可用/恢复 | `*-loading.png`、`*-error.png`、`*-unavailable.png`、四张 detail | 保存中按钮禁用，认证错误不自动重试，原实例恢复与缓存未知采用假状态 |
| 适配 | 360dp 常规、320dp 窄屏、1.3 字体倍率、横屏首屏 | 使用原滚动 scaffold。大字体/横屏的下部内容需滚动；未以缩小全局字号解决 |

## 自审与未验证

已目视检查四种主题分支与详情、错误/加载、不可用/降级状态及转场分镜。修正了最初「说明已降级但当前模型仍为 model-high」的预览矛盾。保存为单个主操作，设置分组、标题、默认色彩和行布局来自官方同类页面。

软件 Canvas 不支持所需 `RuntimeShader`：预览中关闭 COUI contour glow、shader squircle 和模糊；保留真实主题与布局。因此轮廓光效、精确圆角剪裁、系统栏、真实刷新率和手势体验仍未验证。不能把本包作为真机动画/触摸验收。

键盘弹出、系统返回/返回中断、快速连点、横屏滚动、TalkBack、最小触摸区域测量、减少动画设置和真实拖拽均列入批准后的 G6 验证；本阶段不安装 APK。预览文案中的记录、状态和权限说明不代表已实现最终按钮接线。

执行与快照证据见同目录 `FINAL-VALIDATION.md`。测试通过只证明测试范围内行为；本文件与 AI 自审均不构成用户批准。
