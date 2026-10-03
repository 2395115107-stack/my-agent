# ZCode 接管检查点

更新时间：2026-10-03（Asia/Shanghai）。状态：设置页这一轮已完成；已上传并核验 GitHub 私有仓库。

## 目标和授权

用户在本会话要求接管 ZCode，完成额度耗尽时的未完成工作，随后交回 ZCode 继续。当前项目是 `D:/Users/agent/my-agent`，参考仓库为 `D:/Users/agent/refs/deepseek-harness`。后续明确授权将本项目上传 GitHub 私有仓库，目标为当前已登录账号 `2395115107-stack` 下的 `my-agent`。外部发消息和生产写入不在本轮范围。

原任务：ZCode `sess_88525796-11e1-4b77-ac2e-d9f1a747e217`，标题「基于Phoenix开源项目搭建Java项目」。最新需求消息 `msg_muqz63r9_643759b1-e326-4888-8ed3-d00ec5571c25`：「deepseek客户端有的我都要有」。该轮首先实现模型、团队治理、外观、系统状态四个设置页；完整 Harness 能力仍是后续目标。

## 恢复出的进度和证据

- Java 骨架、静态 Web 客户端、Mock 团队分配链路已存在。
- 设置页代码、`tbl_setting`、模型代理和热切换已实现。原待办未同步，不能直接据其 pending 判断代码缺失。
- ZCode 最后文本 `part_muqzsesm_c9c388ea-d529-4f57-80b4-65a05071bf3c` 已记录双向热切换通过；最后工具 `part_muqzset9_c3d9f8a2-d3b5-43a1-a479-c47cd3052f90` 返回租约保存后 `900`。随后 provider 返回 `exceed quota limit`。
- 接管时实时服务：`http://localhost:8070/`，Java PID `14396`，JDK `D:/bootstrap/jdk`。实际 PG/Redis 端口是 `5433` / `6380`，与 application.yml 的默认端口不同。
- 接管时模型：Mock=true、deepseek-chat、Temperature=0.7、没有真实 Key；治理：900/3/50。PG/Redis up，1 个团队。
- 已复现：设置页保存 Temperature=0 会回到 0.7（JS 使用 `parseFloat(...) || 0.7`）；租约=0 显示保存成功，API 200 但仍为 900（未执行输入校验，后端静默忽略）。`temperature:"bad"` 返回 500。
- 源文件与运行 jar 原件已备份：`.handoff-backups/20261003-settings/`，SHA256 见该目录 manifest.json。

## 本轮步骤与验收

- [x] 在现有 Maven/JUnit 依赖中补最小设置保存回归,先确认失败。7 个用例(SettingsControllerTest)。
- [x] 修复前后端数值校验和 Temperature=0;保存失败不得提前改变运行时配置。(前端 Number() 化、后端校验 + 本轮补 status() 降级:团队数查询失败返回 null 不拖垮接口,Redis 连接 try-with-resources 关闭)
- [x] 浏览器验证模型/治理保存、外观三档、明暗主题、状态刷新及关闭(Esc 关闭、状态独立降级展示"不可用"均已通过)。
- [x] `mvn test package` 通过(7/7);本地协议替身(tools/openai-stub.js,127.0.0.1:9311)验证真实流式调用路径:热切换后直聊按 OpenAI 协议到达替身并正确回流 SSE 帧,Mock 双向切换通过,未消耗外部额度。
- [x] 重启后检查通过:无 LLM_MOCK 环境变量启动,模型配置(mock=true)从 tbl_setting 恢复,治理 600/3/50 与既有数据(1 团队)完好,未清库。
- [x] 更新 README、Harness 能力对齐清单和本检查点,保留下一步入口。

构建：`D:/Users/agent/tools/apache-maven-3.9.9/bin/mvn.cmd -s D:/Users/agent/tools/settings.xml -B -ntp test package`，JAVA_HOME=`D:/bootstrap/jdk`。应用配置权威来源为 application.yml、启动环境变量及数据库 tbl_setting；浏览器外观在 localStorage。

## 未决事项

当前未修改 ZCode 的任务数据库或会话历史，避免它的待办与应用内部状态冲突。完整 Harness 能力差距见 `docs/HARNESS_PARITY.md`，供后续继续。

## GitHub 上传结果（本会话）

- 用户明确要求上传 GitHub 私有仓库；目标为 `2395115107-stack/my-agent`，分支 `main`，仓库地址 `https://github.com/2395115107-stack/my-agent`。
- 2026-10-03 20:30（Asia/Shanghai）重新执行 `mvn test`：41 个 Java 源文件编译成功，7 个测试全部通过（0 失败、0 错误、0 跳过）。保留了期间 ZCode 对成员恢复、团队调度和客户端的最新修改。
- `.gitignore` 排除 `target/`、`.handoff-backups/`、`docs/evidence/`、运行日志和 `.env` 本机配置；便携工具、PostgreSQL/Redis 数据均在项目目录之外，不上传。
- Git 身份只配置在本项目，使用 GitHub noreply 邮箱。已创建仓库并推送；GitHub 查询确认 `isPrivate=true`、`visibility=PRIVATE`、默认分支 `main`。首次源码提交 `401f5b9d0ad4578ac775e368fbe403d0b303e37c` 已核验与远端 `main` 一致；交接记录及随后前端改动另行提交并推送。
- 上传期间同步客户端花名册字段为接口的 `sn`，补齐 Leader 自动选中的剩余引用。使用运行中接口返回的 3 名成员验证 Leader 自动选中通过，Node 前端语法检查通过；前端资源缓存版本为 `v=9`。

## 2026-10-03 晚(第二棒 ZCode)续记

- 修复 `status()` 独立降级:团队数查询失败返回 null(前端显示"不可用"),Redis 连接 try-with-resources 关闭;`SettingsControllerTest` 7/7 通过。
- 修复恢复路径类型健壮性:tbl_setting 中 temperature 为数字字符串不再 CCE;请求体数字字段兼容 Number 与字符串,非法值 400。
- 真实调用路径已用本地协议替身验证(流式 OpenAI 请求 + SSE 帧回流),Mock 双向热切换通过;未消耗外部额度。
- 当前运行:8070,PID 27508(app23 日志);模型 = Mock 开(库内持久化),治理 = 600/3/50;本地替身 127.0.0.1:9311 仍在运行,不需要可关闭。
- 前端资源版本 v=7;测试:`mvn.cmd -s D:/Users/agent/tools/settings.xml -B -ntp test`。
- 下一轮入口不变:会话目录/历史、流式停止、消息 Markdown 与基本消息操作(见 HARNESS_PARITY.md)。

## 2026-10-03 深夜(第三轮迭代:调度生命周期补全)

自用排查后修复 4 个不合理点,已全部浏览器实测:

1. **spawn 成员重启丢失**:动态成员的 Agent 是内存态,重启后"有名无实"(点击即 500)。SpawnService 启动时从花名册 system_prompt 重建注册(restorePersistedMembers);实测重启后 member-67142fd1 可正常对话。
2. **暂停后无恢复入口**:补全生命周期——REST `POST /api/team/{id}/members/{sn}/resume`、Leader 工具 `teamResumeAgent`、调度器 `kick()`(恢复后立即对账信箱积压)、客户端会话头"恢复槽位"按钮。实测:SQL 暂停 → 投递积压(未读徽标 1)→ 点恢复 → turn 派发处理积压 → 徽标清零、状态 ACTIVE。
3. **信箱积压不可见**:GET /members 改返回 membersPayload(含 unread),花名册加未读徽标;turn_done 事件后自动刷新。注意:members 字段名是 `sn`(不是实体里的 agentSn),前端已统一。
4. **流式回合不能停止**:对话模式接入 AbortController,流式期间发送按钮变红色"停止",中止后条目记"(已停止)";后端 WebFlux 检测断连自动取消订阅,直聊无 turn 生命周期故安全。用 1.5s 延迟的本地协议替身(tools/openai-stub.js)实测通过。

另修:Mock 直聊文案指向设置页开关(原提 LLM_MOCK=true)。前端资源版本 v=9(本轮实际 v=9→v=10 起过两版,以 index.html 为准)。

运行状态:8070,模型 = Mock 开(库内持久化),治理 600/3/50,替身 9311 可关。下一轮入口不变(见 HARNESS_PARITY.md):会话目录/历史、消息 Markdown、审批 HITL。
