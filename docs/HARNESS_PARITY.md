 # DeepSeek Harness 客户端能力对齐清单
 
 核对日期：2026-10-03。参考仓库：`D:/Users/agent/refs/deepseek-harness`，提交 `639ed015397290b3745d163aafe02ffee4aa3f84`（2026-09-29）。来源是该提交 `packages/client/ui-*/README.zh.md` 的功能说明；不同 dsh 构建按插件启用能力，不代表每个构建都默认启用下表全部功能。
 
 用户的「客户端有的我都要有」目标仍然有效。本轮收尾的是四个设置页和其保存、热切换、持久化验收。当前 my-agent 是 Java 团队调度器配静态 Web 客户端，完整能力对齐尚未完成。
 
 ## 当前状态
 
 | 能力与参考包 | my-agent 状态 | 还需完成 |
 |---|---|---|
 | 外壳/侧栏：`ui-layout`、`ui-sidebar` | 已有三栏、收起侧栏、新对话、底部设置 | 完整窗口/面板几何和不同模态层的键盘交互 |
 | 品牌：`ui-brand-official` | 使用 my-agent 品牌和参考字体 | 保留自有品牌，无需复制官方商标 |
 | 会话与聊天：`ui-session`、`ui-chat`、`ui-conversation` | 服务端历史(tbl_chat_message)、会话目录(切换/删除/新对话)、历史跨刷新恢复、SSE 流式 + 停止回合(Esc 同款中断)、消息 Markdown 渲染、消息复制、消息编辑/重试(fork 会话:服务端复制消息与图检查点,上下文延续、原会话保留)、输入排队(流式期间排队、回合结束自动发送)、历史搜索浮层(Ctrl+R,跨会话消息检索) | 输入引导、编辑点增量渲染等增强 |
 | 工作区：`ui-workspace`、`ui-directory-picker-browse`、`ui-directory-picker-native` | 项目分组(建/选/删,会话按项目过滤)、普通对话不分组 | 会话搜索/重命名/归档、工作区文件系统隔离、本机或浏览器目录选择 |
 | 设置外壳：`ui-settings-general`、`ui-settings` | 四页导航、遮罩/Esc 关闭、保存期间禁用、失败提示 | 首次运行引导、配置文件入口、版本行、完整焦点管理和设置 scope/schema |
 | 模型：`ui-settings-models`、`ui-model-selection` | 一个全局 OpenAI 兼容端点/Key/模型/温度；模型 chip；持久化热切换 | 多提供方目录、模型列表、逐会话模型选择和凭据引用管理 |
 | 外观：`ui-theme` | 深色/浅色、三档整体缩放，浏览器持久化和选中态恢复 | dsh 的正文专属字号设置等完整主题能力 |
 | Agent preset：`ui-agent-preset` | 可选择注册的 Agent、动态组建成员 | 预设目录、模式说明、默认任务预设和 preset 编辑流程 |
 | 子 Agent：`ui-subagent`、`ui-settings-subagent` | 花名册、信箱、任务分配、成员状态和失败通知 | 父子会话目录/续接路由、@子会话、委派深度/并行容量、成员模型选择 |
 | 权限与审批：`ui-approval`、`ui-permission-presets` | 未实现 | 权限预设、请求/响应/恢复契约、HITL 审批；动态 spawn 当前直接生效 |
 | 用户提问/计划审阅：`ui-user-questions` | 未实现 | 附着提问卡、草稿、等待/迟到回复、计划审批闭环 |
 | 计划与目标：`ui-plan`、`ui-goal` | 有团队任务板 | 独立 plan 模式、目标编辑/暂停/恢复、目标与回合状态关联 |
 | 工具输出：`ui-tool`、`ui-primitives` | 消息按文本显示，事件载荷可折叠 | 工具调用树/专属卡、Markdown/代码/数学公式、diff/read/search/网页输出 |
 | 执行轨迹：`ui-trajectory` | 简单团队 SSE 事件流 | 逐轮记录、调用树和时间概览 |
 | 后台任务与工作流：`ui-jobs`、`ui-workflow-run` | 团队分配和任务状态存在 | 后台输出列表/展开、持久化工作流节点、嵌套成员展示和恢复 |
 | 附件：`ui-attachment`，另有 `file-upload` | 未实现 | 上传、拖放、混合草稿、图片画廊/灯箱、模型侧附件内容 |
 | 文件与预览：`ui-sidebar-files`、`ui-sidebar-documentpreview` | 未实现 | 工作区文件接口及边界、文件树、代码/Markdown/图片/PDF/Office/HTML 预览 |
 | 终端：`ui-sidebar-terminal`、`ui-settings-shell` | 未实现 | PTY 会话/恢复/关闭、执行超时/输出上限与权限契约 |
 | 浏览器：`ui-sidebar-browser` | 未实现 | 受限浏览器/沙箱、页面生命周期、loopback 访问和停靠入口 |
 | 停靠布局：`ui-sidebar-right`、`ui-dockkit` | 固定任务板/事件流 tab，可开关 | 按会话恢复、多实例 tab、分栏、浮窗、导航、关闭清理 |
 | 交付与打开：`ui-deliverables`、`ui-open-in-app` | 未实现 | 改动/交付卡、review、文件链接和本机应用打开 |
 | 命令与引用：`ui-commands`、`ui-input-trigger`、`ui-reference` | / 命令面板(行首 `/` 触发候选菜单、↑↓+Enter 键盘导航;9 命令:new/copy/model/mock/theme/tasks/events/resume/search)、@ 成员引用(花名册候选,选中切换会话对象并移除 @token) | @文件/@会话(需工作区与子会话目录)、命令注册契约与扩展 |
 | Skills：`ui-skill` | 未实现 | Skill 目录/引用与执行调用卡 |
 | 插件：`ui-plugin-manager`、`ui-settings-plugins`、`ui-settings-plugin-inventory` | 设置「插件」页(卡片启停/行内工具清单与配置表单/搜索,收纳于设置抽屉不占侧栏);3 个内置工具插件默认关闭,启停热重建 Agent 工具集 | 组合包安装/卸载与 pnpm 注册表(不适用于单体 jar)、按会话/预设作用域、插件自注册配置页 slot |
 | 循环设置：`ui-settings-agent-loop` | 有团队租约/重试/唤醒批量设置 | 模型工具并行调用上限；团队调度参数不是这一设置的完整替代 |
 | 搜索设置：`ui-settings-web-search` | 未实现 | 搜索提供方 Key/端点/次数上限及实际搜索工具 |
 | 账号与日志：`ui-settings-account`、`ui-settings-session-log` | 未实现 | 账号授权/取消/退出、请求日志上传偏好；当前平台无账号体系 |
 | 快捷键：`ui-shortcuts`，另有 `shortcuts` | 输入 Enter/Shift+Enter、设置 Esc、Esc 分层关闭(插件页>设置>搜索>候选面板>编辑条)与流式中断、Ctrl+R 搜索、Ctrl+O 复制最近回复、↑/↓ 草稿历史 | 命令绑定、冲突/模态限制、可搜索快捷键速查 |
 | 反馈：`ui-message-feedback` | 未实现 | 点赞/点踩、反馈表单和失败反馈 |
 | 调度：`ui-schedule` | 未实现 | 跨会话自动任务、提醒、执行历史和取消语义 |
 | 组合基础：`ui-renderer`、`ui-slots` | 静态 HTML/CSS/JS | 按新增功能需要建立扩展契约；没有需求证明前无需照搬 React/Slot 实现 |
 
 ## 下一轮入口
 
 先读 [ZCODE_HANDOFF.md](ZCODE_HANDOFF.md) 并重新查询实际应用配置，避免重做设置页。建议先补会话目录/历史、流式停止、消息 Markdown 和基本消息操作；再实现用户提问/审批及工具事件契约，随后接文件、附件、终端和停靠面板。搜索、账号、调度等按上表逐项验收；插件面(管理页/只读清单/内置工具插件)已于第六轮落地，剩余为组合包安装生态与作用域细分。
 
 后端仍有 README 记录的 MVP 边界：单活跃团队假设、spawn 未接审批、记忆抽取未接、依赖失败传播待补。跨团队和父子会话的接口须依据 `D:/Users/nixiang-zocode/phoenix-analysis/融合架构蓝图_PhoenixxAionUi.md` 核对，再决定改动；本轮不改这些领域规则。
 
 本轮已经验证：设置输入边界、失败不改变运行态、模型配置/治理持久化、Mock 与本地 OpenAI 协议调用双向切换、外观刷新恢复。真实 DeepSeek/其他提供方的有效 Key 调用未验证；接管时没有真实 Key。
