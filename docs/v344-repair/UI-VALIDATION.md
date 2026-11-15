# v3.4.4 正式页面验证

已按 2026-11-14 用户批准的额度/重置预览接入聊天供应商与独立生图固定账号区域，沿用 G6 已批准布局。最终源码身份以任务目录 `snapshots/delivery-source.json` 为准；HEAD 仍是 G6 起点，不能用 HEAD 代替当前源码树。

组件：聊天复用官方 ProviderConfigTab / ProviderConnectionFields / ProviderAccountControls，生图复用 ImageGenPage / ImageGenGroup；两个入口共用 CodexUsagePanel。Miuix 使用 TextButton、WindowDialog、MiuixDialogActions，COUI 使用 CouiButton、CouiConfirmDialog、官方 Group / Scaffold / 输入组件。未另建导航、主题或全局动画。

## 已运行

- `runs/entry-shadow-42`：16 项、0 失败。真实聊天/生图页面，覆盖 API Key 切换订阅、保存、登录结果绑定、额度 GET、固定账号确认 POST、服务端窗口结果。双主题、浅深色均执行。
- `runs/ui-native-40`：16 项、0 失败。额度与重置真实组件、账号切换与连点、当前不可用机会禁用、压缩提示词保存并重新打开。
- `runs/linux-full-20`：2868 项、0 失败、1 既有 shader 跳过。后续旧 Skill 兼容和切换专项新增后的全量结果另见任务 `DELIVERY.md`。
- `runs/theme-clock-20` 及全量：主题切换维持草稿和组合树，页面/独立窗口从 100% 缩放到 90%。测试使用自有帧时钟；没有修改产品主题逻辑。
- `runs/g6-observer-43`：22 项、0 失败。排序验收在 fixture 开始时重置旧 Room 共享观察器，避免其他测试换库后的残留 replay；实际拖动、顺序持久化和 Runtime 候选验证保留。

生产页截图：`F:/eta/01-核心开发源码/.analysis/20261114-v344-repair/ui-validation-native/`。图名含主题、浅深色、chat/image 及 usage/confirm/success。`ui-validation-final` 的压缩页截图显示保存并重开后的真实输入值。原批准素材在同任务 `ui-review-v2`，资产 SHA256 另存任务回执。

弹窗合同：点击重置打开目标账号及消耗次数确认，取消不发送；确认后同账号禁用重复提交；切换账号关闭未提交确认；已提交结果仍归原账号。沿用主题原有弹窗进出，无新增 motion spec。

## 宿主限制与未测项

截图为 Robolectric native Canvas，360dp fixture，关闭宿主不支持的 contour/squircle 特效。切换菜单专项额外 shadow 装饰阴影 CouiShadowRenderer，保留原菜单、回调和保存链；该项不证明原阴影在设备上表现。Windows API33 原生 SQLite/ICU 与加密库宿主限制已保留失败证据，Linux 执行相同源码验证。

ADB 无设备。本轮没有安装、真实浏览器登录、真实付费生图或额度兑换；OEM 圆角、IME、TalkBack、系统返回手势、物理拖拽、过渡帧率仍未测。不能据此宣称设备视觉验收。G6 原窄屏/大字体/拖拽测试继续纳入全量，但其截图不冒称本轮真机素材。

回退：使用原 G6/local 工作树对照；本次修改只在独立任务 checkout，禁止 reset/clean 原现场。候选包不得自动安装。
