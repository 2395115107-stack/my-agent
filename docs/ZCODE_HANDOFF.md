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

## 2026-10-03 深夜(第四轮:服务端会话历史 + Markdown,对齐 dsh 会话语义)

按 HARNESS_PARITY 建议顺序落地「会话与聊天」核心项:

1. **服务端历史**:新表 tbl_chat_message(agent_sn/thread_id/role/content);ChatStore 落库;AgentChatService 统一记录(直聊与团队回合都落,用户消息即时、助手输出随流累积完成时落)。历史成为真相源,localStorage 不再存消息(仅存当前会话指针/主题/字号)。
2. **会话目录**:侧栏"历史会话"区(选中成员时显示),标题=首条用户消息(团队回合加前缀),含条数;点击切换、悬停 ✕ 删除(删消息记录,不动图检查点)。「新对话」= 指针置空,旧会话保留在目录可切回(dsh 会话语义)。
3. **Markdown**:自写安全迷你渲染器(转义优先;代码块/行内代码/标题/粗体/斜体/列表/链接),助手消息最终态按 Markdown 渲染,流式期间纯文本。
4. **复制**:每条 user/assistant 悬停出现"复制"。

实测:跨刷新历史恢复 ✓、会话切换/删除 ✓、Markdown 各语法 ✓、复制 ✓;修复渲染竞态(transcriptToken 令牌,init 与 selectPeer 并发渲染曾导致条目翻倍)。字段备忘:membersPayload/历史接口均为 {sn|role|content};测试 7/7。

下一轮入口(见 HARNESS_PARITY.md):消息重试/编辑、工具调用过程折叠、审批 HITL、附件与文件 dock。

## 2026-10-03 深夜(第五轮:普通对话与项目)

落地用户「普通对话和项目」需求(dsh workspace 的映射):

1. **项目**:tbl_project + `GET/POST/DELETE /api/project`;侧栏顶部项目选择器(全部会话 / 普通对话 / 各项目,带会话计数),建项目对话框,删除项目时其会话自动回到普通对话。
2. **会话登记表**:tbl_session(thread_id 主键,project_id,title)成为目录/标题/项目归属的权威来源;ChatStore.ensureSession 在首条用户消息时创建(幂等);sessions() 自动导入历史遗留会话。
3. **归属语义(dsh 同款)**:会话的项目归属在创建时确定;项目上下文变化或过滤视图找不到当前会话 → 下次发送自动开新会话。实测:项目视图发的消息归项目,普通对话视图发的消息不分组,互不混。
4. 聊天请求体带 projectId(AgentProfile 新字段);团队回合 project_id = null(项目与团队是并列维度,打通属后续)。

实测:建项目/过滤/归属/删除回归 ✓;测试 7/7;前端 v=15。下一轮入口:消息重试/编辑、工具调用过程折叠、审批 HITL(见 HARNESS_PARITY.md)。

## 2026-10-04 凌晨(第六轮:插件功能,对齐 dsh 插件面)

落地用户「插件功能」需求。dsh 的 pnpm/组合包安装生态不适用于单体 jar,采用其「官方组合包」语义:随应用附带、默认关闭、无卸载,只做启停与配置。

1. **内核** `com.myagent.plugin`:`AgentPlugin` SPI(key/title/description/version/experimental/configFields/toolBeans)、`PluginService`(启停/配置校验/持久化到 tbl_setting 的 `plugin.states`/管理视图与只读清单投影;写操作先校验后落库,失败不动运行态)、`AgentRebuildService`(状态变更成功后热重建全部 Agent)。
2. **工具注入**:`AbstractTeamAgent.build()` 与 `SpawnService.registerMember` 把启用插件的 @Tool 对象并入 methodTools(全局作用域 = 所有 Agent);启停/改配置即时重建,进行中的回合持旧引用跑完(与模型热切换同语义)。静态 bean 各自 `rebuildAndRegister`,`SpawnService.rebuildAllSpawned` 跳过静态 sn 防止用成员工具集覆盖 Leader。
3. **内置插件 3 个**:plugin-datetime(时区可配)、plugin-calculator(自写递归下降四则求值器)、plugin-web-fetch(标实验性;超时/长度上限可配;SSRF 门禁:仅 http/https、拒绝本机/内网地址、重定向逐跳复检、响应体 512KB 截断)。
4. **API**:GET /api/plugins(管理视图)、POST /{key}/enable|disable、PUT /{key}/config、GET /api/plugins/inventory;未知 key 404、非法配置 400。
5. **前端**(css v=14 / js v=17):侧栏「❖ 插件」页 = 官方卡片网格 + 启停 toggle + 详情页(状态行/工具清单/按 configFields 渲染的配置表单);设置「内置插件」= 只读清单(状态点、实验性/已停用/启动失败标签、跨标题/说明/工具/标识搜索、刷新);普通启用不标标签(dsh 语义)。

实测:`mvn test` 28/28(新增 PluginServiceTest 8、PluginControllerTest 7、BuiltinPluginsTest 6);API 序列:非法时区 400、启停 200、改配置持久化、404、清单状态投影 ✓;浏览器实测:插件页卡片/详情/配置保存(UI 改时区 → 服务端确认 Asia/Shanghai)/Esc 关闭/停用 toast/清单搜索「调研」命中网页抓取 ✓;Mock 直聊冒烟 ✓;**跨重启恢复 ✓**(启用计算器 → 重启 → ACTIVE 保持,随后恢复全关默认)。

运行实例:8070,PID 29336,日志 tools/app31.log;模型 Mock 开(库内),插件当前全部停用(出厂态)。

⚠️ 本轮与另一并行会话的「消息编辑/重试」改动在 app.js 同文件交错进行(其 beginEdit 在本轮结束时仍未见定义,该功能未完成不影响插件面);README 的插件章节与 HARNESS_PARITY 插件行已同步更新。

## 2026-10-04 凌晨(第七轮:Codex 客户端实用能力移植)

用户需求:「看看我们集成的客户端和codex的客户端有什么区别,实用的都抄过来」。对照 OpenAI Codex CLI 0.16x 客户端(官方 slash-commands/getting-started 文档 + releases)逐项比对:采纳 8 项(排队输入、Esc 中断、编辑/重试+fork、↑/↓ 草稿历史、/ 命令面板、@ 成员引用、Ctrl+R 历史搜索、/copy+Ctrl+O);搁置 5 项(审批 /permissions、/plan、/compact、图片粘贴、! shell——分别依赖后端 HITL 审批契约、plan 模式、上下文压缩、多模态模型、PTY 终端,均不在当前后端能力内,dsh 对齐清单已含这些后续项)。

后端(2 个新接口):
1. **POST /api/agent/sessions/fork**(ChatStore.forkSession):复制源会话前 keep 条消息并登记新会话(项目归属跟随源);同时**按字节复制图检查点** —— RedisSaver 的 meta hash(`thread_id` 字段 → 内部 id)与 `graph:checkpoint:content:{内部id}` 检查点链均走 Redisson 默认 codec,fork 用同 codec get/set 复制到新内部 id 并登记 meta/reverse(`is_released` 与 saver 一致写字符串 `"false"`)。fork 后两会话检查点链独立分叉,互不串写。
2. **GET /api/agent/sessions/search?q=**:tbl_chat_message ILIKE(转义 `%_\`),返回会话标题/成员/角色/片段,最近在前;前端结果限定当前团队花名册。

前端(css v=15 / js v=19):
- **排队输入**(Codex Tab 语义):对话模式流式期间发送自动入队,queueBar 显示 chip(可单个移除),回合结束 flushQueue 依序自动发送;信箱投递不受影响。
- **编辑/重试 → fork**(Codex Esc×2 语义):每条历史用户消息悬停出现「编辑/重试」;提交后 POST fork、切换到新会话重发,原会话保留在目录。**修复一处初版缺陷**:重试模式空输入 Enter = 重发原文本(初版空文本被 send() 提前 return 吞掉,浏览器实测发现后修复)。
- **Esc 分层**:插件页 > 设置 > 搜索浮层 > 候选面板 > 编辑条 > 流式中断。
- **↑/↓ 草稿历史**(输入为空或正在浏览草稿时生效,内存保存最近 30 条)。
- **/ 命令面板**:行首 `/` 过滤候选,↑↓+Enter 或点击执行;/mock 走 PUT settings 热切换。
- **@ 成员引用**:文内 @token 触发花名册候选(按 sn/显示名过滤),选中即切换会话对象并移除 token——无文件工作区,Codex @文件的对齐物。
- **Ctrl+R** 搜索浮层(300ms 防抖,结果点击跳成员+会话)、**Ctrl+O / /copy** 复制最近一条助手回复。

实测:`mvn test` 22/22(本轮 Java 改动后;并行插件轮后来补到 28,Java 未再改);fork 链路 API 级验证:keep=2 消息复制正确、fork 后检查点链独立(content 字节数 4699 → 9340 = 复制 2 检查点 + fork 回合新检查点,数值精确吻合,证实上下文延续)、源会话 meta 不受影响、search 命中;浏览器实测(127.0.0.1:8070):/ 命令过滤+Enter 执行、/search→浮层→结果点击跳会话、编辑→fork 全链路、重试空 Enter、排队条→自动 flush→清空、Esc 中断(条目记"(已停止)")、@ 成员切换、↑ 草稿恢复,全部通过。备注:IAB 后台标签页 setTimeout 被节流,流式窗口类断言改用页面内探针与确定性驱动完成。

运行实例:8070,本轮重建的 jar(含插件轮 + 本轮全部改动),日志 `tools/app31.log`;模型 Mock 开(库内,127.0.0.1:9311/stub-model)、治理 600/3/50;测试会话(codextest1/forktest1/fw8fjit/fazk209)已 DELETE,模型设置已恢复 mock=true。9311 替身仍在运行,不需要可关。

⚠️ 并行会话交接注:上一轮(插件)记录「另一会话的 beginEdit 未见定义」——本轮已完成该功能并全量实测,app.js 现为两轮合并后的最终态(js v=19,与源文件 md5 一致),无遗留冲突。两轮改动均未提交 git,留给用户统一核验提交。

### 第六轮补充(同日,应用户要求调整)

用户要求「把插件放进设置里,不要直接显示出来」:已移除侧栏「❖ 插件」入口与独立插件浮层页,插件管理完整收纳进**设置 → 「插件」**——卡片启停开关 + 「配置」在卡片内行内展开(工具清单/配置表单/保存),展开态在重渲染后保持;原「内置插件」只读清单与管理视图合并为一(管理视图含清单全部信息)。前端 css v=16 / js v=20;后端 API 不变。浏览器实测:侧栏无插件入口 ✓、设置内三卡片渲染/行内展开/改时区保存/启用计算器(高亮+无标签,dsh 语义)✓,验证后已恢复全关出厂态。运行实例:8070,PID 18636,日志 tools/app32.log。

## 2026-10-07(交付轮)

达成「能够交付」并完成交付物独立验证:

1. **启动自动建表**:schema 移入 jar(resources/schema.sql)+ `spring.sql.init.mode=always`(全部 IF NOT EXISTS 幂等);空库 `java -jar` 直启,9 张表自动创建,约 10 秒起服务。
2. **全新库交付验证**:myagent_delivery 空库 → 直启 → 建项目/mock 直聊/建团队/挂成员/投目标 → 任务 COMPLETED 全链路 → 清理(库已删)。
3. **交付物打包**:`scripts/package-dist.sh` → `dist/my-agent-0.1.0.zip`(jar + README-DELIVERY + .env.example + sql_schema + start/stop 脚本)。
4. **交付物独立验证**:zip 解包到临时目录,按交付说明对另一空库直启 → 自动建表 + 建项目 + mock 直聊全通。交付物不依赖工作区。
5. 交付默认配置:DeepSeek 端点 + Mock 开(演示即用);插真实 Key 在设置页热切换即可。

运行状态:8070(工作区实例,myagent 库);交付物:dist/my-agent-0.1.0.zip。前端 v=16;静态资源 no-cache 头已由 StaticCacheConfig 统一设置(注意:测试期间发现侧栏多了「插件」入口,系并行会话添加,未改动)。

## 2026-10-07(交付收尾轮:exe + GitHub 公开)

1. **Windows exe**:jpackage app-image(dist/my-agent/ 含 my-agent.exe + 自带 JRE,约 240MB;zip 为 dist/my-agent-0.1.0-exe.zip)。exe 冒烟通过:配合同目录 config/application.yml(指向本机 PG/Redis)启动,数据与服务正常。无 WiX,未产 msi/exe 安装器,需要时再补。
2. **GitHub**:my-agent 仓库(接管会话所建,此前私有)已推送本轮全部改动(commit 09841aa,含并行会话的 plugin 模块),并已转 **PUBLIC**:https://github.com/2395115107-stack/my-agent 。转公开前做过敏感扫描(无真实 Key/口令泄漏,仅文档中的本地默认口令示例)。dist/ 已加入 .gitignore(二进制 240MB 不进库)。
3. 测试:全量 28 个用例(SettingsControllerTest 7 + Plugin 系列)全绿。
