/* my-agent 调度台客户端 —— 交互范式对齐 dsh Web 客户端:
   侧栏(品牌/新对话/会话列表/底部操作) + 会话流(用户气泡右·助手平铺左) + 右侧停靠面板(任务板/事件流) */
"use strict";

const $ = (id) => document.getElementById(id);

const state = {
  teams: [],
  teamId: null,
  agents: [],
  projects: [],
  currentProject: "all",
  selectedSn: null,
  currentSessionId: null,
  sessions: [],
  composerMode: "chat",
  addMemberMode: "existing",
  es: null,
  streaming: false,
  eventUnseen: 0,
  dockTab: "tasks",
  /* Codex 客户端同款交互:排队输入 / 草稿历史 / 编辑分叉 */
  queue: [],            // 流式期间排队的消息 {sn, text},回合结束自动发送
  drafts: [],           // 输入框 ↑/↓ 恢复的历史草稿
  draftPos: -1,
  editing: null,        // {sn, sourceSessionId, keep, text} 编辑历史消息 -> fork 新会话
  currentMsgs: [],      // 当前会话的历史消息(服务端真相,供编辑/重试定位)
};

let currentMembers = [];

/* ---------- 基础 ---------- */

async function api(path, opts = {}) {
  const res = await fetch(path, opts);
  if (!res.ok) {
    let msg = `${res.status} ${res.statusText}`;
    try {
      const body = await res.json();
      if (body.message) msg = body.message;
      else if (body.error) msg = body.error;
    } catch (_) { /* 非 JSON 错误体 */ }
    throw new Error(msg);
  }
  return res.json();
}

const post = (path, body) => api(path, {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});

const now = () => new Date().toTimeString().slice(0, 8);

function toast(text) {
  const el = $("toast");
  el.textContent = text;
  el.classList.remove("hidden");
  clearTimeout(toast.t);
  toast.t = setTimeout(() => el.classList.add("hidden"), 3200);
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

/* ---------- 会话与本地记录 ---------- */

const SESSION_KEY = (teamId, sn) => `ma.session.${teamId}.${sn}`;

/* ---------- 团队 ---------- */

async function loadTeams() {
  state.teams = await api("/api/team");
  renderTeams();
  const saved = localStorage.getItem("ma.teamId");
  const target = state.teams.some(t => String(t.id) === saved)
    ? saved : (state.teams[0]?.id ?? null);
  if (String(state.teamId) !== String(target)) switchTeam(target, { silent: true });
}

function renderTeams() {
  const box = $("teamList");
  box.innerHTML = "";
  if (!state.teams.length) {
    box.appendChild(el("span", "team-pill-hint", "还没有团队"));
    return;
  }
  for (const t of state.teams) {
    const btn = el("button", "team-pill" + (String(t.id) === String(state.teamId) ? " on" : ""));
    btn.type = "button";
    btn.appendChild(el("span", "tid", `#${t.id}`));
    btn.appendChild(el("span", null, t.name));
    btn.addEventListener("click", () => switchTeam(t.id));
    box.appendChild(btn);
  }
}

async function createTeam(name) {
  const team = await post("/api/team", { name });
  await loadTeams();
  switchTeam(team.id);
  toast(`已建团队 #${team.id} ${team.name}`);
}

function switchTeam(id, { silent = false } = {}) {
  state.teamId = id;
  localStorage.setItem("ma.teamId", String(id));
  state.selectedSn = null;
  state.currentSessionId = null;
  state.sessions = [];
  $("sessionSection").classList.add("hidden");
  if (state.es) { state.es.close(); state.es = null; }
  $("eventFeed").innerHTML = "";
  $("eventsEmpty").classList.remove("hidden");
  connectEvents();
  refreshAndRevealLead();
  renderTeams();
  renderPeer();
  renderTranscript();
  if (!silent) toast(`已切换到 #${id}`);
}

/** 刷新花名册/任务板;若尚未选中成员则自动落到 Leader(建团/切团后的合理落点) */
async function refreshAndRevealLead() {
  await refreshTeamData();
  if (!state.selectedSn) {
    const lead = currentMembers.find(m => m.role === "LEAD");
    if (lead) selectPeer(lead.sn);
  }
}

/* ---------- 花名册 / 任务 / 目录 ---------- */

async function refreshTeamData() {
  if (!state.teamId) { renderRoster([]); renderTasks([]); return; }
  try {
    const [members, tasks] = await Promise.all([
      api(`/api/team/${state.teamId}/members`),
      api(`/api/team/${state.teamId}/tasks`),
    ]);
    renderRoster(members);
    renderTasks(tasks);
  } catch (e) { console.error(e); }
}

async function loadAgents() {
  try { state.agents = await api("/api/agents"); }
  catch (_) { state.agents = []; }
  const sel = $("memberSn");
  sel.innerHTML = "";
  for (const sn of state.agents) sel.appendChild(el("option", null, sn));
}

function renderRoster(members) {
  currentMembers = members;
  const list = $("rosterList");
  list.innerHTML = "";
  $("rosterEmpty").classList.toggle("hidden", members.length > 0);
  for (const m of members) {
    const li = el("li", "session-item" + (m.sn === state.selectedSn ? " on" : ""));
    const dot = el("span", "s-dot" + (m.status === "PAUSED" ? " paused" : ""));
    dot.dataset.sn = m.sn;
    li.appendChild(dot);
    li.appendChild(el("span", "s-name", m.displayName || m.sn));
    li.appendChild(el("span", "s-sn", m.sn));
    if (m.unread > 0) li.appendChild(el("span", "s-unread", String(m.unread)));
    if (m.role === "LEAD") li.appendChild(el("span", "s-role lead", "Lead"));
    const pick = () => selectPeer(m.sn);
    li.setAttribute("role", "button");
    li.setAttribute("tabindex", "0");
    li.addEventListener("click", pick);
    li.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); pick(); } });
    list.appendChild(li);
  }
}

function renderTasks(tasks) {
  const list = $("taskList");
  list.innerHTML = "";
  const visible = tasks.filter(t => t.status !== "DELETED");
  $("tasksEmpty").classList.toggle("hidden", visible.length > 0);
  for (const t of visible) {
    const li = el("li", "task");
    li.appendChild(el("span", "task-id", `#${t.id}`));
    const main = el("div", "task-main");
    main.appendChild(el("div", "task-subject", t.subject));
    main.appendChild(el("div", "task-owner", t.ownerSn ? `owner: ${t.ownerSn}` : "未分配"));
    li.appendChild(main);
    li.appendChild(el("span", `task-status ${t.status}`, t.status));
    list.appendChild(li);
  }
}

/* ---------- 事件流(右侧停靠面板) ---------- */

function connectEvents() {
  if (!state.teamId) return;
  const es = new EventSource(`/api/team/${state.teamId}/events`);
  state.es = es;

  const handlers = {
    task_changed: (p) => { refreshTeamData(); pulseSnFromTask(p); },
    mailbox_changed: (p) => { if (p && p.startsWith("turn_done:")) refreshTeamData(); },
    teammate_message: (p) => { /* 成员回合产出全文 */ },
    agent_status_changed: (p) => { refreshTeamData(); pulseSn(p.split(":")[0]); },
    turn_failed: (p) => { refreshTeamData(); pulseSn(p.split(":")[0]); },
  };

  for (const [name, fn] of Object.entries(handlers)) {
    es.addEventListener(name, (e) => {
      const payload = JSON.parse(e.data).payload;
      fn(payload);
      pushEvent(name, payload);
    });
  }
}

function pushEvent(type, payload) {
  $("eventsEmpty").classList.add("hidden");
  const feed = $("eventFeed");
  const li = el("li", "event");
  const line = el("div", "event-line");
  line.appendChild(el("span", "event-time", now()));
  line.appendChild(el("span", `event-type ${type}`, type));
  li.appendChild(line);
  const payloadDiv = el("div", "event-payload", payload);
  payloadDiv.title = payload;
  li.appendChild(payloadDiv);
  feed.prepend(li);
  while (feed.children.length > 80) feed.lastChild.remove();

  if (state.dockTab !== "events") {
    state.eventUnseen += 1;
    const badge = $("eventBadge");
    badge.textContent = state.eventUnseen > 99 ? "99+" : String(state.eventUnseen);
    badge.classList.remove("hidden");
  }
}

function pulseSn(sn) {
  const dot = document.querySelector(`.s-dot[data-sn="${CSS.escape(sn)}"]`);
  if (!dot) return;
  dot.classList.add("pulse");
  setTimeout(() => dot.classList.remove("pulse"), 1400);
}

/** task_changed payload 形如 created:{id} / {id}:{status},拿不到 owner;仅刷新任务板 */
function pulseSnFromTask() { /* 语义保留:任务变化由 refreshTeamData 呈现 */ }

/* ---------- 会话区 ---------- */

async function selectPeer(sn) {
  state.selectedSn = sn;
  renderRoster(currentMembers);
  renderPeer();
  // 恢复该成员上次活跃会话(dsh 会话语义:服务端历史为真相源)
  state.currentSessionId = localStorage.getItem(SESSION_KEY(state.teamId, sn)) || null;
  await loadSessions(sn);
  await renderTranscript();
  $("composerInput").focus();
}

/** 会话目录:选中成员的历史会话(dsh sidebar sessions 的映射),按当前项目过滤 */
async function loadSessions(sn) {
  try {
    state.sessions = await api(`/api/agent/sessions/${encodeURIComponent(sn)}?project=${encodeURIComponent(state.currentProject)}`);
  } catch (_) { state.sessions = []; }
  // 过滤视图里找不到的会话指针视为失效(项目上下文已变),下次发送开新会话
  if (state.currentSessionId && !state.sessions.some(s => s.thread_id === state.currentSessionId)) {
    state.currentSessionId = null;
  }
  renderSessions(sn);
}

/* ---------- 项目(dsh workspace 的映射) ---------- */

async function loadProjects() {
  try {
    state.projects = await api("/api/project");
  } catch (_) { state.projects = []; }
  renderProjects();
}

function renderProjects() {
  const box = $("projectList");
  box.innerHTML = "";
  const rows = [
    { key: "all", label: "全部会话", count: state.projects.reduce((a, p) => a + Number(p.session_count || 0), 0) + "项目" },
    { key: "none", label: "普通对话", count: "不分组" },
  ];
  for (const p of state.projects) rows.push({ key: String(p.id), label: p.name, count: `${p.session_count}会话`, id: p.id });
  for (const r of rows) {
    const item = el("div", "team-item" + (state.currentProject === r.key ? " on" : ""));
    item.setAttribute("role", "button");
    item.setAttribute("tabindex", "0");
    item.appendChild(el("span", "tid", r.key === "all" ? "≡" : r.key === "none" ? "◇" : "#"));
    item.appendChild(el("span", "s-name", r.label));
    if (r.key !== "all" && r.key !== "none") {
      item.appendChild(el("span", "s-role", String(r.count)));
      const del = el("button", "sess-del", "✕");
      del.title = "删除项目(会话回到普通对话)";
      del.addEventListener("click", async (e) => {
        e.stopPropagation();
        try {
          await api(`/api/project/${r.id}`, { method: "DELETE" });
          if (state.currentProject === r.key) state.currentProject = "all";
          await loadProjects();
          if (state.selectedSn) await loadSessions(state.selectedSn);
          toast("项目已删除,其会话回到普通对话");
        } catch (err) { toast(`删除失败:${err.message}`); }
      });
      item.appendChild(del);
    } else {
      item.appendChild(el("span", "s-role", r.count));
    }
    const pick = () => {
      state.currentProject = r.key;
      renderProjects();
      if (state.selectedSn) loadSessions(state.selectedSn);
    };
    item.addEventListener("click", pick);
    item.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); pick(); } });
    box.appendChild(item);
  }
}

function renderSessions(sn) {
  const section = $("sessionSection");
  const box = $("sessionList");
  section.classList.toggle("hidden", state.selectedSn !== sn || !state.sessions.length);
  box.innerHTML = "";
  for (const s of state.sessions) {
    const item = el("div", "session-row" + (s.thread_id === state.currentSessionId ? " on" : ""));
    item.setAttribute("role", "button");
    item.setAttribute("tabindex", "0");
    item.appendChild(el("span", "sess-title", s.title || "(无标题会话)"));
    item.appendChild(el("span", "sess-meta", `${s.msgs}条`));
    const del = el("button", "sess-del", "✕");
    del.title = "删除会话";
    del.addEventListener("click", async (e) => {
      e.stopPropagation();
      try {
        await api(`/api/agent/sessions/${encodeURIComponent(s.thread_id)}`, { method: "DELETE" });
        if (state.currentSessionId === s.thread_id) {
          state.currentSessionId = null;
          localStorage.removeItem(SESSION_KEY(state.teamId, sn));
          await renderTranscript();
        }
        await loadSessions(sn);
      } catch (err) { toast(`删除失败:${err.message}`); }
    });
    item.appendChild(del);
    const open = () => switchSession(s.thread_id);
    item.addEventListener("click", open);
    item.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); open(); } });
    box.appendChild(item);
  }
}

function switchSession(threadId) {
  state.currentSessionId = threadId;
  localStorage.setItem(SESSION_KEY(state.teamId, state.selectedSn), threadId);
  renderSessions(state.selectedSn);
  renderTranscript();
}

function peerOf(sn) { return currentMembers.find(m => m.sn === sn); }

function isPaused(sn) {
  const m = peerOf(sn);
  return m ? m.status === "PAUSED" : false;
}

function renderPeer() {
  const m = state.selectedSn ? peerOf(state.selectedSn) : null;
  $("peerName").textContent = m ? (m.displayName || m.sn) : "未选择成员";
  $("peerSn").textContent = m
    ? `${m.sn} · ${m.status === "PAUSED" ? "已暂停" : "活跃"}`
    : "从左侧选择一个成员开始";
  $("peerDot").classList.toggle("on", !!(m && m.status !== "PAUSED"));
  $("btnResume").classList.toggle("hidden", !(m && m.status === "PAUSED"));
  $("composerInput").placeholder = m
    ? (state.composerMode === "chat"
      ? `和 ${m.sn} 对话…`
      : `投递给 ${m.sn} 的信箱(将唤醒它)…`)
    : "先在左侧选择一个成员";
  $("composerSend").disabled = !m;
  const mailbox = state.composerMode === "mailbox";
  $("composerSend").textContent = mailbox ? "投递" : "发送";
  $("composerHint").textContent = !m ? "先在左侧选择一个成员"
    : mailbox
      ? (m.status === "PAUSED"
        ? "该成员已暂停:消息会保留在信箱,恢复后自动唤醒投递"
        : "信箱写入即唤醒;回合成功结束后消息才标记已读")
      : "回合结束后自动汇入该会话记忆";
  if (state.streaming) {
    // 流式进行中,发送按钮此时是"停止"
    $("composerSend").textContent = "停止";
    $("composerSend").disabled = false;
  }
}

const transcriptEmptyEl = $("transcriptEmpty");
let transcriptToken = 0;

/** 会话内容:服务端历史为真相源(跨刷新/跨端可见)。token 防并发交错重复渲染 */
async function renderTranscript() {
  const token = ++transcriptToken;
  const box = $("transcript");
  box.innerHTML = "";
  box.appendChild(transcriptEmptyEl);
  const sn = state.selectedSn;
  const sess = state.currentSessionId;
  if (!sn || !sess) {
    if (token === transcriptToken) transcriptEmptyEl.classList.remove("hidden");
    return;
  }
  let msgs = [];
  try {
    msgs = await api(`/api/agent/sessions/history?sessionId=${encodeURIComponent(sess)}`);
  } catch (e) {
    if (token === transcriptToken) appendEntry("sys", "历史加载失败", e.message);
    return;
  }
  if (token !== transcriptToken || state.selectedSn !== sn || state.currentSessionId !== sess) return;
  state.currentMsgs = msgs;
  transcriptEmptyEl.classList.toggle("hidden", msgs.length > 0);
  msgs.forEach((m, idx) => {
    if (m.role === "user") appendEntry("user", "你", m.content, { copyable: true, msgIndex: idx });
    else appendEntry("agent", sn, m.content, { copyable: true, html: true });
  });
  box.scrollTop = box.scrollHeight;
}

/** 迷你 Markdown:转义优先,支持代码块/行内代码/标题/粗体/斜体/列表/链接 */
function renderMarkdown(text) {
  const esc = String(text).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  const blocks = [];
  let out = esc.replace(/```(\w*)\n?([\s\S]*?)```/g, (m, lang, code) => {
    blocks.push(`<pre class="md-code"><code>${code.replace(/\n$/, "")}</code></pre>`);
    return `\u0000B${blocks.length - 1}\u0000`;
  });
  out = out
    .replace(/^###\s+(.*)$/gm, "<h4>$1</h4>")
    .replace(/^##\s+(.*)$/gm, "<h3>$1</h3>")
    .replace(/^#\s+(.*)$/gm, "<h3>$1</h3>")
    .replace(/\*\*([^*\n]+)\*\*/g, "<strong>$1</strong>")
    .replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, "$1<em>$2</em>")
    .replace(/`([^`\n]+)`/g, "<code class=\"md-inline\">$1</code>")
    .replace(/^[-•]\s+(.*)$/gm, "<li>$1</li>")
    .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, "<a href=\"$2\" target=\"_blank\" rel=\"noopener\">$1</a>");
  out = out.split(/\n{2,}/).map(p => {
    const t = p.trim();
    if (!t) return "";
    if (/^<(h3|h4|li|pre)/.test(t) || t.includes("\u0000B")) return t;
    return `<p>${t.replace(/\n/g, "<br>")}</p>`;
  }).join("");
  return out.replace(/\u0000B(\d+)\u0000/g, (m, i) => blocks[Number(i)] ?? "");
}

function appendEntry(kind, sender, text, opts = {}) {
  transcriptEmptyEl.classList.add("hidden");
  const box = $("transcript");
  const entry = el("div", `entry entry-${kind}` + (opts.streaming ? " streaming" : ""));
  const head = el("div", "entry-head");
  head.appendChild(el("span", "entry-sender", sender));
  head.appendChild(el("span", "entry-time", now()));
  if (opts.copyable) {
    head.appendChild(actionBtn("复制", "复制内容", () => {
      navigator.clipboard.writeText(text).then(() => toast("已复制"), () => toast("复制失败"));
    }));
  }
  if (opts.msgIndex !== undefined) {
    // Codex 同款:任何一条历史用户消息都可编辑/重试,发送后从该点 fork 新会话,原会话保留
    head.appendChild(actionBtn("重试", "从这条消息分叉并重发", () => beginEdit(opts.msgIndex, true)));
    head.appendChild(actionBtn("编辑", "从这条消息分叉并修改重发", () => beginEdit(opts.msgIndex, false)));
  }
  entry.appendChild(head);
  const body = el("div", "entry-body");
  if (opts.html) body.innerHTML = renderMarkdown(text);
  else body.textContent = text;
  entry.appendChild(body);
  box.appendChild(entry);
  box.scrollTop = box.scrollHeight;
  return entry;
}

function actionBtn(label, title, onClick) {
  const btn = el("button", "entry-act", label);
  btn.title = title;
  btn.type = "button";
  btn.addEventListener("click", onClick);
  return btn;
}

function addCopyButton(entry, raw) {
  if (!raw) return;
  entry.querySelector(".entry-head").appendChild(actionBtn("复制", "复制内容", () => {
    navigator.clipboard.writeText(raw).then(() => toast("已复制"), () => toast("复制失败"));
  }));
}

/** POST /api/agent/chat 的 SSE 帧解析:data:{content,end} */
async function streamChat(sn, sessionId, message, onChunk, signal) {
  const res = await fetch("/api/agent/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      sn, sessionId, message,
      projectId: /^\d+$/.test(state.currentProject) ? Number(state.currentProject) : null,
    }),
    signal,
  });
  if (!res.ok || !res.body) throw new Error(`${res.status} ${res.statusText}`);

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = "";
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    let idx;
    while ((idx = buf.indexOf("\n\n")) >= 0) {
      const raw = buf.slice(0, idx);
      buf = buf.slice(idx + 2);
      for (const line of raw.split("\n")) {
        if (!line.startsWith("data:")) continue;
        const data = line.slice(5).trim();
        if (data) onChunk(JSON.parse(data));
      }
    }
  }
}

/** 发送入口:编辑态优先;对话模式流式期间排队(Codex Tab 语义);信箱投递即时。 */
async function send() {
  const input = $("composerInput");
  const text = input.value.trim();
  if (state.editing) {
    if (!text && state.editing.text === null) return; // 编辑模式需要新文本
    submitEdit(text || state.editing.text);           // 重试模式空输入 = 重发原文本
    return;
  }
  if (!text) return;
  if (!state.selectedSn) { toast("先在左侧选择一个成员"); return; }

  if (state.streaming) {
    if (state.composerMode !== "chat") return;
    state.queue.push({ sn: state.selectedSn, text });
    input.value = "";
    autosize(input);
    renderQueue();
    return;
  }

  const sn = state.selectedSn;
  input.value = "";
  autosize(input);
  rememberDraft(text);
  if (state.composerMode === "mailbox") {
    await sendMailbox(sn, text);
    return;
  }
  await sendChat(sn, text);
}

async function sendMailbox(sn, text) {
  appendEntry("mail", sn, text);
  pulseSn(sn);
  try {
    await post(`/api/team/${state.teamId}/messages`, { toSn: sn, content: text });
    const paused = isPaused(sn);
    toast(paused ? `已投递(${sn} 暂停中,恢复后自动唤醒)` : `已投递给 ${sn},信箱写入即唤醒`);
  } catch (e) {
    appendEntry("sys", "投递失败", `${e.message} —— 确认团队与成员状态后重试`);
  }
}

async function sendChat(sn, text, sessionIdOverride = null) {
  // 会话的项目归属在创建时确定:项目上下文变了(或无会话)就开新会话,dsh 同款语义
  const expectProject = /^\d+$/.test(state.currentProject) ? Number(state.currentProject) : null;
  const currentSession = state.sessions.find(s => s.thread_id === state.currentSessionId);
  if (sessionIdOverride) {
    state.currentSessionId = sessionIdOverride;
    localStorage.setItem(SESSION_KEY(state.teamId, sn), sessionIdOverride);
  } else if (state.currentSessionId && (!currentSession || (currentSession.project_id ?? null) !== expectProject)) {
    state.currentSessionId = null;
  }
  if (!state.currentSessionId) {
    state.currentSessionId = `web-${state.teamId}-${sn}-${Math.random().toString(36).slice(2, 10)}`;
    localStorage.setItem(SESSION_KEY(state.teamId, sn), state.currentSessionId);
  }

  appendEntry("user", "你", text, { copyable: true });
  state.streaming = true;
  state.abort = new AbortController();
  const sendBtn = $("composerSend");
  sendBtn.disabled = false;
  sendBtn.textContent = "停止";
  sendBtn.classList.add("stop");
  const entry = appendEntry("agent", sn, "", { streaming: true });
  let acc = "";
  try {
    await streamChat(sn, state.currentSessionId, text, (frame) => {
      if (frame.content) {
        acc += frame.content;
        entry.querySelector(".entry-body").textContent = acc;
        $("transcript").scrollTop = $("transcript").scrollHeight;
      }
    }, state.abort.signal);
    entry.classList.remove("streaming");
    entry.querySelector(".entry-body").innerHTML = renderMarkdown(acc);
    addCopyButton(entry, acc);
    await loadSessions(sn);
    await renderTranscript(); // 服务端为真相源,重渲染让刚完成的消息也带上编辑/重试
  } catch (e) {
    entry.classList.remove("streaming");
    if (e.name === "AbortError") {
      const stopped = acc ? `${acc}\n\n(已停止)` : "(已停止)";
      entry.querySelector(".entry-body").textContent = stopped;
      addCopyButton(entry, acc);
    } else {
      const errText = `调用失败:${e.message} —— 若为 401/连接错误,检查 LLM_API_KEY 与模型端点配置`;
      entry.querySelector(".entry-body").textContent = errText;
    }
  } finally {
    state.streaming = false;
    state.abort = null;
    sendBtn.classList.remove("stop");
    renderPeer();
    flushQueue();
  }
}

/* ---------- 排队输入(Codex:回合进行中排队,结束后自动发送) ---------- */

function flushQueue() {
  if (!state.queue.length || state.streaming) return;
  const next = state.queue.shift();
  renderQueue();
  sendChat(next.sn, next.text); // 排队项记录归属 sn,即便期间切换了成员也发到原目标
}

function renderQueue() {
  const bar = $("queueBar");
  if (!state.queue.length) { bar.classList.add("hidden"); bar.innerHTML = ""; return; }
  bar.classList.remove("hidden");
  bar.innerHTML = "";
  bar.appendChild(el("span", "queue-label", `排队 ${state.queue.length} 条(回合结束后自动发送)`));
  state.queue.forEach((item, i) => {
    const chip = el("span", "queue-chip", `${item.sn}: ${item.text.slice(0, 24)}${item.text.length > 24 ? "…" : ""}`);
    const del = el("button", "queue-del", "✕");
    del.title = "移出队列";
    del.addEventListener("click", () => { state.queue.splice(i, 1); renderQueue(); });
    chip.appendChild(del);
    bar.appendChild(chip);
  });
}

/* ---------- 草稿历史(Codex ↑/↓) ---------- */

function rememberDraft(text) {
  if (state.drafts[state.drafts.length - 1] !== text) state.drafts.push(text);
  if (state.drafts.length > 30) state.drafts.shift();
  state.draftPos = -1;
}

function draftNavigate(offset) {
  const input = $("composerInput");
  if (!state.drafts.length) return;
  if (state.draftPos === -1) {
    if (offset > 0) return;
    state.draftPos = state.drafts.length - 1;
  } else {
    state.draftPos += offset;
    if (state.draftPos < 0) state.draftPos = 0;
    if (state.draftPos >= state.drafts.length) {
      state.draftPos = -1;
      input.value = "";
      autosize(input);
      return;
    }
  }
  input.value = state.drafts[state.draftPos];
  autosize(input);
  input.setSelectionRange(input.value.length, input.value.length);
}

/* ---------- 编辑/重试 -> fork 会话(Codex Esc×2 语义) ---------- */

function beginEdit(msgIndex, retry) {
  const msg = state.currentMsgs[msgIndex];
  if (!msg || msg.role !== "user" || !state.currentSessionId) return;
  const input = $("composerInput");
  state.editing = {
    sn: state.selectedSn,
    sourceSessionId: state.currentSessionId,
    keep: msgIndex,          // 保留该用户消息之前的全部历史
    text: retry ? msg.content : null,
  };
  input.value = retry ? "" : msg.content;
  autosize(input);
  input.focus();
  renderEditBanner();
}

function renderEditBanner() {
  const banner = $("editBanner");
  if (!state.editing) { banner.classList.add("hidden"); banner.innerHTML = ""; return; }
  banner.classList.remove("hidden");
  banner.innerHTML = "";
  const tip = el("span", "edit-tip",
    `正在${state.editing.text === null ? "编辑" : "重试"}历史消息 —— 发送后将从这里分叉出新会话,原会话保留在目录`);
  const cancel = el("button", "edit-cancel", "取消(Esc)");
  cancel.type = "button";
  cancel.addEventListener("click", cancelEdit);
  banner.appendChild(tip);
  banner.appendChild(cancel);
}

function cancelEdit() {
  state.editing = null;
  renderEditBanner();
}

async function submitEdit(text) {
  const edit = state.editing;
  const sn = edit.sn;
  const newSessionId = `web-${state.teamId}-${sn}-f${Math.random().toString(36).slice(2, 8)}`;
  const title = `Fork:${(text || "").slice(0, 60)}`;
  try {
    await post("/api/agent/sessions/fork", {
      sourceSessionId: edit.sourceSessionId,
      newSessionId,
      agentSn: sn,
      keep: edit.keep,
      title,
    });
  } catch (e) {
    toast(`分叉失败:${e.message}`);
    return;
  }
  state.editing = null;
  renderEditBanner();
  $("composerInput").value = "";
  autosize($("composerInput"));
  rememberDraft(text);
  state.currentSessionId = newSessionId;
  localStorage.setItem(SESSION_KEY(state.teamId, sn), newSessionId);
  await loadSessions(sn);
  await sendChat(sn, text, newSessionId);
}

/* ---------- / 命令与 @ 成员(Codex 输入约定) ---------- */

const COMMANDS = [
  { name: "/new", desc: "当前成员开启新对话" },
  { name: "/copy", desc: "复制最近一条助手回复" },
  { name: "/model", desc: "打开模型设置" },
  { name: "/mock", desc: "切换演示模式(Mock)" },
  { name: "/theme", desc: "深色 / 浅色切换" },
  { name: "/tasks", desc: "面板切到任务板" },
  { name: "/events", desc: "面板切到事件流" },
  { name: "/resume", desc: "恢复当前成员的暂停槽位" },
  { name: "/search", desc: "搜索全部历史消息" },
];

let paletteItems = [];
let palettePos = -1;

function composerTriggers() {
  const input = $("composerInput");
  const caret = input.selectionStart ?? input.value.length;
  const before = input.value.slice(0, caret);
  if (/^\/\S*$/.test(before)) {
    const q = before.slice(1).toLowerCase();
    return { kind: "cmd", items: COMMANDS.filter(c => c.name.slice(1).startsWith(q)) };
  }
  const at = before.match(/@([^\s@]*)$/);
  if (at && currentMembers.length) {
    const q = at[1].toLowerCase();
    return { kind: "mention", items: currentMembers.filter(m =>
      m.sn.toLowerCase().includes(q) || (m.displayName || "").toLowerCase().includes(q)) };
  }
  return null;
}

function renderPalette() {
  const trigger = composerTriggers();
  const box = $("palette");
  if (!trigger || !trigger.items.length) { closePalette(); return; }
  paletteItems = trigger.items;
  palettePos = trigger.kind === "cmd" ? 0 : -1;
  box.classList.remove("hidden");
  box.innerHTML = "";
  for (const item of trigger.items) {
    const row = el("div", "palette-item");
    if (trigger.kind === "cmd") {
      row.appendChild(el("span", "palette-name mono", item.name));
      row.appendChild(el("span", "palette-desc", item.desc));
    } else {
      row.appendChild(el("span", "palette-name", item.displayName || item.sn));
      row.appendChild(el("span", "palette-desc mono", item.sn));
    }
    row.addEventListener("mousedown", (e) => { e.preventDefault(); pickPalette(item); });
    box.appendChild(row);
  }
  highlightPalette();
}

function highlightPalette() {
  [...$("palette").children].forEach((row, i) =>
    row.classList.toggle("on", i === palettePos));
}

function closePalette() {
  $("palette").classList.add("hidden");
  $("palette").innerHTML = "";
  paletteItems = [];
  palettePos = -1;
}

function pickPalette(item) {
  const input = $("composerInput");
  if (COMMANDS.includes(item)) {
    closePalette();
    input.value = "";
    autosize(input);
    runCommand(item.name);
    return;
  }
  // @ 成员(Codex @ 引用的对齐物:没有文件工作区时,引用对象是团队成员):切换会话对象
  const caret = input.selectionStart ?? input.value.length;
  const before = input.value.slice(0, caret).replace(/@[^\s@]*$/, "");
  input.value = before + input.value.slice(caret);
  closePalette();
  selectPeer(item.sn);
}

function runCommand(name) {
  if (name === "/new") { newConversation(); return; }
  if (name === "/copy") { copyLastReply(); return; }
  if (name === "/model") { openSettings("model"); return; }
  if (name === "/mock") { toggleMock(); return; }
  if (name === "/theme") { applyTheme((document.documentElement.dataset.theme || "dark") === "dark" ? "light" : "dark"); toast("外观已切换(可在设置里确认)"); return; }
  if (name === "/tasks" || name === "/events") { switchDock(name.slice(1)); return; }
  if (name === "/resume") { $("btnResume").click(); return; }
  if (name === "/search") { openSearch(); return; }
}

async function toggleMock() {
  try {
    const m = await api("/api/settings/model");
    await api("/api/settings/model", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ baseUrl: m.baseUrl, apiKey: "", model: m.model, temperature: m.temperature, mock: !m.mock }),
    });
    await updateModelChip();
    toast(!m.mock ? "演示模式已开启" : "演示模式已关闭");
  } catch (e) { toast(`切换失败:${e.message}`); }
}

function copyLastReply() {
  const last = [...state.currentMsgs].reverse().find(m => m.role === "assistant");
  if (!last) { toast("还没有可复制的回复"); return; }
  navigator.clipboard.writeText(last.content).then(() => toast("已复制最近回复"), () => toast("复制失败"));
}

function switchDock(tab) {
  const btn = document.querySelector(`.dock-tab[data-tab="${tab}"]`);
  if (btn) btn.click();
}

/* ---------- 历史搜索(Codex Ctrl+R) ---------- */

function openSearch() {
  $("searchOverlay").classList.remove("hidden");
  $("searchInput").value = "";
  $("searchResults").innerHTML = "";
  $("searchEmpty").classList.remove("hidden");
  $("searchEmpty").textContent = "输入关键词,搜索全部历史消息";
  $("searchInput").focus();
}

function closeSearch() {
  $("searchOverlay").classList.add("hidden");
}

async function runSearch(q) {
  if (!q.trim()) {
    $("searchResults").innerHTML = "";
    $("searchEmpty").classList.remove("hidden");
    $("searchEmpty").textContent = "输入关键词,搜索全部历史消息";
    return;
  }
  const rosterSns = new Set(currentMembers.map(m => m.sn));
  let rows = [];
  try {
    rows = await api(`/api/agent/sessions/search?q=${encodeURIComponent(q.trim())}`);
  } catch (e) {
    $("searchEmpty").classList.remove("hidden");
    $("searchEmpty").textContent = `搜索失败:${e.message}`;
    return;
  }
  rows = rows.filter(r => rosterSns.has(r.agent_sn)); // 限定当前团队花名册,跨团队切换不在本轮范围
  const list = $("searchResults");
  list.innerHTML = "";
  $("searchEmpty").classList.toggle("hidden", rows.length > 0);
  if (!rows.length) {
    $("searchEmpty").textContent = "没有匹配的历史消息";
    $("searchEmpty").classList.remove("hidden");
  }
  for (const r of rows) {
    const item = el("div", "search-item");
    const head = el("div", "search-head");
    head.appendChild(el("span", "search-title", r.title || "(无标题会话)"));
    head.appendChild(el("span", "search-sn mono", r.agent_sn));
    item.appendChild(head);
    item.appendChild(el("div", "search-snippet", `${r.role === "user" ? "你" : "回复"}:${r.content.slice(0, 120)}`));
    item.addEventListener("click", async () => {
      closeSearch();
      await selectPeer(r.agent_sn);
      switchSession(r.thread_id);
    });
    list.appendChild(item);
  }
}

/** 新对话:给当前成员换一个 sessionId(服务端记忆随 threadId 隔离),清空本地记录 */
/** 新对话:指针置空,首条消息后生成新会话;旧会话仍在目录里可随时切回(dsh 会话语义) */
function newConversation() {
  if (!state.selectedSn) { toast("先选择一个成员"); return; }
  const sn = state.selectedSn;
  state.currentSessionId = null;
  localStorage.removeItem(SESSION_KEY(state.teamId, sn));
  renderSessions(sn);
  renderTranscript();
  toast(`已为 ${sn} 开启新对话`);
}

/* ---------- 面板与框 ---------- */

function autosize(ta) {
  ta.style.height = "auto";
  ta.style.height = Math.min(ta.scrollHeight, 160) + "px";
}

function bindSegment(segId, onSwitch) {
  const seg = $(segId);
  seg.addEventListener("click", (e) => {
    const btn = e.target.closest(".seg-btn");
    if (!btn || btn.classList.contains("on")) return;
    seg.querySelectorAll(".seg-btn").forEach(b => {
      b.classList.toggle("on", b === btn);
      b.setAttribute("aria-selected", b === btn ? "true" : "false");
    });
    onSwitch(btn.dataset.mode);
  });
}

function bindUI() {
  $("collapseBtn").addEventListener("click", () =>
    document.querySelector(".frame").classList.add("side-collapsed"));
  $("expandBtn").addEventListener("click", () =>
    document.querySelector(".frame").classList.remove("side-collapsed"));

  $("newChatBtn").addEventListener("click", newConversation);
  $("btnNewSession").addEventListener("click", newConversation);
  $("btnNewProject").addEventListener("click", () => $("projectDialog").showModal());
  $("projectCancel").addEventListener("click", () => $("projectDialog").close());
  $("projectCreate").addEventListener("click", async () => {
    const name = $("projectName").value.trim();
    if (!name) { toast("先起一个项目名"); return; }
    try {
      const p = await post("/api/project", { name, description: $("projectDesc").value.trim() });
      $("projectDialog").close();
      $("projectName").value = "";
      $("projectDesc").value = "";
      state.currentProject = String(p.id);
      await loadProjects();
      if (state.selectedSn) await loadSessions(state.selectedSn);
      toast(`已建项目 ${p.name},新对话将归入该项目`);
    } catch (e) { toast(`建项目失败:${e.message}`); }
  });

  $("btnNewTeam").addEventListener("click", () => $("teamDialog").showModal());
  $("teamCancel").addEventListener("click", () => $("teamDialog").close());
  $("teamCreateBtn").addEventListener("click", async () => {
    const name = $("teamName").value.trim();
    if (!name) { toast("先起一个团队名"); return; }
    try {
      await createTeam(name);
      $("teamDialog").close();
      $("teamName").value = "";
    } catch (e) { toast(`建团失败:${e.message}`); }
  });

  $("btnAddMember").addEventListener("click", () => $("memberDialog").showModal());
  $("memberCancel").addEventListener("click", () => $("memberDialog").close());
  bindSegment("memberModeSeg", (mode) => {
    state.addMemberMode = mode;
    $("fieldExisting").classList.toggle("hidden", mode !== "existing");
    $("fieldSpawn").classList.toggle("hidden", mode !== "spawn");
  });
  $("memberSubmit").addEventListener("click", async () => {
    if (!state.teamId) { toast("先建团队"); return; }
    try {
      let member;
      if (state.addMemberMode === "spawn") {
        member = await post(`/api/team/${state.teamId}/members`, {
          displayName: $("memberName").value.trim() || "成员",
          systemPrompt: $("memberDuty").value.trim() || "你是团队成员,服从 Leader 的任务分配。",
        });
        await loadAgents();
      } else {
        member = await post(`/api/team/${state.teamId}/members`, {
          agentSn: $("memberSn").value,
        });
      }
      toast(`已加入 ${member.agentSn}`);
      $("memberDialog").close();
      $("memberName").value = "";
      $("memberDuty").value = "";
      await refreshTeamData();
      selectPeer(member.agentSn);
    } catch (e) { toast(`加入失败:${e.message}`); }
  });

  bindSegment("modeSeg", (mode) => {
    state.composerMode = mode;
    renderPeer();
  });

  $("composerSend").addEventListener("click", () => {
    // 流式进行中该按钮是"停止";空闲时是发送/投递
    if (state.streaming) {
      if (state.abort) state.abort.abort();
      return;
    }
    send();
  });
  $("btnResume").addEventListener("click", async () => {
    if (!state.selectedSn || !state.teamId) return;
    try {
      await post(`/api/team/${state.teamId}/members/${encodeURIComponent(state.selectedSn)}/resume`, {});
      toast(`${state.selectedSn} 槽位已恢复,信箱积压将自动处理`);
      await refreshTeamData();
      renderPeer();
    } catch (e) { toast(`恢复失败:${e.message}`); }
  });
  $("composerInput").addEventListener("keydown", (e) => {
    // 候选面板打开时,键盘优先服务面板(Codex / 与 @ 的菜单语义)
    if (!$("palette").classList.contains("hidden") && paletteItems.length) {
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        e.preventDefault();
        const delta = e.key === "ArrowDown" ? 1 : -1;
        palettePos = (palettePos + delta + paletteItems.length) % paletteItems.length;
        highlightPalette();
        return;
      }
      if (e.key === "Enter") {
        e.preventDefault();
        if (palettePos >= 0) pickPalette(paletteItems[palettePos]);
        return;
      }
      if (e.key === "Escape") { closePalette(); return; }
    }
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); return; }
    // 草稿历史:输入为空或正在浏览草稿时,↑/↓ 在历史草稿间移动
    const atStart = (e.target.selectionStart ?? 0) === 0 && (e.target.selectionEnd ?? 0) === 0;
    const atEnd = (e.target.selectionStart ?? 0) === e.target.value.length;
    if (e.key === "ArrowUp" && atStart && (!e.target.value || state.draftPos !== -1)) {
      e.preventDefault(); draftNavigate(-1);
    } else if (e.key === "ArrowDown" && atEnd && state.draftPos !== -1) {
      e.preventDefault(); draftNavigate(1);
    }
  });
  $("composerInput").addEventListener("input", (e) => {
    autosize(e.target);
    renderPalette();
  });
  $("composerInput").addEventListener("blur", () => setTimeout(closePalette, 120));
  $("composerInput").addEventListener("click", renderPalette);

  $("rightToggle").addEventListener("click", () => {
    const bar = $("rightbar");
    const closed = bar.classList.toggle("closed");
    $("rightToggle").setAttribute("aria-pressed", String(!closed));
  });

  document.querySelector(".dock-tabs").addEventListener("click", (e) => {
    const tab = e.target.closest(".dock-tab");
    if (!tab) return;
    state.dockTab = tab.dataset.tab;
    document.querySelectorAll(".dock-tab").forEach(t => {
      t.classList.toggle("on", t === tab);
      t.setAttribute("aria-selected", t === tab ? "true" : "false");
    });
    document.querySelectorAll(".dock-pane").forEach(p =>
      p.classList.toggle("on", p.id === `tab-${state.dockTab}`));
    if (state.dockTab === "events") {
      state.eventUnseen = 0;
      $("eventBadge").classList.add("hidden");
    }
  });

  // 设置面板
  $("btnSettings").addEventListener("click", () => openSettings("model"));
  $("settingsClose").addEventListener("click", closeSettings);
  $("settingsMask").addEventListener("click", closeSettings);
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      if (!$("settings").classList.contains("hidden")) { closeSettings(); return; }
      if (!$("searchOverlay").classList.contains("hidden")) { closeSearch(); return; }
      if (!$("palette").classList.contains("hidden")) { closePalette(); return; }
      if (!$("editBanner").classList.contains("hidden")) { cancelEdit(); return; }
      // Codex 同款:Esc 中断当前回合
      if (state.streaming && state.abort) state.abort.abort();
      return;
    }
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "r") {
      // Ctrl+R 打开历史搜索(Codex 语义);浏览器默认刷新被拦截
      e.preventDefault();
      openSearch();
      return;
    }
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "o") {
      e.preventDefault();
      copyLastReply();
    }
  });

  // 历史搜索浮层(Codex Ctrl+R)
  $("btnSearch").addEventListener("click", openSearch);
  $("searchClose").addEventListener("click", closeSearch);
  $("searchMask").addEventListener("click", closeSearch);
  $("searchInput").addEventListener("input", (e) => {
    clearTimeout(state.searchTimer);
    state.searchTimer = setTimeout(() => runSearch(e.target.value), 300);
  });
  $("searchInput").addEventListener("keydown", (e) => {
    if (e.key === "Enter") { e.preventDefault(); clearTimeout(state.searchTimer); runSearch(e.target.value); }
  });
  // 设置 → 插件(启停/配置/清单收纳在设置抽屉内)
  $("invSearch").addEventListener("input", renderPluginCards);
  $("invRefresh").addEventListener("click", loadPlugins);
  document.querySelector(".set-nav").addEventListener("click", (e) => {
    const item = e.target.closest(".set-item");
    if (item) switchSettingsTab(item.dataset.tab);
  });
  $("setMock").addEventListener("click", () => {
    setToggle($("setMock"), $("setMock").getAttribute("aria-pressed") !== "true");
  });
  $("setModelSave").addEventListener("click", saveModelSettings);
  $("setGovernanceSave").addEventListener("click", saveGovernanceSettings);
  $("statusRefresh").addEventListener("click", loadSystemStatus);
  $("modelChip").addEventListener("click", () => openSettings("model"));

  // 外观:主题 / 字号(立即生效 + 本机保存)
  document.querySelector("#themeSeg").addEventListener("click", (e) => {
    const btn = e.target.closest(".seg-btn");
    if (!btn) return;
    document.querySelectorAll("#themeSeg .seg-btn").forEach(b => {
      b.classList.toggle("on", b === btn);
      b.setAttribute("aria-selected", b === btn ? "true" : "false");
    });
    applyTheme(btn.dataset.theme);
  });
  document.querySelector("#fontSeg").addEventListener("click", (e) => {
    const btn = e.target.closest(".seg-btn");
    if (!btn) return;
    document.querySelectorAll("#fontSeg .seg-btn").forEach(b => {
      b.classList.toggle("on", b === btn);
      b.setAttribute("aria-selected", b === btn ? "true" : "false");
    });
    applyFont(btn.dataset.zoom);
  });
}

/* ---------- 设置(dsh Settings 范式:右侧滑出 + 内部导航) ---------- */

function openSettings(tab = "model") {
  $("settings").classList.remove("hidden");
  $("settingsMask").classList.remove("hidden");
  switchSettingsTab(tab);
}

function closeSettings() {
  $("settings").classList.add("hidden");
  $("settingsMask").classList.add("hidden");
}

function switchSettingsTab(tab) {
  document.querySelectorAll(".set-item").forEach(b => {
    b.classList.toggle("on", b.dataset.tab === tab);
  });
  document.querySelectorAll(".set-pane").forEach(p => {
    p.classList.toggle("on", p.id === `set-${tab}`);
  });
  if (tab === "model") loadModelSettings();
  if (tab === "governance") loadGovernanceSettings();
  if (tab === "system") loadSystemStatus();
  if (tab === "plugins") loadPlugins();
  if (tab === "appearance") {
    for (const [id, key, value] of [
      ["themeSeg", "theme", document.documentElement.dataset.theme || "dark"],
      ["fontSeg", "zoom", document.body.dataset.zoom || "1"],
    ]) {
      document.querySelectorAll(`#${id} .seg-btn`).forEach(b => {
        const selected = b.dataset[key] === value;
        b.classList.toggle("on", selected);
        b.setAttribute("aria-selected", String(selected));
      });
    }
  }
}

function setToggle(btn, on) {
  btn.setAttribute("aria-pressed", String(on));
}

async function loadModelSettings() {
  try {
    const m = await api("/api/settings/model");
    $("setBaseUrl").value = m.baseUrl || "";
    $("setModel").value = m.model || "";
    $("setTemperature").value = m.temperature ?? 0.7;
    $("keyHint").textContent = m.hasKey
      ? `已配置 API Key(尾号 ${m.keyTail});留空保存即保留`
      : "尚未配置 API Key";
    setToggle($("setMock"), !!m.mock);
    updateModelChip(m);
  } catch (e) { toast(`读取模型设置失败:${e.message}`); }
}

async function saveModelSettings() {
  if (![...document.querySelectorAll("#set-model input")].every(input => input.reportValidity())) return;
  const button = $("setModelSave");
  button.disabled = true;
  try {
    const m = await api("/api/settings/model", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        baseUrl: $("setBaseUrl").value.trim(),
        apiKey: $("setApiKey").value.trim(),
        model: $("setModel").value.trim(),
        temperature: Number($("setTemperature").value),
        mock: $("setMock").getAttribute("aria-pressed") === "true",
      }),
    });
    $("setApiKey").value = "";
    toast("模型配置已保存,后续调用使用新配置");
    await loadModelSettings();
  } catch (e) { toast(`保存失败:${e.message}`); }
  finally { button.disabled = false; }
}

async function loadGovernanceSettings() {
  try {
    const g = await api("/api/settings/team");
    $("setLease").value = g.leaseSeconds;
    $("setAttempts").value = g.deliveryMaxAttempts;
    $("setBatch").value = g.wakeBatchSize;
  } catch (e) { toast(`读取治理设置失败:${e.message}`); }
}

async function saveGovernanceSettings() {
  if (![...document.querySelectorAll("#set-governance input")].every(input => input.reportValidity())) return;
  const button = $("setGovernanceSave");
  button.disabled = true;
  try {
    const g = await api("/api/settings/team", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        leaseSeconds: Number($("setLease").value),
        deliveryMaxAttempts: Number($("setAttempts").value),
        wakeBatchSize: Number($("setBatch").value),
      }),
    });
    await loadGovernanceSettings();
    toast("治理参数已保存:对后续回合立即生效");
  } catch (e) { toast(`保存失败:${e.message}`); }
  finally { button.disabled = false; }
}

async function loadSystemStatus() {
  const box = $("systemStatus");
  box.textContent = "加载中…";
  try {
    const s = await api("/api/settings/status");
    const row = (k, v, cls = "") => {
      const r = el("div", "status-row");
      r.appendChild(el("span", "k", k));
      r.appendChild(el("span", `v ${cls}`, v));
      return r;
    };
    box.innerHTML = "";
    box.appendChild(row("PostgreSQL", s.pg, s.pg));
    box.appendChild(row("Redis", s.redis, s.redis));
    box.appendChild(row("模型端点", s.model.baseUrl, "dim"));
    box.appendChild(row("当前模型", s.model.mock ? "Mock 演示模型" : s.model.model));
    box.appendChild(row("演示模式(Mock)", s.model.mock ? "开" : "关"));
    box.appendChild(row("注册 Agent", s.agents.join(", ") || "无"));
    box.appendChild(row("团队数", s.teams == null ? "不可用" : String(s.teams)));
  } catch (e) {
    box.textContent = `读取失败:${e.message}`;
  }
}

function applyTheme(theme) {
  if (theme === "light") document.documentElement.dataset.theme = "light";
  else delete document.documentElement.dataset.theme;
  localStorage.setItem("ma.theme", theme);
}

function applyFont(zoom) {
  if (zoom && zoom !== "1") document.body.dataset.zoom = zoom;
  else delete document.body.dataset.zoom;
  localStorage.setItem("ma.font", zoom || "1");
}

async function updateModelChip(m) {
  if (!m) {
    try { m = await api("/api/settings/model"); }
    catch (_) { return; }
  }
  $("modelChip").textContent = m.mock ? "Mock 演示模型" : (m.model || "未配置模型");
}

/* ---------- 设置 → 插件(dsh ui-plugin-manager 收纳进 Settings:卡片启停 + 行内展开配置) ---------- */

const PLUGIN_ICONS = {
  "plugin-datetime": "🕑",
  "plugin-calculator": "🧮",
  "plugin-web-fetch": "🌐",
};

const pluginUI = { list: [], expanded: null };

function replacePlugin(view) {
  const i = pluginUI.list.findIndex(x => x.key === view.key);
  if (i >= 0) pluginUI.list[i] = view;
}

async function loadPlugins() {
  const box = $("invList");
  box.innerHTML = "";
  box.appendChild(el("div", "dock-empty", "加载中…"));
  try {
    pluginUI.list = await api("/api/plugins");
    renderPluginCards();
  } catch (e) {
    box.innerHTML = "";
    box.appendChild(el("div", "dock-empty", `读取失败:${e.message}`));
  }
}

function renderPluginCards() {
  const box = $("invList");
  box.innerHTML = "";
  const q = ($("invSearch").value || "").trim().toLowerCase();
  const rows = pluginUI.list.filter(p => !q || [
    p.title, p.description, p.key, p.moduleId,
    ...p.tools.map(t => `${t.name} ${t.description}`),
  ].join(" ").toLowerCase().includes(q));
  if (!rows.length) {
    box.appendChild(el("div", "dock-empty", q ? "没有匹配的插件。" : "没有可用插件。"));
    return;
  }
  for (const p of rows) box.appendChild(pluginCard(p));
}

function pluginTitleRow(p) {
  const title = el("div", "pc-title");
  title.appendChild(el("span", null, p.title));
  title.appendChild(el("span", "pc-ver mono", `v${p.version}`));
  if (p.experimental) title.appendChild(el("span", "pc-badge exp", "实验性"));
  // dsh 语义:普通启用不标标签,只标异常态
  if (p.error) title.appendChild(el("span", "pc-badge err", "启动失败"));
  else if (!p.enabled) title.appendChild(el("span", "pc-badge off", "已停用"));
  return title;
}

function pluginToggleBtn(p) {
  const btn = el("button", "toggle");
  btn.type = "button";
  btn.setAttribute("aria-label", `启用 ${p.title}`);
  btn.setAttribute("aria-pressed", String(!!p.enabled));
  btn.appendChild(el("span", "knob"));
  btn.addEventListener("click", async () => {
    const enabling = btn.getAttribute("aria-pressed") !== "true";
    btn.disabled = true;
    try {
      const view = await post(`/api/plugins/${encodeURIComponent(p.key)}/${enabling ? "enable" : "disable"}`, {});
      replacePlugin(view);
      renderPluginCards();
      toast(enabling ? `已启用 ${p.title}:工具已注入所有 Agent` : `已停用 ${p.title}`);
    } catch (err) { toast(`${enabling ? "启用" : "停用"}失败:${err.message}`); }
    finally { btn.disabled = false; }
  });
  return btn;
}

function pluginCard(p) {
  const card = el("article", "inv-card plugin-card" + (p.enabled ? " on" : ""));
  const head = el("div", "pc-head");
  head.appendChild(el("span", "pc-icon", PLUGIN_ICONS[p.key] || "⬡"));
  const tw = el("div", "pc-title-wrap");
  tw.appendChild(pluginTitleRow(p));
  tw.appendChild(el("div", "pc-key mono", p.key));
  head.appendChild(tw);
  head.appendChild(pluginToggleBtn(p));
  card.appendChild(head);
  card.appendChild(el("p", "pc-desc", p.description));

  const foot = el("div", "pc-foot");
  foot.appendChild(el("span", "pc-tools-count",
    `${p.tools.length ? `${p.tools.length} 个工具` : "无工具"} · 全局作用域`));
  let inline = null;
  if (p.configFields.length || p.error) {
    const cfgBtn = el("button", "mini-btn", pluginUI.expanded === p.key ? "收起" : "配置");
    cfgBtn.type = "button";
    cfgBtn.addEventListener("click", () => {
      pluginUI.expanded = pluginUI.expanded === p.key ? null : p.key;
      renderPluginCards();
    });
    foot.appendChild(cfgBtn);
    if (pluginUI.expanded === p.key) {
      inline = pluginInlineSection(p);
      card.appendChild(inline);
    }
  }
  card.appendChild(foot);
  if (p.error && pluginUI.expanded !== p.key) card.appendChild(el("div", "pc-err", p.error));
  return card;
}

/** 行内展开区:工具清单 + 状态/配置表单(仅占一卡片高度,不离开设置抽屉) */
function pluginInlineSection(p) {
  const box = el("div", "pd-inline");
  box.dataset.key = p.key;

  const toolSec = el("div", "pd-section");
  toolSec.appendChild(el("h5", null, `提供工具(${p.tools.length})`));
  if (!p.tools.length) {
    toolSec.appendChild(el("div", "dock-empty", "该插件没有提供工具。"));
  } else {
    const ul = el("ul", "pd-tools");
    for (const t of p.tools) {
      const li = el("li", "pd-tool");
      li.appendChild(el("code", null, t.name));
      li.appendChild(el("span", null, t.description));
      ul.appendChild(li);
    }
    toolSec.appendChild(ul);
  }
  box.appendChild(toolSec);

  if (p.error) box.appendChild(el("div", "pd-status err", `启动失败:${p.error}`));

  if (p.configFields.length) {
    const cfgSec = el("div", "pd-section");
    cfgSec.appendChild(el("h5", null, "配置"));
    const form = el("div", "pd-form");
    for (const f of p.configFields) {
      const field = el("div", "field");
      const label = el("label", null, f.label || f.name);
      label.htmlFor = `pf-${p.key}-${f.name}`;
      field.appendChild(label);
      if (f.type === "boolean") {
        const toggle = el("button", "toggle");
        toggle.type = "button";
        toggle.id = `pf-${p.key}-${f.name}`;
        toggle.setAttribute("aria-pressed", String(!!p.config[f.name]));
        toggle.appendChild(el("span", "knob"));
        toggle.addEventListener("click", () =>
          toggle.setAttribute("aria-pressed", toggle.getAttribute("aria-pressed") !== "true"));
        field.appendChild(toggle);
      } else {
        const input = el("input", null);
        input.id = `pf-${p.key}-${f.name}`;
        input.name = f.name;
        if (f.type === "number") {
          input.type = "number";
          input.step = "any";
          if (f.min != null) input.min = String(f.min);
          if (f.max != null) input.max = String(f.max);
          input.required = true;
        } else {
          input.type = "text";
          input.maxLength = 500;
        }
        input.value = p.config[f.name] ?? f.defaultValue ?? "";
        if (f.hint) field.appendChild(el("p", "hint", f.hint));
        field.appendChild(input);
      }
      form.appendChild(field);
    }
    const save = el("button", "btn solid", "保存配置");
    save.type = "button";
    save.addEventListener("click", () => savePluginConfig(p));
    form.appendChild(save);
    cfgSec.appendChild(form);
    box.appendChild(cfgSec);
  }
  return box;
}

async function savePluginConfig(p) {
  const form = document.querySelector(`.pd-inline[data-key="${CSS.escape(p.key)}"] .pd-form`);
  if (form && ![...form.querySelectorAll("input")].every(input => input.reportValidity())) return;
  const config = {};
  for (const f of p.configFields) {
    const node = document.getElementById(`pf-${p.key}-${f.name}`);
    if (!node) continue;
    if (f.type === "number") config[f.name] = Number(node.value);
    else if (f.type === "boolean") config[f.name] = node.getAttribute("aria-pressed") === "true";
    else config[f.name] = node.value.trim();
  }
  try {
    const view = await api(`/api/plugins/${encodeURIComponent(p.key)}/config`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ config }),
    });
    replacePlugin(view);
    renderPluginCards();
    toast("配置已保存,工具集已按新配置重建");
  } catch (e) { toast(`保存失败:${e.message}`); }
}

/* ---------- 启动 ---------- */

(async function init() {
  // 外观先行,避免闪烁
  applyTheme(localStorage.getItem("ma.theme") || "dark");
  applyFont(localStorage.getItem("ma.font") || "1");

  bindUI();
  try {
    await Promise.all([loadTeams(), loadAgents(), loadProjects(), updateModelChip()]);
  } catch (e) {
    toast(`服务连接失败:${e.message} —— 确认 my-agent(8070)已启动`);
  }
  if (state.teamId) {
    await refreshAndRevealLead();
  }
  renderTranscript();
})();
