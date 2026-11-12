# G5 v2 与子代理引导修复

用户反馈：生图模型不应只手填；模型优先级应通过前端拖动更改；导航过渡圆角丢失；派发子代理后仍应能用引导唤醒主代理。用户随后明确选择：**子代理继续执行，主代理立即响应**。

## 结果与范围

- 生图底层新增独立模型目录读取，复用现有 `RemoteModelFetcher` 的 HTTP、取消、大小限制和模型解析。用独立 URL / Key 请求 `GET /v1/models`，按返回模态与已知图像模型命名筛选，不建立聊天供应商或触发生图 POST。未声明模态且无法识别的目录项仍可通过手动添加使用；列表出现不能保证服务端支持 Images 协议，不能静默选用模型或付费探测。
- 生图预览改为模型选择页，保留刷新列表和目录未列出时的手动添加入口。正式设置页尚未接线，不声称已在 APK 生效。
- 子代理优先级预览增加 48dp 拖动柄、长按拖动与上移/下移可访问性操作。测试实际向 Compose 注入 down / 长按 / move / up，验证 Miuix / COUI 均由 high→low 改成 low→high。此阶段只改测试目录，最终保存到已有 `modelPriority` 的业务接线仍待视觉批准。
- 导航缺角来自 v1 预览 `NavDisplayEffects()` 未传半径，Miuix 0.9.4 默认 `cornerClipRadius=0dp`。正式 `AgentAppRoot` 已传 `rememberNavSystemCornerRadius()`；本轮未改全局业务导航。v2 沿同一官方参数，Robolectric 无物理圆角时仅用标注的 32dp 设备 fixture，提供实际 NavDisplay 中间帧。它与 contour shader 是否启用是两件事，不能用软件 shader 限制掩盖漏传参数。
- 主代理循环原来在单个子代理同步执行或多个 `awaitAll` 处阻塞，引导仅入队，直到子代理全部结束才消费。新逻辑在等待期间通过已有 Controller condition 唤醒：已完成工具协议结果先落盘，仍运行子代理返回明确 running receipt，主代理在协议安全边界消费引导；子代理原实例继续执行。完成结果随后在同一 run 完整轮次边界追加一次。没有取消/重发子请求，不改变 session/cache 身份，不引入 Mailbox/DecisionBroker/TaskGroup 或第二套持久任务表。

主代理响应后保持 run 活跃等待子代理或下一条引导；因此后续引导仍可唤醒。只有用户或主代理明确取消才停止指定子代理。运行进程丢失后，running receipt 在既有恢复边界变为 `SUBAGENT_INTERRUPTED / unknown`，不假称后台还在执行、不自动重派。自然完成且没有引导时仍等待并按调用顺序汇总原结果。

## 验证证据

- `runs/steering-wake-red`：新增用例在修复前失败，主代理在子代理完成前未收到引导。
- `runs/steering-wake-green`：3 suites / 13 tests / 0 failed，覆盖单个/两个子代理、原官方并行汇总与取消边界。
- `runs/review-v2-02`：5 suites / 16 tests / 0 failed，包含真实拖动、模型目录假 HTTP 与 wake 用例。
- `runs/revision-v2-full`：**439 suites / 2733 tests / 0 failed / 1 既有 shader skip**；Lint 0 errors / 438 warnings。
- `runs/revision-v2-ipc`：3 suites / 4 tests / 0 failed，真实 Messenger → Runtime Session → 原 SubagentExecutor；子请求未结束时引导已被主代理消费，原子请求没有取消或重启。此运行另有 Lint 成功回执。
- 最终源码与新增集成用例共同验证：`runs/revision-v2-final` 为 **440 suites / 2734 tests / 0 failed / 1 既有 shader skip，exit 0**，Lint **0 errors / 438 warnings**。源码树 `f715867b6bbafb9409ceb8c64ec73787f67b96b3`，archive SHA256 `ac3a2dfd57b656cc7429ca0ef8e2c4dac6f156cd60f26ea618aa0478ce13ec02`，1367 文件同步校验 0 差异，普通用户 `eta-test`。完整回执见该运行的 `summary.json` / `source.json` / `source-byte-check.json` / `host-user.txt` / `lint/`。早期日志不能替代最终回执。

目录只在 127.0.0.1 假 HTTP 测试，无真实 URL/Key、模型请求、生图或账号改动。目录失败（401/403/404/429/500）、重定向、不支持目录都不伪造模型或自动生图。取消继承现有 Controller 绑定。

## 预览状态

v1 保留为历史，v2 仍为 **REVIEWING / WAITING_UI_APPROVAL**，未收到用户视觉批准。本轮用户确认的是运行语义「子代理继续、主代理响应」，不能当作对所有页面或 G6 的批准。

真实 Compose 页面、模型选择、拖动帧、带圆角进入/返回帧见 `UI-REVIEW.md` 与任务 `ui-preview/v2`。未安装 APK，未测手机触摸、键盘、实际系统圆角半径、动画中断/性能；不得称真机通过。只修改被指出的三处预览，其他页面未重绘。
