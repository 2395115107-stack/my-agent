/* my-agent 调度台客户端 —— 交互范式对齐 dsh Web 客户端:
   侧栏(品牌/新对话/会话列表/底部操作) + 会话流(用户气泡右·助手平铺左) + 右侧停靠面板(任务板/事件流) */
"use strict";

const $ = (id) => document.getElementById(id);

const state = {
  teams: [],
  teamId: null,
  agents: [],
  selectedSn: null,
  composerMode: "chat",
  addMemberMode: "existing",
  es: null,
  streaming: false,
  eventUnseen: 0,
  dockTab: "tasks",
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
const LOG_KEY = (teamId, sn) => `ma.log.${teamId}.${sn}`;

function loadLog(sn) {
  try { return JSON.parse(localStorage.getItem(LOG_KEY(state.teamId, sn))) || []; }
  catch (_) { return []; }
}

function saveLog(sn, log) {
  localStorage.setItem(LOG_KEY(state.teamId, sn), JSON.stringify(log.slice(-200)));
}

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
  for (const t of state.teams) {
    const btn = el("button", "team-item" + (String(t.id) === String(state.teamId) ? " on" : ""));
    btn.type = "button";
    btn.appendChild(el("span", "tid", `#${t.id}`));
    btn.appendChild(el("span", "s-name", t.name));
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
    if (lead) selectPeer(lead.agentSn);
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
    const li = el("li", "session-item" + (m.agentSn === state.selectedSn ? " on" : ""));
    const dot = el("span", "s-dot" + (m.status === "PAUSED" ? " paused" : ""));
    dot.dataset.sn = m.agentSn;
    li.appendChild(dot);
    li.appendChild(el("span", "s-name", m.displayName || m.agentSn));
    li.appendChild(el("span", "s-sn", m.agentSn));
    if (m.unread > 0) li.appendChild(el("span", "s-unread", String(m.unread)));
    if (m.role === "LEAD") li.appendChild(el("span", "s-role lead", "Lead"));
    const pick = () => selectPeer(m.agentSn);
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
    mailbox_changed: (p) => { /* turn_done:{sn} */ },
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

function selectPeer(sn) {
  state.selectedSn = sn;
  renderRoster(currentMembers);
  renderPeer();
  renderTranscript();
  $("composerInput").focus();
}

function peerOf(sn) { return currentMembers.find(m => m.agentSn === sn); }

function isPaused(sn) {
  const m = peerOf(sn);
  return m ? m.status === "PAUSED" : false;
}

function renderPeer() {
  const m = state.selectedSn ? peerOf(state.selectedSn) : null;
  $("peerName").textContent = m ? (m.displayName || m.agentSn) : "未选择成员";
  $("peerSn").textContent = m
    ? `${m.agentSn} · ${m.status === "PAUSED" ? "已暂停" : "活跃"}`
    : "从左侧选择一个成员开始";
  $("peerDot").classList.toggle("on", !!(m && m.status !== "PAUSED"));
  $("btnResume").classList.toggle("hidden", !(m && m.status === "PAUSED"));
  $("composerInput").placeholder = m
    ? (state.composerMode === "chat"
      ? `和 ${m.agentSn} 对话…`
      : `投递给 ${m.agentSn} 的信箱(将唤醒它)…`)
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

function renderTranscript() {
  const box = $("transcript");
  box.innerHTML = "";
  box.appendChild(transcriptEmptyEl);
  if (!state.selectedSn) {
    transcriptEmptyEl.classList.remove("hidden");
    return;
  }
  const log = loadLog(state.selectedSn);
  transcriptEmptyEl.classList.toggle("hidden", log.length > 0);
  for (const item of log) appendEntry(item.kind, item.sender, item.text);
  box.scrollTop = box.scrollHeight;
}

function appendEntry(kind, sender, text, streaming = false) {
  transcriptEmptyEl.classList.add("hidden");
  const box = $("transcript");
  const entry = el("div", `entry entry-${kind}` + (streaming ? " streaming" : ""));
  const head = el("div", "entry-head");
  head.appendChild(el("span", "entry-sender", sender));
  head.appendChild(el("span", "entry-time", now()));
  entry.appendChild(head);
  entry.appendChild(el("div", "entry-body", text));
  box.appendChild(entry);
  box.scrollTop = box.scrollHeight;
  return entry;
}

function record(kind, sender, text) {
  if (!state.selectedSn) return;
  const log = loadLog(state.selectedSn);
  log.push({ kind, sender, text, time: now() });
  saveLog(state.selectedSn, log);
}

/** POST /api/agent/chat 的 SSE 帧解析:data:{content,end} */
async function streamChat(sn, message, onChunk, signal) {
  const sessionId =
    localStorage.getItem(SESSION_KEY(state.teamId, sn)) ||
    `web-${state.teamId}-${sn}-${Math.random().toString(36).slice(2, 10)}`;
  localStorage.setItem(SESSION_KEY(state.teamId, sn), sessionId);

  const res = await fetch("/api/agent/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ sn, sessionId, message }),
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

async function send() {
  const input = $("composerInput");
  const text = input.value.trim();
  if (!text || !state.selectedSn || state.streaming) return;
  input.value = "";
  autosize(input);

  const sn = state.selectedSn;
  if (state.composerMode === "mailbox") {
    appendEntry("mail", sn, text);
    record("mail", sn, text);
    pulseSn(sn);
    try {
      await post(`/api/team/${state.teamId}/messages`, { toSn: sn, content: text });
      const paused = isPaused(sn);
      toast(paused ? `已投递(${sn} 暂停中,恢复后自动唤醒)` : `已投递给 ${sn},信箱写入即唤醒`);
    } catch (e) {
      appendEntry("sys", "投递失败", `${e.message} —— 确认团队与成员状态后重试`);
      record("sys", "sys", `投递失败:${e.message}`);
    }
    return;
  }

  appendEntry("user", "你", text);
  record("user", "你", text);
  state.streaming = true;
  state.abort = new AbortController();
  const sendBtn = $("composerSend");
  sendBtn.disabled = false;
  sendBtn.textContent = "停止";
  sendBtn.classList.add("stop");
  const entry = appendEntry("agent", sn, "", true);
  let acc = "";
  try {
    await streamChat(sn, text, (frame) => {
      if (frame.content) {
        acc += frame.content;
        entry.querySelector(".entry-body").textContent = acc;
        $("transcript").scrollTop = $("transcript").scrollHeight;
      }
    }, state.abort.signal);
    entry.classList.remove("streaming");
    record("agent", sn, acc);
  } catch (e) {
    entry.classList.remove("streaming");
    if (e.name === "AbortError") {
      const stopped = acc ? acc + "\n\n(已停止)" : "(已停止)";
      entry.querySelector(".entry-body").textContent = stopped;
      record("agent", sn, stopped);
    } else {
      const errText = `调用失败:${e.message} —— 若为 401/连接错误,检查 LLM_API_KEY 与模型端点配置`;
      entry.querySelector(".entry-body").textContent = errText;
      record("sys", "sys", errText);
    }
  } finally {
    state.streaming = false;
    state.abort = null;
    sendBtn.classList.remove("stop");
    renderPeer();
  }
}

/** 新对话:给当前成员换一个 sessionId(服务端记忆随 threadId 隔离),清空本地记录 */
function newConversation() {
  if (!state.selectedSn) { toast("先选择一个成员"); return; }
  const sn = state.selectedSn;
  localStorage.removeItem(SESSION_KEY(state.teamId, sn));
  localStorage.removeItem(LOG_KEY(state.teamId, sn));
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
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); }
  });
  $("composerInput").addEventListener("input", (e) => autosize(e.target));

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
    if (e.key === "Escape" && !$("settings").classList.contains("hidden")) closeSettings();
  });
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

/* ---------- 启动 ---------- */

(async function init() {
  // 外观先行,避免闪烁
  applyTheme(localStorage.getItem("ma.theme") || "dark");
  applyFont(localStorage.getItem("ma.font") || "1");

  bindUI();
  try {
    await Promise.all([loadTeams(), loadAgents(), updateModelChip()]);
  } catch (e) {
    toast(`服务连接失败:${e.message} —— 确认 my-agent(8070)已启动`);
  }
  if (state.teamId) {
    await refreshAndRevealLead();
  }
  renderTranscript();
})();
