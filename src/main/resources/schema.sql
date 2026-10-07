-- my-agent 骨架库表(MVP 用 TEXT 承载 JSON,量产后再改 jsonb)
-- 对应实体:com.myagent.team.entity.*

CREATE TABLE IF NOT EXISTS tbl_team (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    lead_sn     VARCHAR(64)  NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tbl_team_member (
    id            BIGSERIAL PRIMARY KEY,
    team_id       BIGINT NOT NULL REFERENCES tbl_team(id),
    agent_sn      VARCHAR(64)  NOT NULL,
    role          VARCHAR(16)  NOT NULL DEFAULT 'MEMBER',   -- LEAD | MEMBER
    display_name  VARCHAR(128),
    system_prompt TEXT,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | PAUSED
    created_at    TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_member_team ON tbl_team_member(team_id);
CREATE INDEX IF NOT EXISTS idx_member_sn   ON tbl_team_member(agent_sn);

CREATE TABLE IF NOT EXISTS tbl_team_task (
    id            BIGSERIAL PRIMARY KEY,
    team_id       BIGINT NOT NULL REFERENCES tbl_team(id),
    subject       VARCHAR(256) NOT NULL,
    description   TEXT,
    owner_sn      VARCHAR(64),                              -- 空 = 未分配
    blocked_by    TEXT,                                     -- JSON 数组,如 "[3,7]"
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',   -- PENDING|IN_PROGRESS|COMPLETED|DELETED
    created_by_sn VARCHAR(64),
    created_at    TIMESTAMP,
    updated_at    TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_task_team_status ON tbl_team_task(team_id, status);
CREATE INDEX IF NOT EXISTS idx_task_owner       ON tbl_team_task(owner_sn);

CREATE TABLE IF NOT EXISTS tbl_mailbox (
    id         BIGSERIAL PRIMARY KEY,
    team_id    BIGINT NOT NULL REFERENCES tbl_team(id),
    agent_sn   VARCHAR(64) NOT NULL,
    msg_type   VARCHAR(24) NOT NULL DEFAULT 'MESSAGE',      -- MESSAGE|TASK_ASSIGN|IDLE_NOTIFY|TASK_FAILED|SHUTDOWN_REQ
    priority   INT NOT NULL DEFAULT 0,                      -- 10 = 中断替换指令
    content    TEXT,
    payload    TEXT,                                        -- JSON
    is_read    BOOLEAN NOT NULL DEFAULT FALSE,
    read_at    TIMESTAMP,
    created_at TIMESTAMP
);
-- FIFO 读取路径:priority DESC, id ASC
CREATE INDEX IF NOT EXISTS idx_mailbox_inbox ON tbl_mailbox(agent_sn, is_read, priority DESC, id);

CREATE TABLE IF NOT EXISTS tbl_team_turn (
    id                   BIGSERIAL PRIMARY KEY,
    team_id              BIGINT NOT NULL REFERENCES tbl_team(id),
    agent_sn             VARCHAR(64) NOT NULL,
    thread_id            VARCHAR(128) NOT NULL,             -- = team_{teamId}_{sn},挂 Redis 检查点
    status               VARCHAR(16) NOT NULL DEFAULT 'RUNNING', -- RUNNING|DONE|FAILED
    delivered_message_ids TEXT,                             -- JSON 数组,成功后按此精确标已读
    delivery_attempts    INT NOT NULL DEFAULT 0,
    started_at           TIMESTAMP,
    lease_expires_at     TIMESTAMP,
    finished_at          TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_turn_agent_status ON tbl_team_turn(agent_sn, status, id DESC);

-- 设置持久化(模型配置 / 团队治理参数;设置页在线修改,重启保留)
CREATE TABLE IF NOT EXISTS tbl_setting (
    skey       VARCHAR(64) PRIMARY KEY,
    sval       TEXT,
    updated_at TIMESTAMP
);

-- 会话消息持久化(dsh 式服务端历史:会话目录/历史恢复/跨端可见)
CREATE TABLE IF NOT EXISTS tbl_chat_message (
    id         BIGSERIAL PRIMARY KEY,
    agent_sn   VARCHAR(64)  NOT NULL,
    thread_id  VARCHAR(160) NOT NULL,
    role       VARCHAR(16)  NOT NULL,              -- user | assistant
    content    TEXT,
    created_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_chat_thread ON tbl_chat_message(thread_id, id);
CREATE INDEX IF NOT EXISTS idx_chat_agent  ON tbl_chat_message(agent_sn, id);

-- 项目(dsh workspace 的映射:会话的组织维度;普通对话 = 不归项目)
CREATE TABLE IF NOT EXISTS tbl_project (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    description TEXT,
    created_at  TIMESTAMP
);

-- 会话登记表:目录/项目归属/标题的权威来源(首条用户消息时创建)
CREATE TABLE IF NOT EXISTS tbl_session (
    thread_id  VARCHAR(160) PRIMARY KEY,
    agent_sn   VARCHAR(64)  NOT NULL,
    project_id BIGINT,
    title      VARCHAR(200),
    created_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_session_project ON tbl_session(project_id);
