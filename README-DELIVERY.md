# my-agent 交付说明(v0.1)

自研智能体平台第一块可运行地基:**Phoenix 引擎范式 × AionUi 团队分配机制**,
内置 Web 客户端「调度台」(交互范式参考 DeepSeek Harness Web 客户端)。

## 包内容

```
my-agent.jar          Spring Boot 4 可执行 jar(内含 Web 客户端与自动建表脚本)
sql/schema.sql        建表脚本(手动导入用;jar 启动时也会自动幂等执行)
.env.example          环境变量样例
README-DELIVERY.md    本文件
scripts/              启动/停止/打包脚本
```

## Windows 免装 JDK 版(exe)

另提供 `my-agent-0.1.0-exe.zip`(jpackage app-image):解压后**双击 `my-agent.exe`** 即可,
自带 JRE,无需安装 JDK。数据库与 Redis 仍需外部提供(Port/DB 用同目录 `config/application.yml`
或环境变量覆盖;默认 localhost:5432/myagent + 6379)。重新打包:

```bash
mvn -DskipTests package
jpackage --type app-image --name my-agent --input dist/jpackage-input --main-jar my-agent.jar \
  --java-options "-Dspring.config.additional-location=optional:file:$APPDIR/config/" \
  --dest dist
```

## 前置要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 21+ | 运行唯一硬依赖 |
| PostgreSQL | 14+ | 建一个空数据库即可,**启动时自动建表** |
| Redis | 3.0+ | 检查点与用量去重(建议 5+,老 3.0 兼容性未充分验证) |
| 模型 API Key | 可选 | 任一 OpenAI 兼容端点;不配也能用 Mock 模式跑通全链路 |

## 三步运行

```bash
# 1. 准备数据库(任选一种)
createdb myagent                          # 或 psql -c "CREATE DATABASE myagent"
# 不建库也行?不行,库名必须存在(表会自动建)

# 2. 配置环境变量(见 .env.example,全部有默认值)
export PG_HOST=127.0.0.1 PG_PORT=5432 PG_DB=myagent PG_USER=postgres PG_PASSWORD=postgres
export REDIS_HOST=127.0.0.1 REDIS_PORT=6379
export LLM_MOCK=true                      # 演示模式,无需 Key;接真实模型时改为 false 并配 LLM_API_KEY

# 3. 启动(空库自动建表,约 10 秒)
java -jar my-agent.jar
# 浏览器打开 http://localhost:8070/
```

## 配置项(.env.example 同)

| 变量 | 默认 | 说明 |
|---|---|---|
| `PG_HOST/PG_PORT/PG_DB/PG_USER/PG_PASSWORD` | localhost/5432/myagent/postgres/postgres | 数据库 |
| `REDIS_HOST/REDIS_PORT` | localhost/6379 | Redis |
| `LLM_BASE_URL` | https://api.deepseek.com | OpenAI 兼容端点 |
| `LLM_API_KEY` | sk-demo | 模型 Key |
| `LLM_MODEL` | deepseek-chat | 模型 ID |
| `LLM_MOCK` | false | **演示模式**:脚本化 Mock 模型,无 Key 跑通全链路 |
| `server.port` | 8070 | java -jar -Dserver.port=xxx 覆盖 |

说明:模型端点/Key/治理参数也可在运行期通过 Web 界面「设置」修改并**热切换**,持久化在数据库(重启保留,启动时库内配置覆盖环境变量默认值)。

## 交付验收清单(已在本机全部验证)

- [x] 空数据库直启:9 张表自动创建,10 秒起服务
- [x] 建团队/加成员(已有 Agent + 动态组建)/花名册未读徽标
- [x] 普通对话与项目:会话目录/切换/删除/按项目过滤/归属隔离
- [x] 消息服务端历史(跨刷新/跨端)、Markdown 渲染、复制、流式停止
- [x] 信箱投递 → 唤醒 → 拆任务 → 成员执行 → 汇报 → 任务板 COMPLETED 全链路(Mock 模式)
- [x] 可靠性语义:失败保留未读 → 重投 → 超限 PAUSED → 「恢复槽位」处理积压
- [x] 设置页:模型热切换(不重启)、团队治理参数、深浅主题/字号、系统状态
- [x] 动态成员重启恢复注册;模型/治理配置重启恢复
- [x] 回归测试 7/7(SettingsControllerTest)

## 已知边界(v0.1,详见 docs/HARNESS_PARITY.md)

- 单活跃团队假设(工具层取首个 ACTIVE 团队);多团队并行需把 teamId 织入工具上下文
- spawn 治理未接审批(HITL);记忆抽取/向量检索未接(扩展点已留)
- 消息编辑/重试、工具调用过程折叠、附件/文件/终端 dock 未做
- 工作区文件系统隔离未实现(项目目前只组织会话)

## 故障排查

| 现象 | 处理 |
|---|---|
| 启动失败 `Connection refused` | PG/Redis 未起或端口不对;核对环境变量 |
| 对话报 401 | 未配真实 Key 且 Mock 未开:设置 → 模型 → 开演示模式,或配 Key |
| 页面样式/脚本像旧版 | Ctrl+F5;jar 内资源带 no-cache,正常不会发生 |
| 成员 PAUSED 不工作 | 会话头点「恢复槽位」;积压会自动处理 |
