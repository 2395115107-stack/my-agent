# my-agent 自研骨架 v0.1

> **交付**:见 [README-DELIVERY.md](README-DELIVERY.md)(三步运行/配置表/验收清单/已知边界);交付包构建:`bash scripts/package-dist.sh` → `dist/my-agent-0.1.0.zip`。

自研智能体平台的第一块可运行地基:**Phoenix 引擎范式 × AionUi 团队分配机制**(架构依据见
`D:/Users/nixiang-zocode/phoenix-analysis/融合架构蓝图_PhoenixxAionUi.md`,实现细节逐条对齐两份逆向报告的 `文件:行号` 证据)。
内置 Web 客户端「调度台」,交互范式参考 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 的 Web 客户端。

参考仓库:
- Phoenix(多模块智能体平台,含 phoenix-agent-core):https://github.com/liu463805737-collab/Phoenix-Agent-Java
- 注:https://gitee.com/lwj/phoenix 匿名访问 404(需登录或地址有误),已验证 GitHub 仓库与其为同一工程,本骨架以 GitHub 版为版本锚点。

## Web 客户端(调度台)

启动后浏览器打开 **http://localhost:8070/** 即可,客户端随 jar 一起交付(`src/main/resources/static/`,无前端构建步骤)。

设计语言对齐 dsh Web 客户端:中性蓝黑灰阶 + blue-500 强调 + 大圆角面板 + Montserrat 品牌字(woff2 按 SIL OFL 引入);
三栏几何:侧栏 280px(可收成 56px 图标栏)/ 中栏内容列 ~748px / 右栏 340px 停靠面板。

| 区域 | 内容 | 对应 dsh 概念 |
|---|---|---|
| 侧栏 | 品牌行、新对话、团队列表、成员会话(状态点/Lead 徽章)、底部「加入成员」 | brand row / New Session / Sessions / Settings seat |
| 中栏 | 会话头(成员名·sn·状态)+ 消息流(用户气泡右、助手平铺左、信箱投递虚线卡、系统错误红卡)+ 底部大圆角输入卡(模式切换:对话 / 投递到信箱) | conversation / composer |
| 右栏 | 停靠 tab:任务板(编号/标题/owner/状态徽章)、事件流(task_changed / turn_failed / agent_status_changed / teammate_message 实时推送,未读计数徽标) | right sidebar dockkit tabs |

交互说明:
- **对话模式**走 `POST /api/agent/chat`(SSE 流式,{content,end} 帧);**投递到信箱**走 `POST /api/team/{id}/messages`(分配即唤醒纪律)。
- 「新对话」= 给当前成员换 sessionId(服务端记忆按 threadId 隔离);本地会话记录存 localStorage。
- 成员失败重试、槽位 PAUSED 等可靠性语义全部经由事件流实时可见(含 `turn_failed` 事件,对应蓝图 6.2 的最小实现)。

输入交互(对齐 OpenAI Codex 客户端的实用集,2026-10-04):**排队输入**(回合流式期间继续发送,自动排队并在回合结束后依序发出)、**编辑/重试**(任意历史用户消息悬停出「编辑/重试」,发送后 fork 出新会话——服务端复制消息与图检查点,上下文延续、原会话保留;Codex Esc×2 语义)、**Esc 中断**当前回合、**↑/↓ 草稿历史**、**/ 命令面板**(行首 `/`:new/copy/model/mock/theme/tasks/events/resume/search)、**@ 成员引用**(候选菜单,选中即切换会话对象)、**Ctrl+R** 历史搜索浮层(跨会话消息检索)、**Ctrl+O / /copy** 复制最近回复。

为客户端新增的后端接口:`GET /api/team`(团队列表)、`GET /api/agents`(注册表目录)、`GET /api/team/{id}/members`(含未读数)、`POST /api/team/{id}/members/{sn}/resume`(恢复暂停槽位并处理积压)、`POST /api/agent/sessions/fork`(消息+检查点复制分叉)、`GET /api/agent/sessions/search`(历史搜索)、`turn_failed` SSE 事件;启动时自动从花名册恢复动态成员注册。

### 设置页

侧栏底部「设置」或输入卡的模型 chip 打开设置面板；点击遮罩、关闭按钮或 Esc 关闭。

| 页面 | 当前行为 |
|---|---|
| 模型 | OpenAI 兼容 Base URL、API Key、模型 ID、Temperature(0–2)、Mock 开关。Key 留空保留已有值，读取接口不回显 Key。保存后无需重启，后续模型调用使用新配置，已发出的请求继续使用原实例。 |
| 团队治理 | 回合租约(整数 ≥30 秒)、投递重试上限(整数 ≥1)、唤醒批量(整数 ≥1)。保存后按后续调度读取生效。 |
| 外观 | 深色/浅色、三档整体缩放；即时生效并保存在当前浏览器，刷新和重新打开面板时恢复选中态。 |
| 系统状态 | PostgreSQL/Redis 连通性、注册 Agent、团队数、当前模型；支持刷新。 |

模型与治理配置保存在 PostgreSQL 的 `tbl_setting`，启动时覆盖对应的环境变量默认值；若首次升级已有数据库，运行现有 SchemaLoader 导入更新后的 `sql/schema.sql`（建表语句幂等）。无效输入会被页面阻止，直接调用 API 也会得到 `400` 和原因。构建模型或数据库保存失败时，已有运行时配置保持原值。

接口：`GET/PUT /api/settings/model`、`GET/PUT /api/settings/team`、`GET /api/settings/status`。

## 插件(对齐 dsh ui-plugin-manager / plugin-inventory)

插件管理收纳在**设置 → 「插件」**里,不占用侧栏入口:卡片列表(图标/版本/启停开关/「实验性 · 已停用 · 启动失败」标签),「配置」在卡片内行内展开工具清单与配置表单 —— 全部操作不离开设置抽屉;dsh 语义:普通启用不标标签。

- 内置 3 个工具插件,默认关闭;启用后其 @Tool 方法注入**所有 Agent**(全局作用域),启停/改配置即时热重建,进行中的回合继续用旧工具集跑完(与模型热切换同语义):
  - **plugin-datetime 时间与时区**:当前日期时间/星期/时区,时区可配;
  - **plugin-calculator 计算器**:四则/括号/小数表达式求值,数字计算不靠模型心算;
  - **plugin-web-fetch 网页抓取**(实验性):公开网页转正文,超时/长度上限可配;仅 http/https、拒绝本机/内网地址、重定向逐跳复检。
- 插件状态持久化在 `tbl_setting` 的 `plugin.states`,重启恢复;配置由服务端按字段模式校验(类型/边界/未知字段一律 400),先探针后落库,失败不动运行态。
- 接口:`GET /api/plugins`(管理视图)、`POST /api/plugins/{key}/enable|disable`、`PUT /api/plugins/{key}/config`、`GET /api/plugins/inventory`(只读清单)。
- 扩展方式:实现 `com.myagent.plugin.AgentPlugin` 接口并标 `@Component`,即成为随附的官方插件(dsh 的 pnpm 组合包安装生态不适用于单体 jar,故不做安装/卸载)。

完整 DeepSeek Harness 功能对齐进度见 [能力清单](docs/HARNESS_PARITY.md)；ZCode 后续继续前先读 [交接检查点](docs/ZCODE_HANDOFF.md)。

## 已实现(对应蓝图 P0 + P4 核心子集)

| 能力 | 位置 | 逆向对照 |
|---|---|---|
| Agent 注册表 + 基类自动注册 | `engine/AgentRegistry` `engine/AbstractTeamAgent` | Phoenix `AgentStaticLoader` / `AbstractReactAgent` |
| 统一调用入口(threadId=sessionId 挂 Redis 检查点) | `engine/AgentChatService` | Phoenix `ReactAgentComponent` |
| 画像注入 Hook / 用量 Interceptor | `engine/ProfileHook` `engine/UsageInterceptor` | Phoenix `AbstractCombinedDbHook` / `LoginUserAgentInterceptor` |
| SSE 帧协议 {content,end} | `web/AgentChatController` | Phoenix `ReactAgentController` |
| 任务板(分配原语 + 依赖解锁) | `team/TaskBoardService` | AionUi `task_board.rs` |
| 信箱(FIFO/优先级/成功才标已读) | `team/MailboxService` | AionUi `mailbox.rs` |
| 调度器(事件驱动+对账+lease 回收+中断) | `team/TeamScheduler` | AionUi `scheduler/` + `member_runtime.rs` |
| team_* 工具集(any/lead_only 双档位) | `team/tools/LeaderTeamTools` `MemberTeamTools` | AionUi 13 个 team_* MCP 工具 |
| 运行时 spawn 成员 | `team/SpawnService` | AionUi `provisioning.rs` |
| 团队实时事件(SSE) | `web/TeamEventController` `team/TeamEventPublisher` | AionUi 19 个 team.* 事件(最小子集) |
| 治理提示词(分配纪律/唤醒纪律/sn 纪律) | `resources/prompts/*.st` | AionUi 内嵌系统提示词(逆向提取) |
| 插件面(启停/配置/只读清单/工具注入热重建) | `plugin/PluginService` `plugin/AgentRebuildService` `web/PluginController` | dsh `ui-plugin-manager` / `ui-settings-plugin-inventory`(官方组合包语义) |

## 环境要求

- JDK 21(已验证 Temurin 21.0.12)
- 本工作区已配好全套免安装工具(`D:/Users/agent/tools/`),**无需 Docker**:
  - Maven 3.9.9(`tools/apache-maven-3.9.9`,镜像配置在 `tools/settings.xml`,走阿里云)
  - PostgreSQL 16.4 便携版(`tools/pgsql`,数据目录 `tools/pgdata`,**端口 5433**,避开本机 5432 已有实例)
  - Redis 5.0.14.1(`tools/redis`,**端口 6380**;本机 6379 是 Redis 3.0.504 老移植版,与 Redisson 4.3.1 兼容性差)
- 任一 OpenAI 兼容模型 API Key(默认按 DeepSeek 配置;启动时 `LLM_API_KEY` 必配,否则模型调用 401)
- **无 Key 演示**:`LLM_MOCK=true java -jar target/my-agent.jar` 启用脚本化 Mock 模型
  (`config/MockScriptedChatModel`),按真实工具协议驱动「拆解-分配-执行-汇报」全链路,供演示与联调。

## 启动

```bash
# 1. 起环境(Redis 6380 + PostgreSQL 5433)
bash scripts/env-start.sh

# 2. 首次:建库建表(JDBC 直连,无需 psql)
cd D:/Users/agent/tools && java -cp "repo/org/postgresql/postgresql/42.7.8/postgresql-42.7.8.jar" \
  SchemaLoader.java "jdbc:postgresql://127.0.0.1:5433/postgres" "D:/Users/agent/my-agent/sql/schema.sql"

# 3a. 配置真实模型后一键构建+启动(端口 8070)
export LLM_BASE_URL=https://api.deepseek.com   # 或 GLM/Qwen 等 OpenAI 兼容端点
export LLM_API_KEY=sk-xxx
export LLM_MODEL=deepseek-chat
bash scripts/run.sh

# 3b. 或无 Key 演示(Mock 模型)
LLM_MOCK=true bash scripts/run.sh
```

一键脚本内部等价于:`PG_PORT=5433 REDIS_PORT=6380 java -jar target/my-agent.jar`。

## 五分钟演示(一条分配链路)

```bash
# 1. 建团(自动把 Leader 写入花名册)
curl -X POST localhost:8070/api/team -H 'Content-Type: application/json' -d '{"name":"demo"}'

# 2. 把示例成员 analyst-01(启动时已注册)挂进团队
curl -X POST localhost:8070/api/team/1/members -H 'Content-Type: application/json' \
  -d '{"agentSn":"analyst-01","displayName":"analyst"}'

# 3. (另开终端)订阅团队事件流,观察分配过程
curl -N localhost:8070/api/team/1/events

# 4. 把目标投进 Leader 信箱 —— 唤醒 Leader
curl -X POST localhost:8070/api/team/1/messages -H 'Content-Type: application/json' \
  -d '{"toSn":"team-lead","content":"调研一下 weaviate 和 pgvector 的选型差异,给我一个结论"}'

# 5. 观察:Leader 被唤醒 -> 拆任务 teamTaskCreate(分配即唤醒 analyst-01)
#    -> analyst-01 开工/完工 -> 汇报回 Leader 信箱 -> Leader 被再次唤醒向用户汇报
curl localhost:8070/api/team/1/tasks
```

> Windows 终端(Git Bash/cmd)发中文 JSON 可能因编码产生 400:把 body 写入 UTF-8 文件用 `-d @body.json`,或用 PowerShell `Invoke-RestMethod`。

也可以直接与某个智能体对话:`POST /api/agent/chat {"sn":"team-lead","sessionId":"s1","message":"..."}`(SSE)。

## 已验证(2026-10-02,无 Key 下的机制链路)

- 启动:PG 连接池、Redisson 接入 6380、36 源文件编译打包、10 秒起服务 ✓
- 建团/挂成员/花名册/任务板接口 ✓;建团数据落库持久化 ✓
- 信箱投递 → 事件唤醒 Leader → turn 派发 → 模型调用(占位 Key 返回 401,符合预期)✓
- 可靠性语义:失败保留未读 → 重投(1→2→3 次)→ 超限槽位 PAUSED → 告警消息投给 Leader ✓
- SSE:`agent_status_changed: team-lead:PAUSED` 正常推送 ✓
- Redis 检查点:`graph:checkpoint:content` / `graph:thread:meta:team_1_team-lead` 按 threadId 落 Redis ✓
- 修复调度竞态:PAUSED 检查移入成员锁内并锁内重读,3 次重试后干净收敛(修复前会多派一轮)✓

## 首次编译踩坑记录(版本组合对齐 Phoenix phoenix-parent/pom.xml)

1. **ReactAgent 不在 graph-core**:`spring-ai-alibaba-graph-core` 2.0.0-M1.1 只有图引擎(RedisSaver/NodeOutput);
   `com.alibaba.cloud.ai.graph.agent.*` 在 **`spring-ai-alibaba-agent-framework`**(Phoenix phoenix-agent-core 同款依赖),两者都要引。
2. **里程碑仓库已无用**:spring-ai 2.0.0-M1 / spring-ai-alibaba 2.0.0-M1.1 均已发布到 Maven Central,`repo.spring.io/milestone` 反而 404,删掉该 repositories 声明。
3. **flex starter 的 optional 依赖**:`mybatis-flex-spring-boot4-starter` 把 `spring-boot-jdbc` 标为 optional;Boot 4 里 `DataSourceAutoConfiguration` 已迁入该模块,不显式引入则 `MybatisFlexAutoConfiguration` 全部条件不匹配 → Mapper 报 `sqlSessionFactory required`。
4. **Mapper 要标 `@Mapper`**(对齐 Phoenix phoenix-data-core 写法)。
5. **-parameters 编译参数**:未继承 spring-boot-starter-parent 时必须显式给 maven-compiler-plugin 加 `<parameters>true</parameters>`,否则 Spring Framework 7 解析不了 `@PathVariable/@RequestParam`。
6. **Jackson 2 Bean**:Boot 4 默认装配 Jackson 3(tools.jackson),代码里用的 `com.fasterxml.jackson.databind.ObjectMapper` 需自建 `@Bean`(见 `config/JacksonConfig`)。
7. **spring-ai 用纯库不用 starter**:`spring-ai-starter-model-openai` 的自动配置启动期强校验 api-key 且装配 audio/image 模型;模型由 `ModelFactory` 手工装配(对齐 Phoenix),故直接依赖 `spring-ai-openai`。
8. **受检异常**:`agent.stream(...)` 声明 `throws GraphRunnerException`,响应式入口用 `Flux.defer` 转错误信号。

## MVP 已知边界(下一步迭代项)

1. **单活跃团队**假设:`TeamToolSupport.currentTeam()` 取第一个 ACTIVE 团队;多团队需把 teamId 织入工具上下文。
2. **spawn 治理未接 HITL**:`teamSpawnAgent` 直接生效;正式版应先提案、经确认通道放行。
3. **记忆管道缺失**:ProfileHook/UsageInterceptor 是扩展点,画像表/向量抽取(蓝图 2.6/2.7)未接。
4. **依赖失败传播**:任务 FAILED 目前等 Leader 人工重排;蓝图 6.2 节的 task_failed 自动通知待实现。
5. **完整客户端能力**:当前设置页和调度台已实现，附件、文件/终端停靠、审批、完整会话管理等仍待建设，逐项见能力清单。

测试期数据重置脚本 `D:/Users/agent/tools/ResetDb.java` 会 TRUNCATE 全部团队表；本轮接管没有清库，保留了已有团队和任务。
