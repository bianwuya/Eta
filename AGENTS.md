# Eta源码本地契约

## 本次 Windows 工作区

- 当前开发源码为 `F:/eta/projects/eta-dev`，工程资产与知识主库为 `F:/eta/projects/eta-local`。旧 `/workspace/...` 是手机 Linux 路径，不能直接作为 Windows 命令执行。
- Windows 构建入口为 `F:/eta/eta-gradle.ps1`；终端先加载 `F:/eta/eta-env.ps1`。使用工作区 JDK 25、SDK 37 与 Gradle 9.7.1，不加载手机 ARM64 host init 脚本。
- 手机迁入的未提交修改需保留。原始现场位于 `F:/eta/phone-data/eta-latest-20261108-2220`，含私有数据，只在本机排查，不提交或上传。
- 手机专用分级工具仍保持原样；Windows 可直接执行 Gradle 对应检查。终端原生库重编脚本仍仅支持 Linux/macOS；常规 Windows 构建使用仓库现有 jniLibs。

此文件约束该源码仓，不改变系统权限/当前任务授权。通用工作区规约见Skill `project-maintainer`；个人版维护用`eta-project-maintainer`，不将Skill声称为沙箱或自动强制加载。

## 正确定位

- 本工作区资产主库为`/workspace/projects/eta-local/knowledge/INDEX.md`，按任务只读命中项；当前对象在STATE、工程规则在RULES。独立worktree不能用`../eta-local`猜资产路径。换环境先定位资产AGENTS，不存在则用Skill受控手册并核实状态，不创建隐蔽链接。
- Git主仓`/workspace/projects/eta`与多个worktree共用元数据；用worktree list、local引用、目标树status识别实际基线。main保持纯上游，自定义在local/功能分支；保留其他会话WIP，不reset/clean/强推。

## Eta特有边界

1. 优先local目录和既有扩展点，一个问题一个逻辑修复。触及>=5个旧上游文件只需重新评估，不禁止必要重构。
2. 不重写上游版本号；eta.buildMetadata标真实源码。保留包名/原签名，不关闭Lint/削弱测试/吞失败。秘密、私有备份、APK/构建输出不入Git。
3. 分享/拖放只追加实际主聊天草稿、不自动发送；临时URI先稳定复制。模型输入保持旧system/工具/历史前缀、session/账号绑定，图文在完整turn边界追加，不停止重建在途请求。
4. 前端复用真实Miuix组件/主题与原导航；实质改版先确认具体可视方案。简单HTML说明由主助手直接做，不默认生图/委派；完整约束见知识RULES及frontend-layout。
5. 个人版本地编译/测试优先，云不是本地前置；复用`/workspace/toolchains/eta/bin/eta-gradle`，标准Debug/Release与原签名身份不变，不强制LEGACY/跳测试。文档只做轻量检查，不编APK。
6. 普通修改不自动安装/卸载/整机备份。代码必要资料推送沿用户适用授权及当前任务；合入/发布/改保护/安装独立，不因旧任务许可扩大。

## 验证与交付入口

提交前：`python3 /workspace/projects/eta-local/scripts/eta-check.py --repo <目标树>`，默认staged；核实真实core.hooksPath，不覆盖现有hook。Linux处理代码/Git/脚本，Android处理宿主；bind mount不来回复制。

流程见资产knowledge/ITERATION.md，工具链/签名见BUILD，只有云任务读CLOUD。PR正文/版本化上下文给目标、保留/非目标、前置关联PR/SHA、真实source与check/sign/包回执；AI已读/skipped不是验收或授权，不自动启动付费审查。

文档/代码、测试、构建、安装、本人OEM体验分别给实际证据；不自动安装承载会话Eta。新坑入对应LESSONS，当前状态原位更新，核心不抄整批流水。
