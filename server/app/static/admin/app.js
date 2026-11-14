/* NekoMate admin console — vanilla JS SPA */
(() => {
  'use strict';

  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

  const state = {
    token: localStorage.getItem('nm_admin_token') || '',
    user: null,
    logTimer: null,
    healthTimer: null,
    logFilters: { level: 'info', category: 'all', q: '' },
  };

  const NAV = [
    { id: 'welcome', label: '欢迎', icon: '🏠', title: '欢迎', sub: '服务端概览与开箱引导' },
    { id: 'personas', label: '角色卡', icon: '🎭', title: '角色卡', sub: '定义 AI 的身份、性格与说话方式' },
    { id: 'knowledge', label: '知识库', icon: '📚', title: '知识库', sub: '导入微信 / QQ 聊天记录，学习别人的语气' },
    { id: 'providers', label: '模型提供商', icon: '🧠', title: '模型提供商', sub: '对话模型 / 图像模型 / 分析模型' },
    { id: 'users', label: '用户', icon: '👥', title: '用户管理', sub: '账号、权限与密码重置' },
    { id: 'plugins', label: '插件', icon: '🧩', title: '插件', sub: '兼容 AstrBot 插件，热插拔扩展能力' },
    { id: 'tasks', label: '未来任务', icon: '⏰', title: '未来任务', sub: '定时消息、主动搭话、日常动态' },
    { id: 'releases', label: '客户端更新', icon: '📦', title: '客户端更新', sub: '上传 APK、版本号与更新日志' },
    { id: 'logs', label: '日志', icon: '📜', title: '运行日志', sub: '检索服务端、模型、插件与任务日志' },
    { id: 'settings', label: '设置', icon: '⚙️', title: '系统设置', sub: '全局参数、注册开关与安全' },
  ];

  // ------------------------------------------------------------------ utils
  const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
  const attr = esc;
  const fmtNum = (n) => Number(n || 0).toLocaleString('zh-CN');
  const fmtTime = (iso) => {
    if (!iso) return '—';
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return String(iso);
    const pad = (x) => String(x).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  };
  const timeAgo = (iso) => {
    if (!iso) return '—';
    const diff = (Date.now() - new Date(iso).getTime()) / 1000;
    if (Number.isNaN(diff)) return '—';
    if (diff < 60) return '刚刚';
    if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`;
    if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`;
    return `${Math.floor(diff / 86400)} 天前`;
  };
  const dur = (sec) => {
    sec = Math.max(0, Math.floor(Number(sec) || 0));
    const d = Math.floor(sec / 86400);
    const h = Math.floor((sec % 86400) / 3600);
    const m = Math.floor((sec % 3600) / 60);
    if (d) return `${d} 天 ${h} 小时`;
    if (h) return `${h} 小时 ${m} 分`;
    return `${m} 分钟`;
  };
  const bytes = (n) => {
    n = Number(n || 0);
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let i = 0;
    while (n >= 1024 && i < units.length - 1) { n /= 1024; i += 1; }
    return `${n.toFixed(n >= 100 || i === 0 ? 0 : 1)} ${units[i]}`;
  };
  const avatar = (value, fallback = '🌙') => {
    const v = String(value || '');
    if (/^https?:\/\//i.test(v) || v.startsWith('data:')) return `<img src="${attr(v)}" alt="">`;
    return esc(v || fallback);
  };

  function toast(message, kind = 'info') {
    const wrap = $('#toast-wrap');
    const el = document.createElement('div');
    el.className = `toast ${kind === 'error' ? 'err' : kind === 'ok' ? 'ok' : ''}`;
    el.innerHTML = `<span class="ico">${kind === 'error' ? '⚠️' : kind === 'ok' ? '✅' : 'ℹ️'}</span><div>${esc(message)}</div>`;
    wrap.appendChild(el);
    setTimeout(() => { el.style.opacity = '0'; el.style.transform = 'translateY(6px)'; }, 3200);
    setTimeout(() => el.remove(), 3600);
  }

  async function api(path, opts = {}) {
    const headers = {};
    if (state.token) headers.Authorization = `Bearer ${state.token}`;
    let body;
    if (opts.form) {
      body = opts.form;
    } else if (opts.body !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(opts.body);
    }
    let res;
    try {
      res = await fetch(path, { method: opts.method || 'GET', headers, body });
    } catch (err) {
      throw new Error('无法连接服务端，请检查容器是否运行');
    }
    if (res.status === 401 && !path.startsWith('/api/admin/auth/login')) {
      logout(false);
      throw new Error('登录已失效，请重新登录');
    }
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = { detail: text }; }
    if (!res.ok) {
      const detail = data && data.detail;
      throw new Error(typeof detail === 'string' ? detail : `请求失败（HTTP ${res.status}）`);
    }
    return data;
  }

  // ------------------------------------------------------------------ forms
  function fText(label, name, value = '', o = {}) {
    return `<label class="field ${o.full ? 'full' : ''}"><span>${label}</span>
      <input ${o.id ? `id="${o.id}"` : ''} name="${name}" type="${o.type || 'text'}" value="${attr(value)}" placeholder="${attr(o.placeholder || '')}"
        ${o.required ? 'required' : ''} ${o.step ? `step="${o.step}"` : ''} ${o.min !== undefined ? `min="${o.min}"` : ''}>
      ${o.hint ? `<span class="hint">${esc(o.hint)}</span>` : ''}</label>`;
  }
  function fArea(label, name, value = '', o = {}) {
    return `<label class="field ${o.full ? 'full' : ''}"><span>${label}</span>
      <textarea name="${name}" rows="${o.rows || 3}" placeholder="${attr(o.placeholder || '')}">${esc(value)}</textarea>
      ${o.hint ? `<span class="hint">${esc(o.hint)}</span>` : ''}</label>`;
  }
  function fSelect(label, name, options, value, o = {}) {
    const opts = options.map((item) => {
      const v = typeof item === 'object' ? item.value : item;
      const l = typeof item === 'object' ? item.label : item;
      return `<option value="${attr(v)}" ${String(v) === String(value) ? 'selected' : ''}>${esc(l)}</option>`;
    }).join('');
    return `<label class="field ${o.full ? 'full' : ''}"><span>${label}</span><select name="${name}" ${o.id ? `id="${o.id}"` : ''}>${opts}</select>
      ${o.hint ? `<span class="hint">${esc(o.hint)}</span>` : ''}</label>`;
  }
  function fSwitch(label, name, checked = false) {
    return `<label class="switch"><input type="checkbox" name="${name}" ${checked ? 'checked' : ''}>
      <span class="track"></span><span>${label}</span></label>`;
  }

  function openModal({ title, body, submitText = '保存', wide = false, onSubmit, after }) {
    const root = $('#modal-root');
    root.innerHTML = `<div class="modal-mask"><div class="modal ${wide ? 'wide' : ''}">
      <div class="modal-head"><h3>${esc(title)}</h3><button class="icon-btn" data-close type="button">✕</button></div>
      <form class="modal-body" id="modal-form">${body}</form>
      <div class="modal-foot">
        <button class="btn ghost" type="button" data-close>取消</button>
        <button class="btn primary" type="button" id="modal-submit">${esc(submitText)}</button>
      </div></div></div>`;
    const close = () => { root.innerHTML = ''; };
    $$('[data-close]', root).forEach((b) => { b.onclick = close; });
    $('.modal-mask', root).addEventListener('mousedown', (e) => {
      if (e.target.classList.contains('modal-mask')) close();
    });
    const form = $('#modal-form', root);
    const submit = async () => {
      const btn = $('#modal-submit', root);
      btn.disabled = true;
      try {
        const result = await onSubmit(form);
        if (result !== false) close();
      } catch (err) {
        toast(err.message || '操作失败', 'error');
      } finally {
        if (btn) btn.disabled = false;
      }
    };
    $('#modal-submit', root).onclick = submit;
    form.addEventListener('submit', (e) => { e.preventDefault(); submit(); });
    if (after) after(form);
    return { close, form };
  }

  function emptyState(icon, text, action = '') {
    return `<div class="empty"><span class="big">${icon}</span>${esc(text)}${action ? `<div style="margin-top:12px">${action}</div>` : ''}</div>`;
  }

  // ------------------------------------------------------------------ auth
  function showLogin() {
    $('#app-view').classList.add('hidden');
    $('#login-view').classList.remove('hidden');
    $('#login-user').value = 'admin';
    $('#login-pass').value = '';
  }

  function showApp() {
    $('#login-view').classList.add('hidden');
    $('#app-view').classList.remove('hidden');
  }

  function logout(show = true) {
    state.token = '';
    state.user = null;
    localStorage.removeItem('nm_admin_token');
    localStorage.removeItem('nm_admin_user');
    if (state.logTimer) { clearInterval(state.logTimer); state.logTimer = null; }
    if (state.healthTimer) { clearInterval(state.healthTimer); state.healthTimer = null; }
    showLogin();
    if (show) toast('已退出登录', 'ok');
  }

  function startHealthPing() {
    const ping = async () => {
      const el = $('#server-status');
      if (!el) return;
      try {
        const h = await api('/api/v1/health');
        el.innerHTML = `<i></i><span>在线 · DB ${h.db ? 'OK' : '异常'}</span>`;
        el.className = 'conn ok';
      } catch {
        el.innerHTML = '<i></i><span>服务不可达</span>';
        el.className = 'conn err';
      }
    };
    ping();
    if (state.healthTimer) clearInterval(state.healthTimer);
    state.healthTimer = setInterval(ping, 30000);
  }

  async function doLogin(event) {
    event.preventDefault();
    const username = $('#login-user').value.trim();
    const password = $('#login-pass').value;
    const btn = $('#login-form button[type="submit"]');
    btn.disabled = true;
    btn.textContent = '登录中…';
    try {
      const data = await api('/api/admin/auth/login', { method: 'POST', body: { username, password } });
      state.token = data.token;
      state.user = data.user;
      localStorage.setItem('nm_admin_token', data.token);
      localStorage.setItem('nm_admin_user', JSON.stringify(data.user));
      showApp();
      location.hash = '#/welcome';
      await renderRoute();
      startHealthPing();
      toast(`欢迎回来，${data.user.nickname || data.user.username}`, 'ok');
    } catch (err) {
      toast(err.message, 'error');
    } finally {
      btn.disabled = false;
      btn.textContent = '进入控制台';
    }
  }

  // ------------------------------------------------------------------ router
  function buildNav() {
    const nav = $('#nav');
    nav.innerHTML = NAV.map((item) => `
      <button class="nav-item" data-route="${item.id}">
        <span class="ico">${item.icon}</span><span>${item.label}</span>
      </button>`).join('');
    $$('.nav-item', nav).forEach((btn) => {
      btn.onclick = () => { location.hash = `#/${btn.dataset.route}`; };
    });
  }

  function setActiveNav(routeId) {
    $$('.nav-item').forEach((b) => b.classList.toggle('active', b.dataset.route === routeId));
    const meta = NAV.find((n) => n.id === routeId) || NAV[0];
    $('#page-title').textContent = meta.title;
    $('#page-sub').textContent = meta.sub;
  }

  async function renderRoute() {
    if (!state.token) { showLogin(); return; }
    if (state.logTimer) { clearInterval(state.logTimer); state.logTimer = null; }
    const raw = (location.hash || '#/welcome').replace(/^#\/?/, '');
    const [routeId, param] = raw.split('/');
    const route = NAV.find((n) => n.id === routeId) || NAV[0];
    setActiveNav(route.id);
    const page = $('#page');
    page.innerHTML = `<div class="card"><div class="skeleton" style="width:40%"></div>
      <div class="skeleton" style="width:80%;margin-top:14px"></div>
      <div class="skeleton" style="width:65%;margin-top:10px"></div></div>`;
    try {
      const map = {
        welcome: pageWelcome, personas: pagePersonas, knowledge: (p) => pageKnowledge(p),
        providers: pageProviders, users: pageUsers, plugins: pagePlugins,
        tasks: pageTasks, releases: pageReleases, logs: pageLogs, settings: pageSettings,
      };
      await map[route.id](param);
    } catch (err) {
      page.innerHTML = `<section class="card">${emptyState('🛰️', err.message || '页面加载失败')}
        <div class="row" style="justify-content:center"><button class="btn ghost" onclick="location.reload()">重新加载</button></div></section>`;
    }
  }

  // ------------------------------------------------------------------ welcome
  function statCard(label, value, foot, icon) {
    return `<div class="card stat"><div class="label">${icon} ${label}</div>
      <div class="value">${fmtNum(value)}</div><div class="foot">${esc(foot || '')}</div></div>`;
  }

  async function pageWelcome() {
    const d = await api('/api/admin/overview');
    const s = d.stats;
    const w = d.welcome;
    $('#online-pill').textContent = `${s.online} 在线`;
    const hour = new Date().getHours();
    const greet = hour < 6 ? '凌晨好' : hour < 11 ? '早上好' : hour < 14 ? '中午好' : hour < 18 ? '下午好' : '晚上好';
    const providers = d.providers || [];
    const roleReady = d.roles || {};
    const kindLabel = { chat: '对话', image: '图像', analysis: '分析', vision: '视觉', stt: '语音转写' };
    const kindState = ['chat', 'image', 'analysis', 'vision', 'stt'].map((kind) => {
      const list = providers.filter((p) => p.kind === kind && p.enabled);
      const ready = Boolean(roleReady[kind]) || list.length > 0;
      let label = '未配置（使用演示模式）';
      if (ready) {
        if (list.length) label = `${list[0].name} · ${list[0].model || '未填模型'}`;
        else if (kind === 'analysis') label = '复用对话模型';
        else label = '已就绪';
      }
      return `<div class="row" style="justify-content:space-between;padding:8px 0;border-bottom:1px solid rgba(255,255,255,.05)">
        <div class="row"><span class="dot ${ready ? 'ok' : 'warn'}"></span><span>${kindLabel[kind]}模型</span></div>
        <span class="badge ${ready ? 'ok' : 'warn'}">${esc(label)}</span>
      </div>`;
    }).join('');
    const doneCount = d.checklist.filter((c) => c.done).length;

    $('#page').innerHTML = `
      <section class="card hero">
        <div class="hero-top">
          <div>
            <h1>${greet}，${esc(w.admin)}</h1>
            <p class="greet">${esc(w.site_name)} · 服务端 v${esc(w.version)} · 已运行 ${dur(w.uptime_seconds)}</p>
            <div class="hero-badges">
              <span class="badge ok">● 服务在线</span>
              <span class="badge brand">${s.ai_friends} 个 AI 好友</span>
              <span class="badge">${s.personas} 张角色卡</span>
              <span class="badge">${s.knowledge_bases} 个知识库</span>
            </div>
          </div>
          <div class="clock">${fmtTime(w.server_time).replace(' ', '<br>')}<br>${esc(new Date().toLocaleDateString('zh-CN', { weekday: 'long' }))}</div>
        </div>
        ${w.notice ? `<div class="notice">📢 ${esc(w.notice)}</div>` : ''}
        ${w.must_change_password ? `<div class="notice" style="background:rgba(255,199,90,.09);border-color:rgba(255,199,90,.32);color:#ffe0a3">
          ⚠️ 仍在使用默认管理员密码，上线前请到 <a href="#/settings" style="color:#fff;text-decoration:underline">系统设置</a> 修改。
        </div>` : ''}
      </section>

      <div class="grid cols-4">
        ${statCard('用户总数', s.users, `近 7 天新增 ${fmtNum(s.users_new_week)}`, '👥')}
        ${statCard('今日消息', s.messages_today, `累计 ${fmtNum(s.messages)} 条`, '💬')}
        ${statCard('在线连接', s.online, `好友申请待处理 ${s.pending_requests}`, '📡')}
        ${statCard('插件 / 任务', s.plugins, `启用中的未来任务 ${s.tasks}`, '🧩')}
      </div>

      <div class="split">
        <section class="card">
          <div class="card-head">
            <div><h3>开箱检查清单</h3><p class="sub">按顺序完成这几步，AI 伴侣就能真正跑起来</p></div>
            <span class="badge ${doneCount === d.checklist.length ? 'ok' : ''}">${doneCount}/${d.checklist.length}</span>
          </div>
          <div class="check">
            ${d.checklist.map((c) => `
              <div class="check-item ${c.done ? 'done' : ''}">
                <div class="check-dot">${c.done ? '✓' : ''}</div>
                <div><div class="t">${esc(c.label)}</div><div class="h">${esc(c.hint)}</div></div>
              </div>`).join('')}
          </div>
        </section>
        <div class="stack">
          <section class="card">
            <div class="card-head"><h3>模型角色状态</h3><a href="#/providers" class="tag">去配置 →</a></div>
            ${kindState}
          </section>
          <section class="card">
            <div class="card-head"><h3>快捷入口</h3></div>
            <div class="row">
              <a class="btn sm" href="#/knowledge">📚 导入聊天记录</a>
              <a class="btn sm" href="#/personas">🪪 新建角色卡</a>
              <a class="btn sm" href="#/tasks">⏰ 定时任务</a>
              <a class="btn sm" href="/docs" target="_blank" rel="noopener">🧪 API 文档</a>
            </div>
          </section>
        </div>
      </div>

      <div class="split">
        <section class="card">
          <div class="card-head"><h3>最近注册用户</h3><a href="#/users" class="tag">全部 →</a></div>
          <div class="table-wrap"><table>
            <thead><tr><th>用户</th><th>角色</th><th>AI 好友</th><th>注册时间</th></tr></thead>
            <tbody>
              ${(d.recent_users || []).map((u) => `<tr>
                <td><div class="row"><div class="avatar" style="width:30px;height:30px;border-radius:9px;font-size:14px">${avatar(u.avatar, '👤')}</div>
                  <div>${esc(u.nickname)}<div class="muted mono" style="font-size:11px">@${esc(u.username)}</div></div></div></td>
                <td>${u.role === 'admin' ? '<span class="badge brand">管理员</span>' : '<span class="badge">用户</span>'}</td>
                <td>${u.ai_friends || 0}</td>
                <td class="muted">${timeAgo(u.created_at)}</td>
              </tr>`).join('') || `<tr><td colspan="4">${emptyState('🌱', '还没有用户，去客户端注册或在用户管理中创建')}</td></tr>`}
            </tbody>
          </table></div>
        </section>
        <section class="card">
          <div class="card-head"><h3>最新日志</h3><a href="#/logs" class="tag">全部 →</a></div>
          <div class="stack" style="gap:8px">
            ${(d.recent_logs || []).map((l) => `<div style="font-size:12px;display:flex;gap:8px;align-items:flex-start">
              <span class="level ${esc(l.level)}">${esc(l.level)}</span>
              <div style="flex:1;min-width:0"><div>${esc(l.message)}</div>
              <div class="log-meta">${timeAgo(l.created_at)} · ${esc(l.category)}</div></div></div>`).join('') || emptyState('📭', '暂无日志')}
          </div>
        </section>
      </div>
    `;
  }

  // ------------------------------------------------------------------ personas
  function stripTrailingCommas(text) {
    let out = '';
    let inString = false;
    let escaped = false;
    for (let i = 0; i < text.length; i++) {
      const ch = text[i];
      if (inString) {
        out += ch;
        if (escaped) escaped = false;
        else if (ch === '\\') escaped = true;
        else if (ch === '"') inString = false;
        continue;
      }
      if (ch === '"') {
        inString = true;
        out += ch;
        continue;
      }
      if (ch === ',') {
        let j = i + 1;
        while (j < text.length && /\s/.test(text[j])) j++;
        if (text[j] === '}' || text[j] === ']') continue;
      }
      out += ch;
    }
    return out;
  }

  function parseJsonLoose(text) {
    const clean = text.replace(/^\uFEFF/, '');
    try {
      return JSON.parse(clean);
    } catch (firstError) {
      try {
        return JSON.parse(stripTrailingCommas(clean));
      } catch (ignored) {
        throw firstError;
      }
    }
  }

  async function pagePersonas() {
    const [data, kbData] = await Promise.all([api('/api/admin/personas'), api('/api/admin/knowledge')]);
    const kbs = kbData.knowledge_bases || [];
    const list = data.personas || [];
    $('#page').innerHTML = `
      <div class="row">
        <span class="badge">共 ${list.length} 张角色卡</span>
        <span class="tag">角色卡 = AI 的身份证：身份 + 性格 + 说话方式 + 关联知识库</span>
        <div class="spacer"></div>
        <button class="btn" id="import-persona">⇧ 导入 JSON</button>
        <button class="btn primary" id="new-persona">＋ 新建角色卡</button>
        <input type="file" id="persona-import-file" accept=".json,application/json" hidden>
      </div>
      ${list.length ? `<div class="grid auto">
        ${list.map((p) => `
          <section class="card persona-card">
            <div class="top">
              <div class="avatar">${avatar(p.avatar, '🌙')}</div>
              <div>
                <div class="name">${esc(p.name)}</div>
                <div class="tagline">${esc(p.tagline || '未填写一句话人设')}</div>
              </div>
            </div>
            <div class="desc">${esc(p.description || '未填写身份背景')}</div>
            <div class="meta">
              ${p.enabled ? '<span class="badge ok">启用</span>' : '<span class="badge">停用</span>'}
              ${p.is_public ? '<span class="badge brand">公开</span>' : '<span class="badge warn">私有</span>'}
              ${p.owner_id ? `<span class="badge brand">专属 · ${esc(p.owner_name || ('用户 ' + p.owner_id))}</span>` : ''}
              <span class="badge">知识库 ${(p.kb_ids || []).length}</span>
              <span class="badge">被使用 ${p.ai_friends || 0}</span>
            </div>
            <div class="actions">
              <button class="btn sm" data-edit="${p.id}">编辑</button>
              <button class="btn sm danger" data-del="${p.id}" data-name="${attr(p.name)}">删除</button>
            </div>
          </section>`).join('')}
      </div>` : `<section class="card">${emptyState('🪪', '还没有角色卡，先建一张吧', '<button class="btn primary" id="new-persona-2">＋ 新建角色卡</button>')}</section>`}
    `;
    const openNew = () => personaModal(null, kbs);
    $('#new-persona') ?.addEventListener('click', openNew);
    $('#new-persona-2')?.addEventListener('click', openNew);
    const importFile = $('#persona-import-file');
    $('#import-persona')?.addEventListener('click', () => importFile?.click());
    importFile?.addEventListener('change', () => {
      const file = importFile.files?.[0];
      if (!file) return;
      if (file.size > 5 * 1024 * 1024) {
        toast('JSON 文件不能超过 5MB', 'error');
        importFile.value = '';
        return;
      }
      const reader = new FileReader();
      reader.onload = async () => {
        let payload;
        try {
          payload = parseJsonLoose(String(reader.result || ''));
        } catch (err) {
          toast('JSON 解析失败：' + err.message, 'error');
          return;
        }
        const cards = Array.isArray(payload) ? payload
          : Array.isArray(payload?.personas) ? payload.personas
          : Array.isArray(payload?.characters) ? payload.characters
          : Array.isArray(payload?.cards) ? payload.cards
          : Array.isArray(payload?.data) ? payload.data
          : [payload];
        if (!confirm(`检测到 ${cards.length} 个角色卡条目，开始导入？`)) return;
        try {
          const result = await api('/api/admin/personas/import', { method: 'POST', body: payload });
          toast(`导入完成：成功 ${result.imported || 0}，跳过 ${result.skipped || 0}`, 'ok');
          if (result.errors?.length) console.warn('角色卡导入错误', result.errors);
          pagePersonas();
        } catch (err) {
          toast(err.message, 'error');
        } finally {
          importFile.value = '';
        }
      };
      reader.readAsText(file, 'utf-8');
    });
    $$('[data-edit]').forEach((btn) => {
      btn.onclick = () => personaModal(list.find((p) => String(p.id) === btn.dataset.edit), kbs);
    });
    $$('[data-del]').forEach((btn) => {
      btn.onclick = async () => {
        if (!confirm(`确定删除角色卡「${btn.dataset.name}」？`)) return;
        try {
          await api(`/api/admin/personas/${btn.dataset.del}`, { method: 'DELETE' });
          toast('已删除', 'ok');
          pagePersonas();
        } catch (err) { toast(err.message, 'error'); }
      };
    });
  }

  function personaModal(persona, kbs) {
    const p = persona || {};
    const selected = new Set(p.kb_ids || []);
    const kbChecks = kbs.length ? `<div class="checkbox-grid">
      ${kbs.map((kb) => `<label class="checkbox-item">
        <input type="checkbox" name="kb_ids" value="${kb.id}" ${selected.has(kb.id) ? 'checked' : ''}>
        <span>${esc(kb.name)}<span class="muted" style="font-size:11px"> · ${kb.chunks_count || 0} 条</span></span>
      </label>`).join('')}
    </div>` : '<p class="hint">还没有知识库，可先去「知识库」导入微信 / QQ 聊天记录，让这个角色学会别人的语气。</p>';
    const body = `
      <div class="form-grid">
        ${fText('角色名', 'name', p.name || '', { required: true, placeholder: '例如：小夜' })}
        ${fText('头像（emoji 或图片 URL）', 'avatar', p.avatar || '🌙', { placeholder: '🌙' })}
        ${fText('一句话人设', 'tagline', p.tagline || '', { full: true, placeholder: '住在你手机里的夜猫子' })}
        <label class="field full"><span>身份背景</span><textarea name="description" rows="3" placeholder="年龄、职业、生活状态、住在哪……">${esc(p.description || '')}</textarea></label>
        <label class="field"><span>性格</span><textarea name="personality" rows="3" placeholder="温柔但不黏人，有点小傲娇……">${esc(p.personality || '')}</textarea></label>
        <label class="field"><span>说话方式</span><textarea name="speaking_style" rows="3" placeholder="短句、口语、常用“诶”“嘛”，一次只说 1-3 句……">${esc(p.speaking_style || '')}</textarea></label>
        <label class="field full"><span>角色设定（system prompt）</span><textarea name="system_prompt" rows="3" placeholder="最高优先级的设定，例如：你就是小夜本人，不是助手也不是 AI……">${esc(p.system_prompt || '')}</textarea></label>
        <label class="field"><span>打招呼语</span><textarea name="greeting" rows="2" placeholder="我来了～今天过得怎么样？">${esc(p.greeting || '')}</textarea></label>
        ${fText('口头禅（逗号分隔）', 'catchphrases', (p.catchphrases || []).join('，'), { placeholder: '诶？, 嘛, 好耶' })}
        <div class="full"><div class="section-title">关联知识库（用于模仿语气）</div>${kbChecks}</div>
        <div class="full row" style="gap:20px;margin-top:4px">
          ${fSwitch('公开给所有用户', 'is_public', p.is_public !== false)}
          ${fSwitch('启用', 'enabled', p.enabled !== false)}
        </div>
      </div>`;
    openModal({
      title: persona ? `编辑角色卡 · ${p.name}` : '新建角色卡',
      body,
      wide: true,
      submitText: persona ? '保存修改' : '创建',
      onSubmit: async (form) => {
        const fd = new FormData(form);
        const payload = {
          name: (fd.get('name') || '').toString().trim(),
          avatar: (fd.get('avatar') || '').toString().trim(),
          tagline: (fd.get('tagline') || '').toString(),
          description: (fd.get('description') || '').toString(),
          personality: (fd.get('personality') || '').toString(),
          speaking_style: (fd.get('speaking_style') || '').toString(),
          system_prompt: (fd.get('system_prompt') || '').toString(),
          greeting: (fd.get('greeting') || '').toString(),
          catchphrases: (fd.get('catchphrases') || '').toString().split(/[,，;；、\s]+/).filter(Boolean),
          is_public: fd.get('is_public') === 'on',
          enabled: fd.get('enabled') === 'on',
          kb_ids: fd.getAll('kb_ids').map((x) => Number(x)),
        };
        if (!payload.name) throw new Error('角色名不能为空');
        if (persona) await api(`/api/admin/personas/${persona.id}`, { method: 'PUT', body: payload });
        else await api('/api/admin/personas', { method: 'POST', body: payload });
        toast('已保存', 'ok');
        pagePersonas();
      },
    });
  }

  // ------------------------------------------------------------------ knowledge
  async function pageKnowledge(kbId) {
    if (kbId) return kbDetail(Number(kbId));
    const data = await api('/api/admin/knowledge');
    const list = data.knowledge_bases || [];
    $('#page').innerHTML = `
      <div class="row">
        <span class="badge">共 ${list.length} 个知识库</span>
        <span class="tag">支持微信 / QQ 导出的 txt、json、csv、html</span>
        <div class="spacer"></div>
        <button class="btn primary" id="new-kb">＋ 新建知识库</button>
      </div>
      ${list.length ? `<div class="grid auto">${list.map((kb) => `
        <section class="card">
          <div class="kb-head">
            <div class="kb-icon">${kb.kind === 'style' ? '🎭' : '📖'}</div>
            <div style="flex:1;min-width:0">
              <div style="display:flex;align-items:center;gap:8px"><strong>${esc(kb.name)}</strong>
                ${kb.enabled ? '<span class="badge ok">启用</span>' : '<span class="badge">停用</span>'}</div>
              <div class="muted" style="font-size:12px;margin-top:2px">${esc(kb.description || '未填写说明')}</div>
            </div>
          </div>
          <div class="row" style="margin:12px 0">
            <span class="tag">文档 ${kb.docs_count}</span>
            <span class="tag">样本 ${kb.chunks_count}</span>
            <span class="tag">${kb.style_profile?.speaker ? `模仿：${esc(kb.style_profile.speaker)}` : '未学习'}</span>
            <span class="tag">关联角色 ${(kb.persona_ids || []).length}</span>
          </div>
          <div class="actions row">
            <a class="btn sm primary" href="#/knowledge/${kb.id}">导入 / 查看</a>
            <button class="btn sm" data-edit="${kb.id}">编辑</button>
            <button class="btn sm danger" data-del="${kb.id}" data-name="${attr(kb.name)}">删除</button>
          </div>
        </section>`).join('')}</div>` : `<section class="card">${emptyState('📚', '还没有知识库，导入一份聊天记录就能让 AI 学会特定语气', '<button class="btn primary" id="new-kb-2">＋ 新建知识库</button>')}</section>`}
    `;
    const openNew = () => kbModal(null);
    $('#new-kb')?.addEventListener('click', openNew);
    $('#new-kb-2')?.addEventListener('click', openNew);
    $$('[data-edit]').forEach((btn) => {
      btn.onclick = () => kbModal(list.find((kb) => String(kb.id) === btn.dataset.edit));
    });
    $$('[data-del]').forEach((btn) => {
      btn.onclick = async () => {
        if (!confirm(`删除知识库「${btn.dataset.name}」及其全部样本？`)) return;
        try {
          await api(`/api/admin/knowledge/${btn.dataset.del}`, { method: 'DELETE' });
          toast('已删除', 'ok');
          pageKnowledge();
        } catch (err) { toast(err.message, 'error'); }
      };
    });
  }

  function kbModal(kb) {
    const k = kb || {};
    openModal({
      title: kb ? `编辑知识库 · ${k.name}` : '新建知识库',
      body: `<div class="form-grid">
        ${fText('名称', 'name', k.name || '', { required: true, placeholder: '例如：小雨的说话方式' })}
        ${fSelect('类型', 'kind', [{ value: 'style', label: '语气模仿（聊天记录）' }, { value: 'fact', label: '资料库' }], k.kind || 'style')}
        <label class="field full"><span>说明</span><textarea name="description" rows="2">${esc(k.description || '')}</textarea></label>
        <div class="full">${fSwitch('启用', 'enabled', k.enabled !== false)}</div>
      </div>`,
      submitText: kb ? '保存' : '创建',
      onSubmit: async (form) => {
        const fd = new FormData(form);
        const payload = {
          name: (fd.get('name') || '').toString().trim(),
          kind: fd.get('kind'),
          description: (fd.get('description') || '').toString(),
          enabled: fd.get('enabled') === 'on',
        };
        if (!payload.name) throw new Error('名称不能为空');
        if (kb) {
          await api(`/api/admin/knowledge/${kb.id}`, { method: 'PUT', body: payload });
          toast('已保存', 'ok');
          kbDetail(kb.id);
        } else {
          const created = await api('/api/admin/knowledge', { method: 'POST', body: payload });
          toast('知识库已创建，下一步导入聊天记录', 'ok');
          location.hash = `#/knowledge/${created.id}`;
        }
      },
    });
  }

  async function kbDetail(kbId) {
    const data = await api(`/api/admin/knowledge/${kbId}`);
    const kb = data.knowledge_base;
    const docs = kb.documents || [];
    const chunks = await api(`/api/admin/knowledge/${kbId}/chunks?limit=8`);
    const profile = kb.style_profile || {};
    const metric = (k, v) => `<div class="profile-item"><div class="k">${k}</div><div class="v">${v}</div></div>`;
    $('#page').innerHTML = `
      <div class="row">
        <a class="btn ghost sm" href="#/knowledge">← 返回</a>
        <h3 style="font-size:16px">${esc(kb.name)}</h3>
        ${kb.enabled ? '<span class="badge ok">启用</span>' : '<span class="badge">停用</span>'}
        <span class="badge">文档 ${kb.docs_count} · 样本 ${kb.chunks_count}</span>
        <div class="spacer"></div>
        <button class="btn sm" id="kb-edit">编辑信息</button>
      </div>

      <div class="split">
        <div class="stack">
          <section class="card">
            <div class="card-head"><div><h3>导入聊天记录</h3><p class="sub">越长的真实对话，模仿效果越好；建议至少 200 条消息</p></div></div>
            <div class="import-tabs">
              <button class="tab active" data-tab="file">上传文件</button>
              <button class="tab" data-tab="text">粘贴文本</button>
            </div>
            <div id="tab-file">
              <label class="dropzone" id="dropzone">
                <input type="file" id="kb-file" accept=".txt,.json,.csv,.html,.htm,.log" hidden>
                <div id="drop-text"><strong>点击选择</strong> 或把文件拖进来<br><span style="font-size:12px">支持 txt / json / csv / html，自动识别微信、QQ 导出格式</span></div>
              </label>
              <div class="form-grid" style="margin-top:14px">
                ${fText('要模仿的说话人（可留空自动识别）', 'target_speaker', '', { placeholder: '例如：小雨；留空则自动选择消息最多的人' })}
              </div>
              <div class="row">
                <button class="btn" id="kb-preview">预览解析</button>
                <button class="btn primary" id="kb-import">开始导入</button>
                <span class="hint muted" id="kb-import-hint">文件最大 8MB</span>
              </div>
            </div>
            <div id="tab-text" class="hidden">
              <label class="field"><span>标题</span><input id="kb-text-title" placeholder="例如：QQ 聊天记录 2024"></label>
              <label class="field"><span>聊天记录内容</span><textarea id="kb-text-body" rows="8" placeholder="2024-05-01 12:00:00 小雨(123456)&#10;今天好热啊&#10;2024-05-01 12:01:02 我&#10;是啊，出去一趟快化了"></textarea></label>
              <div class="row">
                <input id="kb-text-speaker" placeholder="要模仿的说话人（可留空）" style="max-width:280px">
                <button class="btn primary" id="kb-text-import">导入文本</button>
              </div>
            </div>
            <div id="kb-result" style="margin-top:14px"></div>
          </section>

          <section class="card">
            <div class="card-head"><h3>已导入文档</h3><span class="badge">${docs.length}</span></div>
            ${docs.length ? `<div class="table-wrap"><table>
              <thead><tr><th>标题</th><th>来源</th><th>消息</th><th>样本</th><th>模仿对象</th><th>时间</th><th></th></tr></thead>
              <tbody>${docs.map((d) => `<tr>
                <td>${esc(d.title)}</td><td><span class="tag">${esc(d.source_type)}</span></td>
                <td>${d.message_count}</td><td>${d.chunk_count}</td><td>${esc(d.target_speaker || '—')}</td>
                <td class="muted">${timeAgo(d.created_at)}</td>
                <td class="table-actions"><button class="btn sm danger" data-del-doc="${d.id}">删除</button></td>
              </tr>`).join('')}</tbody></table></div>` : emptyState('📄', '还没有导入任何聊天记录')}
          </section>
        </div>

        <div class="stack">
          <section class="card">
            <div class="card-head"><h3>风格画像</h3><span class="badge">${profile.speaker ? esc(profile.speaker) : '未学习'}</span></div>
            ${profile.speaker ? `
              <div class="profile-grid">
                ${metric('样本条数', fmtNum(profile.messages))}
                ${metric('平均长度', `${profile.avg_len} 字`)}
                ${metric('Emoji/百条', profile.emoji_per_100)}
                ${metric('不带标点', `${Math.round((profile.no_punct_ratio || 0) * 100)}%`)}
              </div>
              <div style="margin-top:14px"><div class="section-title">高频词</div>
                <div class="row">${(profile.top_words || []).map((w) => `<span class="tag">${esc(w)}</span>`).join('') || '—'}</div></div>
              <div style="margin-top:12px"><div class="section-title">常见开头 / 结尾</div>
                <div class="row">${(profile.openers || []).map((w) => `<span class="tag">${esc(w)}…</span>`).join('')}${(profile.endings || []).map((w) => `<span class="tag">…${esc(w)}</span>`).join('')}</div></div>
              <div style="margin-top:12px"><div class="section-title">真实语句</div>
                ${(profile.sample_lines || []).map((l) => `<div class="sample">${esc(l)}</div>`).join('') || '<p class="hint">暂无</p>'}</div>
            ` : emptyState('🎭', '导入聊天记录后，这里会显示这个人的语气画像')}
          </section>

          <section class="card">
            <div class="card-head"><h3>对话样本</h3><span class="badge">展示前 8 条</span></div>
            ${(chunks.chunks || []).map((c) => `<div class="sample">${esc(c.content)}</div>`).join('') || '<p class="hint">暂无样本</p>'}
          </section>
        </div>
      </div>
    `;

    $('#kb-edit').onclick = () => kbModal(kb);
    $$('.tab').forEach((tab) => {
      tab.onclick = () => {
        $$('.tab').forEach((t) => t.classList.toggle('active', t === tab));
        $('#tab-file').classList.toggle('hidden', tab.dataset.tab !== 'file');
        $('#tab-text').classList.toggle('hidden', tab.dataset.tab !== 'text');
      };
    });

    const dz = $('#dropzone');
    const fileInput = $('#kb-file');
    let currentFile = null;
    const setFile = (file) => {
      currentFile = file;
      $('#drop-text').innerHTML = `<strong>${esc(file.name)}</strong><br><span style="font-size:12px">${bytes(file.size)}，点击可重新选择</span>`;
    };
    fileInput.onchange = () => { if (fileInput.files[0]) setFile(fileInput.files[0]); };
    dz.addEventListener('dragover', (e) => { e.preventDefault(); dz.classList.add('over'); });
    dz.addEventListener('dragleave', () => dz.classList.remove('over'));
    dz.addEventListener('drop', (e) => {
      e.preventDefault();
      dz.classList.remove('over');
      if (e.dataTransfer.files[0]) setFile(e.dataTransfer.files[0]);
    });
    const speaker = () => $('#page').querySelector('input[name="target_speaker"]').value.trim();

    const renderResult = (result, preview = false) => {
      const stats = [
        `识别到 <b>${fmtNum(result.messages)}</b> 条消息`,
        result.target_speaker ? `模仿对象：<b>${esc(result.target_speaker)}</b>` : '',
        result.samples !== undefined ? `生成 <b>${result.samples}</b> 条风格样本` : '',
        result.source_type ? `格式：${esc(result.source_type)}${result.format ? `（${esc(result.format)}）` : ''}` : '',
      ].filter(Boolean).join(' · ');
      const speakers = Object.entries(result.speakers || {}).slice(0, 8)
        .map(([k, v]) => `<span class="tag">${esc(k)} · ${v}</span>`).join('');
      $('#kb-result').innerHTML = `
        <div class="card tight" style="background:rgba(69,224,168,.06);border-color:rgba(69,224,168,.25)">
          <div style="font-size:13px">${preview ? '解析预览' : '导入完成'} · ${stats}</div>
          <div class="row" style="margin-top:8px">${speakers}</div>
        </div>
        ${(result.preview || []).map((s) => `<div class="sample" style="margin-top:8px">${esc(s)}</div>`).join('')}`;
    };

    $('#kb-preview').onclick = async () => {
      if (!currentFile) return toast('先选择要导入的文件', 'error');
      const fd = new FormData();
      fd.append('file', currentFile);
      const sp = speaker();
      if (sp) fd.append('target_speaker', sp);
      try {
        const res = await api(`/api/admin/knowledge/${kbId}/preview`, { method: 'POST', form: fd });
        renderResult(res.preview, true);
        toast('解析成功，确认无误后点击开始导入', 'ok');
      } catch (err) { toast(err.message, 'error'); }
    };

    $('#kb-import').onclick = async () => {
      if (!currentFile) return toast('先选择要导入的文件', 'error');
      const fd = new FormData();
      fd.append('file', currentFile);
      const sp = speaker();
      if (sp) fd.append('target_speaker', sp);
      const btn = $('#kb-import');
      btn.disabled = true;
      btn.textContent = '导入中…';
      try {
        const res = await api(`/api/admin/knowledge/${kbId}/import`, { method: 'POST', form: fd });
        toast(`导入成功：${res.result.messages} 条消息，${res.result.samples} 条样本`, 'ok');
        renderResult(res.result);
        setTimeout(() => kbDetail(kbId), 1200);
      } catch (err) {
        toast(err.message, 'error');
      } finally {
        btn.disabled = false;
        btn.textContent = '开始导入';
      }
    };

    $('#kb-text-import').onclick = async () => {
      const text = $('#kb-text-body').value;
      if (!text.trim()) return toast('先粘贴聊天记录', 'error');
      const btn = $('#kb-text-import');
      btn.disabled = true;
      try {
        const res = await api(`/api/admin/knowledge/${kbId}/import-text`, {
          method: 'POST',
          body: {
            text,
            title: $('#kb-text-title').value.trim() || '粘贴导入',
            target_speaker: $('#kb-text-speaker').value.trim() || null,
          },
        });
        toast(`导入成功：${res.result.samples} 条样本`, 'ok');
        renderResult(res.result);
        setTimeout(() => kbDetail(kbId), 1200);
      } catch (err) {
        toast(err.message, 'error');
      } finally { btn.disabled = false; }
    };

    $$('[data-del-doc]').forEach((btn) => {
      btn.onclick = async () => {
        if (!confirm('删除这份文档及其全部风格样本？')) return;
        try {
          await api(`/api/admin/knowledge/documents/${btn.dataset.delDoc}`, { method: 'DELETE' });
          toast('已删除', 'ok');
          kbDetail(kbId);
        } catch (err) { toast(err.message, 'error'); }
      };
    });
  }

  // ------------------------------------------------------------------ providers
  async function pageProviders() {
    const [data, userData] = await Promise.all([
      api('/api/admin/providers'),
      api('/api/admin/users?page=1&size=100'),
    ]);
    const list = data.providers || [];
    const users = userData.users || [];
    const presets = data.presets || [];
    const kindLabel = { chat: '对话模型', image: '图像模型', analysis: '分析模型', vision: '视觉模型', stt: '语音转写模型' };
    const roles = ['chat', 'image', 'analysis', 'vision', 'stt'].map((kind) => {
      const enabled = list.filter((p) => p.kind === kind && p.enabled && !p.owner_user_id);
      const role = data.roles[kind] || {};
      const configured = role.configured !== undefined ? Boolean(role.configured) : enabled.length > 0;
      const names = [...new Set(enabled.map((e) => e.name))];
      const desc = names.length ? names.slice(0, 2).join(' / ') : configured ? '复用对话模型' : '未配置';
      return `<div class="card tight" style="display:flex;justify-content:space-between;align-items:center;gap:10px">
        <div style="min-width:0"><div style="font-size:13px">${role.label || kindLabel[kind]}</div>
        <div class="muted" style="font-size:11.5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(desc)}</div></div>
        <span class="badge ${configured ? 'ok' : 'warn'}">${configured ? '已就绪' : '演示模式'}</span></div>`;
    }).join('');
    $('#page').innerHTML = `
      <div class="grid cols-3">${roles}</div>
      <section class="card">
        <div class="card-head">
          <div><h3>快速添加</h3><p class="sub">都是 OpenAI 兼容协议，填 API Key 即可用</p></div>
          <button class="btn primary" id="new-provider">＋ 手动新增</button>
        </div>
        <div class="preset-row">
          ${presets.map((p, i) => `<button class="preset" data-preset="${i}">${esc(p.label)}</button>`).join('')}
        </div>
      </section>
      ${list.length ? `<div class="grid auto">${list.map((p) => `
        <section class="card provider-card">
          <div class="p-head">
            <div style="min-width:0">
              <div class="p-name">${esc(p.name)} <span class="kind-tag ${esc(p.kind)}">${kindLabel[p.kind] || p.kind}</span></div>
              <div class="p-url">${esc(p.base_url)} · ${esc(p.model || '未填模型')}</div>
            </div>
            <span class="badge ${p.enabled ? 'ok' : ''}">${p.enabled ? '启用' : '停用'}</span>
          </div>
          <div class="row">
            <span class="tag">优先级 ${p.priority}</span>
            <span class="tag">Key ${p.has_key ? esc(p.api_key) : '未填'}</span>
            <span class="badge ${p.status === 'ok' ? 'ok' : p.status === 'error' ? 'err' : ''}">
              ${p.status === 'ok' ? '连接正常' : p.status === 'error' ? '连接失败' : '未测试'}</span>
            ${p.owner_user_id ? `<span class="badge brand">专属 · ${esc(p.owner_name || ('用户 ' + p.owner_user_id))}</span>` : '<span class="tag">全局</span>'}
          </div>
          ${p.last_error ? `<div class="hint" style="color:#ffb3c0;font-size:11.5px">${esc(p.last_error)}</div>` : ''}
          <div class="row">
            <button class="btn sm" data-test="${p.id}">测试</button>
            <button class="btn sm" data-edit="${p.id}">编辑</button>
            <button class="btn sm danger" data-del="${p.id}" data-name="${attr(p.name)}">删除</button>
          </div>
        </section>`).join('')}</div>` : `<section class="card">${emptyState('🧠', '还没有模型提供商。不配置也能跑，但只会返回演示回复', '<button class="btn primary" id="new-provider-2">＋ 新增提供商</button>')}</section>`}
    `;

    const openNew = (preset) => providerModal(null, preset, users);
    $('#new-provider')?.addEventListener('click', () => openNew(null));
    $('#new-provider-2')?.addEventListener('click', () => openNew(null));
    $$('[data-preset]').forEach((btn) => {
      btn.onclick = () => openNew(presets[Number(btn.dataset.preset)]);
    });
    $$('[data-edit]').forEach((btn) => {
      btn.onclick = () => providerModal(list.find((p) => String(p.id) === btn.dataset.edit), null, users);
    });
    $$('[data-test]').forEach((btn) => {
      btn.onclick = async () => {
        btn.disabled = true;
        btn.textContent = '测试中…';
        try {
          const res = await api(`/api/admin/providers/${btn.dataset.test}/test`, { method: 'POST' });
          if (res.result.ok) toast(`连接成功（${res.result.latency_ms}ms）：${res.result.reply || 'OK'}`, 'ok');
          else toast(`连接失败：${res.result.error || '未知错误'}`, 'error');
          pageProviders();
        } catch (err) {
          toast(err.message, 'error');
        } finally {
          btn.disabled = false;
          btn.textContent = '测试';
        }
      };
    });
    $$('[data-del]').forEach((btn) => {
      btn.onclick = async () => {
        if (!confirm(`删除提供商「${btn.dataset.name}」？`)) return;
        try {
          await api(`/api/admin/providers/${btn.dataset.del}`, { method: 'DELETE' });
          toast('已删除', 'ok');
          pageProviders();
        } catch (err) { toast(err.message, 'error'); }
      };
    });
  }

  function providerModal(provider, preset, users, ownerId, afterSave) {
    const p = provider || {};
    const base = preset || {};
    const ownerValue = p.owner_user_id ?? ownerId ?? '';
    const ownerOptions = [{ value: '', label: '全局（所有用户可用）' }].concat(
      (users || []).map((u) => ({ value: u.id, label: `${u.nickname} (@${u.username})` }))
    );
    const body = `<div class="form-grid">
      ${fText('名称', 'name', p.name || base.label || '', { required: true, placeholder: '例如：DeepSeek 主力' })}
      ${fSelect('用途', 'kind', [
        { value: 'chat', label: '对话模型（聊天 / 主动消息）' },
        { value: 'image', label: '图像模型（生成图片）' },
        { value: 'analysis', label: '分析模型（记忆重要性判断）' },
        { value: 'vision', label: '视觉模型（聊天图片识别）' },
        { value: 'stt', label: '语音转写模型（GLM-ASR / Whisper）' },
      ], p.kind || base.kind || 'chat')}
      <label class="field full"><span>Base URL</span>
        <input name="base_url" value="${attr(p.base_url || base.base_url || '')}" placeholder="https://api.deepseek.com/v1" required>
        <span class="hint">OpenAI 兼容地址，可写 https://api.xxx.com 或带 /v1</span>
        <span class="hint">语音转写：智谱填 https://open.bigmodel.cn/api/paas/v4（模型 glm-asr-2512），Whisper 填 https://api.openai.com/v1</span>
        <span class="hint">本地 whisper.cpp 填 http://127.0.0.1:8080/inference（模型 tiny，路径必须带 /inference）</span></label>
      <label class="field full"><span>API Key</span>
        <input name="api_key" type="password" placeholder="${p.has_key ? '已保存，留空则不修改' : 'sk-...'}" value="">
        <span class="hint">保存在本地数据库，展示时会脱敏</span></label>
      ${fText('模型名称', 'model', p.model || base.model || '', { placeholder: 'deepseek-chat' })}
      ${fText('优先级（数字越小越优先）', 'priority', p.priority ?? 10, { type: 'number' })}
      ${fSelect('专属用户（留空为全局 Key）', 'owner_user_id', ownerOptions, ownerValue, {
        hint: '选择用户后，该用户只会用这把 Key，不会消耗全局 Key 的额度',
      })}
      <div class="full row" style="gap:20px">${fSwitch('启用', 'enabled', p.enabled !== false)}</div>
      <label class="field full"><span>高级参数（JSON，可选）</span>
        <textarea name="extra" rows="2" placeholder='{"headers": {"X-Foo": "bar"}, "body": {"top_p": 0.9}}'>${esc(JSON.stringify(p.extra || {}))}</textarea>
        <span class="hint">可加自定义 header 或请求体字段，例如某些代理需要的键</span></label>
    </div>`;
    openModal({
      title: provider ? `编辑提供商 · ${p.name}` : '新增模型提供商',
      body,
      wide: true,
      onSubmit: async (form) => {
        const fd = new FormData(form);
        let extra = {};
        try {
          const raw = (fd.get('extra') || '').toString().trim();
          extra = raw ? JSON.parse(raw) : {};
        } catch { throw new Error('高级参数不是合法 JSON'); }
        const payload = {
          name: (fd.get('name') || '').toString().trim(),
          kind: fd.get('kind'),
          base_url: (fd.get('base_url') || '').toString().trim(),
          api_key: (fd.get('api_key') || '').toString().trim(),
          model: (fd.get('model') || '').toString().trim(),
          priority: Number(fd.get('priority') || 10),
          owner_user_id: fd.get('owner_user_id') ? Number(fd.get('owner_user_id')) : null,
          enabled: fd.get('enabled') === 'on',
          extra,
        };
        if (!payload.name || !payload.base_url) throw new Error('名称和 Base URL 必填');
        if (provider) await api(`/api/admin/providers/${provider.id}`, { method: 'PUT', body: payload });
        else await api('/api/admin/providers', { method: 'POST', body: payload });
        toast('已保存，建议点一下「测试」', 'ok');
        if (typeof afterSave === 'function') afterSave();
        else pageProviders();
      },
    });
  }

  // ------------------------------------------------------------------ users
  async function userKeysDialog(user, users) {
    if (!user) return;
    const data = await api(`/api/admin/providers?owner_user_id=${user.id}`);
    const list = data.providers || [];
    const body = `
      <p class="hint">给「${esc(user.nickname)}」分配专属模型 Key 后，该用户的 AI 对话、主动消息、记忆分析只会使用这里的 Key，不会消耗全局 Key 的 token。建议至少配置一个「对话模型」。</p>
      <div class="row" style="margin:12px 0">
        <button class="btn primary sm" id="user-key-add">＋ 新增专属 Key</button>
        <span class="badge">${list.length} 个</span>
      </div>
      <div id="user-key-list">
        ${list.length ? list.map((p) => `
          <div class="card tight" style="margin-bottom:8px">
            <div class="row" style="justify-content:space-between">
              <div><strong>${esc(p.name)}</strong> <span class="kind-tag ${esc(p.kind)}">${p.kind}</span>
                <div class="muted mono" style="font-size:11px">${esc(p.base_url)} · ${esc(p.model)}</div></div>
              <div class="row">
                <span class="badge ${p.enabled ? 'ok' : ''}">${p.enabled ? '启用' : '停用'}</span>
                <button class="btn sm danger" data-uk-del="${p.id}">删除</button>
              </div>
            </div>
          </div>`).join('') : '<p class="hint">还没有专属 Key，这个用户目前使用全局模型。</p>'}
      </div>`;
    openModal({
      title: `专属模型 Key · ${user.nickname}`,
      body,
      wide: true,
      submitText: '关闭',
      onSubmit: async () => {},
      after: (form) => {
        form.querySelector('#user-key-add').onclick = () => {
          providerModal(null, { label: `${user.nickname} 专属` }, users, user.id, () => {
            setTimeout(() => userKeysDialog(user, users), 300);
          });
        };
        $$('[data-uk-del]', form).forEach((btn) => {
          btn.onclick = async () => {
            if (!confirm('删除这把专属 Key？该用户之后会改用全局模型。')) return;
            await api(`/api/admin/providers/${btn.dataset.ukDel}`, { method: 'DELETE' });
            toast('已删除', 'ok');
            setTimeout(() => userKeysDialog(user, users), 300);
          };
        });
      },
    });
  }

  async function pageUsers(search = '') {
    const data = await api(`/api/admin/users?page=1&size=50&q=${encodeURIComponent(search)}`);
    const list = data.users || [];
    $('#page').innerHTML = `
      <section class="card">
        <div class="card-head">
          <div><h3>账号列表</h3><p class="sub">共 ${data.total} 个账号，账号数据全部存于服务端数据库</p></div>
          <div class="row">
            <input id="user-search" placeholder="搜索用户名 / 昵称" style="width:220px" value="${attr(search)}">
            <button class="btn sm" id="user-search-btn">搜索</button>
          </div>
        </div>
        <div class="table-wrap"><table>
          <thead><tr><th>用户</th><th>角色</th><th>状态</th><th>好友</th><th>AI</th><th>专属Key</th><th>消息</th><th>最近活跃</th><th>注册时间</th><th></th></tr></thead>
          <tbody>
            ${list.length ? list.map((u) => `<tr>
              <td><div class="row"><div class="avatar" style="width:32px;height:32px;border-radius:9px;font-size:15px">${avatar(u.avatar, '👤')}</div>
                <div><div>${esc(u.nickname)}</div><div class="muted mono" style="font-size:11px">@${esc(u.username)}</div></div></div></td>
              <td>${u.role === 'admin' ? '<span class="badge brand">管理员</span>' : '<span class="badge">用户</span>'}</td>
              <td>${u.status === 'active' ? '<span class="badge ok">正常</span>' : '<span class="badge err">禁用</span>'}</td>
              <td>${u.friends || 0}</td><td>${u.ai_friends || 0}</td>
              <td>${u.private_keys ? `<span class="badge brand">${u.private_keys}</span>` : '<span class="muted">—</span>'}</td>
              <td>${u.messages || 0}</td>
              <td class="muted">${timeAgo(u.last_seen_at)}</td>
              <td class="muted">${fmtTime(u.created_at)}</td>
              <td class="table-actions">
                <button class="btn sm" data-edit="${u.id}">编辑</button>
                <button class="btn sm" data-keys="${u.id}" data-name="${attr(u.nickname)}">专属Key</button>
                <button class="btn sm" data-pwd="${u.id}" data-name="${attr(u.username)}">改密</button>
                <button class="btn sm danger" data-del="${u.id}" data-name="${attr(u.username)}">删除</button>
              </td>
            </tr>`).join('') : `<tr><td colspan="10">${emptyState('👤', '没有匹配的账号')}</td></tr>`}
          </tbody>
        </table></div>
      </section>`;
    const doSearch = () => pageUsers($('#user-search').value.trim());
    $('#user-search-btn').onclick = doSearch;
    $('#user-search').addEventListener('keydown', (e) => { if (e.key === 'Enter') doSearch(); });
    $$('[data-edit]').forEach((btn) => {
      btn.onclick = () => {
        const u = list.find((x) => String(x.id) === btn.dataset.edit);
        openModal({
          title: `编辑用户 · ${u.username}`,
          body: `<div class="form-grid">
            ${fText('昵称', 'nickname', u.nickname)}
            ${fSelect('角色', 'role', [{ value: 'user', label: '普通用户' }, { value: 'admin', label: '管理员' }], u.role)}
            ${fSelect('状态', 'status', [{ value: 'active', label: '正常' }, { value: 'disabled', label: '禁用' }], u.status)}
            ${fText('城市（用于天气）', 'city', u.city || '', { placeholder: '杭州' })}
          </div>`,
          onSubmit: async (form) => {
            const fd = new FormData(form);
            await api(`/api/admin/users/${u.id}`, {
              method: 'PUT',
              body: {
                nickname: fd.get('nickname'),
                role: fd.get('role'),
                status: fd.get('status'),
                city: fd.get('city'),
              },
            });
            toast('已保存', 'ok');
            pageUsers(search);
          },
        });
      };
    });
    $$('[data-pwd]').forEach((btn) => {
      btn.onclick = () => {
        openModal({
          title: `重置密码 · ${btn.dataset.name}`,
          body: fText('新密码（至少 6 位）', 'password', '', { type: 'password', required: true }),
          onSubmit: async (form) => {
            const password = new FormData(form).get('password').toString();
            if (password.length < 6) throw new Error('密码至少 6 位');
            await api(`/api/admin/users/${btn.dataset.pwd}/reset-password`, { method: 'POST', body: { password } });
            toast('密码已重置，用户下次登录需修改', 'ok');
          },
        });
      };
    });
    $$('[data-keys]').forEach((btn) => {
      btn.onclick = () => userKeysDialog(list.find((x) => String(x.id) === btn.dataset.keys), list);
    });
    $$('[data-del]').forEach((btn) => {
      btn.onclick = async () => {
        if (!confirm(`删除账号 ${btn.dataset.name}？其好友关系与 AI 会话将一并失效。`)) return;
        try {
          await api(`/api/admin/users/${btn.dataset.del}`, { method: 'DELETE' });
          toast('已删除', 'ok');
          pageUsers(search);
        } catch (err) { toast(err.message, 'error'); }
      };
    });
  }

  // ------------------------------------------------------------------ plugins
  async function pagePlugins() {
    const data = await api('/api/admin/plugins');
    const list = data.plugins || [];
    $('#page').innerHTML = `
      <section class="card">
        <div class="card-head">
          <div><h3>插件管理</h3><p class="sub">${esc(data.compat)}</p></div>
          <div class="row">
            <input type="file" id="plugin-file" accept=".zip" hidden>
            <button class="btn primary" id="plugin-upload">上传插件 zip</button>
            <button class="btn" id="plugin-reload">重新加载全部</button>
          </div>
        </div>
        <div class="row"><span class="tag">内置示例：${(data.builtin || []).join('、')}</span>
          <span class="tag">插件目录：/data/plugins</span>
          <span class="tag">支持 metadata.yaml / _conf_schema.json</span></div>
      </section>
      ${list.length ? `<div class="grid auto">${list.map((p) => `
        <section class="card plugin-card">
          <div class="p-top">
            <div>
              <div class="p-name">${esc(p.name)} <span class="muted" style="font-size:11.5px">v${esc(p.version)}</span></div>
              <div class="muted" style="font-size:11.5px">${esc(p.author || '未知作者')} · ${esc(p.source === 'builtin' ? '内置' : '本地上传')}</div>
            </div>
            <span class="badge ${p.enabled && p.status === 'loaded' ? 'ok' : p.status === 'failed' ? 'err' : ''}">
              ${p.status === 'failed' ? '加载失败' : p.enabled ? '运行中' : '已停用'}</span>
          </div>
          <div class="p-desc">${esc(p.description || '没有描述')}</div>
          <div class="row">${(p.commands || []).slice(0, 8).map((c) => `<span class="tag">/${esc(c)}</span>`).join('') || '<span class="tag">无命令</span>'}</div>
          ${p.error ? `<div class="hint" style="color:#ffb3c0;font-size:11.5px">${esc(p.error)}</div>` : ''}
          <div class="p-foot">
            ${p.enabled ? `<button class="btn sm" data-toggle="${p.slug}" data-on="0">停用</button>`
      : `<button class="btn sm primary" data-toggle="${p.slug}" data-on="1">启用</button>`}
            ${Object.keys(p.config_schema || {}).length ? `<button class="btn sm" data-config="${p.slug}">配置</button>` : ''}
            <button class="btn sm" data-source="${p.slug}">源码</button>
            <button class="btn sm danger" data-del="${p.slug}" data-name="${attr(p.name)}">删除</button>
          </div>
        </section>`).join('')}</div>` : `<section class="card">${emptyState('🧩', '还没有插件，上传一个 zip 试试')}</section>`}
    `;

    const upload = async (file) => {
      const fd = new FormData();
      fd.append('file', file);
      try {
        const res = await api('/api/admin/plugins/upload', { method: 'POST', form: fd });
        if (res.status === 'failed') toast(`插件已上传但加载失败：${res.error}`, 'error');
        else toast('插件已安装并加载', 'ok');
        pagePlugins();
      } catch (err) { toast(err.message, 'error'); }
    };
    $('#plugin-upload').onclick = () => $('#plugin-file').click();
    $('#plugin-file').onchange = () => { if ($('#plugin-file').files[0]) upload($('#plugin-file').files[0]); };
    $('#plugin-reload').onclick = async () => {
      try {
        const res = await api('/api/admin/plugins/reload', { method: 'POST' });
        toast(`已重新加载 ${res.plugins} 个插件`, 'ok');
        pagePlugins();
      } catch (err) { toast(err.message, 'error'); }
    };
    $$('[data-toggle]').forEach((btn) => {
      btn.onclick = async () => {
        const on = btn.dataset.on === '1';
        try {
          await api(`/api/admin/plugins/${btn.dataset.toggle}/${on ? 'enable' : 'disable'}`, { method: 'POST' });
          toast(on ? '插件已启用' : '插件已停用', 'ok');
          pagePlugins();
        } catch (err) { toast(err.message, 'error'); }
      };
    });
    $$('[data-config]').forEach((btn) => {
      btn.onclick = async () => {
        const slug = btn.dataset.config;
        const detail = await api(`/api/admin/plugins/${slug}`);
        const plugin = detail.plugin;
        const schema = plugin.config_schema || {};
        const cfg = plugin.config || {};
        const fields = Object.entries(schema).map(([key, spec]) => {
          const value = cfg[key] ?? spec.default ?? '';
          const label = spec.description || key;
          if (spec.type === 'bool') return `<div class="field">${fSwitch(label, `cfg_${key}`, Boolean(value))}</div>`;
          if (spec.type === 'select' || spec.type === 'string' && Array.isArray(spec.options)) {
            return fSelect(label, `cfg_${key}`, (spec.options || []).map((o) => ({ value: o, label: o })), value);
          }
          if (spec.type === 'int' || spec.type === 'number') {
            return fText(label, `cfg_${key}`, value, { type: 'number' });
          }
          return fText(label, `cfg_${key}`, value);
        }).join('');
        openModal({
          title: `插件配置 · ${plugin.name}`,
          body: fields || '<p class="muted">该插件没有可配置项</p>',
          onSubmit: async (form) => {
            const fd = new FormData(form);
            const next = {};
            Object.entries(schema).forEach(([key, spec]) => {
              let v = fd.get(`cfg_${key}`);
              if (spec.type === 'bool') v = v === 'on';
              else if (spec.type === 'int' || spec.type === 'number') v = Number(v || 0);
              next[key] = v;
            });
            await api(`/api/admin/plugins/${slug}/config`, { method: 'PUT', body: { config: next } });
            toast('配置已保存', 'ok');
            pagePlugins();
          },
        });
      };
    });
    $$('[data-source]').forEach((btn) => {
      btn.onclick = async () => {
        try {
          const detail = await api(`/api/admin/plugins/${btn.dataset.source}`);
          openModal({
            title: `源码 · ${detail.plugin.name}`,
            wide: true,
            submitText: '关闭',
            body: `<pre class="code">${esc(detail.plugin.source || '未找到 main.py / plugin.py')}</pre>`,
            onSubmit: async () => {},
          });
        } catch (err) { toast(err.message, 'error'); }
      };
    });
    $$('[data-del]').forEach((btn) => {
      btn.onclick = async () => {
        if (!confirm(`删除插件「${btn.dataset.name}」及其目录？`)) return;
        try {
          await api(`/api/admin/plugins/${btn.dataset.del}`, { method: 'DELETE' });
          toast('已删除', 'ok');
          pagePlugins();
        } catch (err) { toast(err.message, 'error'); }
      };
    });
  }

  // ------------------------------------------------------------------ tasks
  async function pageTasks() {
    const [data, options] = await Promise.all([api('/api/admin/tasks'), api('/api/admin/tasks/options')]);
    const list = data.tasks || [];
    const typeLabel = { message: '定时消息', proactive: '主动搭话', moment: '日常动态', plugin: '插件命令' };
    const scheduleText = (s) => {
      if (!s || !s.kind) return '手动';
      if (s.kind === 'once') return `一次性 · ${fmtTime(s.at)}`;
      if (s.kind === 'interval') return `每 ${s.minutes} 分钟`;
      if (s.kind === 'daily') return `每天 ${s.time}`;
      if (s.kind === 'cron') return `Cron · ${s.expression}`;
      return '仅手动';
    };
    $('#page').innerHTML = `
      <div class="row">
        <span class="badge">共 ${list.length} 个任务</span>
        <span class="tag">任务到点由服务端调度器执行，适合早安提醒 / 定期关心 / 角色日常</span>
        <div class="spacer"></div>
        <button class="btn primary" id="new-task">＋ 新建任务</button>
      </div>
      <section class="card">
        <div class="table-wrap"><table>
          <thead><tr><th>任务</th><th>类型</th><th>计划</th><th>下次执行</th><th>最近执行</th><th>次数</th><th>状态</th><th></th></tr></thead>
          <tbody>
            ${list.length ? list.map((t) => `<tr>
              <td><div>${esc(t.name)}</div><div class="muted" style="font-size:11px">#${t.id} · 用户 ${t.user_id ?? '—'} · AI ${t.ai_friend_id ?? '—'}</div></td>
              <td><span class="tag">${typeLabel[t.type] || t.type}</span></td>
              <td class="muted">${esc(scheduleText(t.schedule))}</td>
              <td class="muted">${t.next_run_at ? fmtTime(t.next_run_at) : '—'}</td>
              <td class="muted">${t.last_run_at ? timeAgo(t.last_run_at) : '—'}</td>
              <td>${t.run_count}</td>
              <td>${t.enabled ? '<span class="badge ok">启用</span>' : '<span class="badge">停用</span>'}</td>
              <td class="table-actions">
                <button class="btn sm" data-run="${t.id}">执行</button>
                <button class="btn sm" data-runs="${t.id}">历史</button>
                <button class="btn sm" data-edit="${t.id}">编辑</button>
                <button class="btn sm danger" data-del="${t.id}" data-name="${attr(t.name)}">删除</button>
              </td>
            </tr>`).join('') : `<tr><td colspan="8">${emptyState('⏰', '还没有未来任务，建一个「每天早上 8 点说早安」试试')}</td></tr>`}
          </tbody>
        </table></div>
      </section>`;

    const openNew = (task) => taskModal(task, options, data.types, data.schedule_kinds);
    $('#new-task').onclick = () => openNew(null);
    $$('[data-edit]').forEach((btn) => {
      btn.onclick = () => openNew(list.find((t) => String(t.id) === btn.dataset.edit));
    });
    $$('[data-run]').forEach((btn) => {
      btn.onclick = async () => {
        btn.disabled = true;
        btn.textContent = '执行中…';
        try {
          const res = await api(`/api/admin/tasks/${btn.dataset.run}/run`, { method: 'POST' });
          const r = res.run;
          openModal({
            title: r.status === 'ok' ? '执行成功' : '执行失败',
            submitText: '关闭',
            body: `<div class="kv"><div class="k">状态</div><div class="v"><span class="badge ${r.status === 'ok' ? 'ok' : 'err'}">${esc(r.status)}</span></div>
              <div class="k">输出</div><div class="v">${esc(r.output || '（无）')}</div>
              <div class="k">耗时</div><div class="v">${r.started_at && r.finished_at ? `${((new Date(r.finished_at) - new Date(r.started_at)) / 1000).toFixed(1)}s` : '—'}</div></div>`,
            onSubmit: async () => {},
          });
          pageTasks();
        } catch (err) { toast(err.message, 'error'); }
        finally { btn.disabled = false; btn.textContent = '执行'; }
      };
    });
    $$('[data-runs]').forEach((btn) => {
      btn.onclick = async () => {
        try {
          const res = await api(`/api/admin/tasks/${btn.dataset.runs}/runs`);
          openModal({
            title: '执行历史',
            wide: true,
            submitText: '关闭',
            body: res.runs.length ? `<div class="table-wrap"><table>
              <thead><tr><th>时间</th><th>状态</th><th>输出</th></tr></thead>
              <tbody>${res.runs.map((r) => `<tr><td class="muted">${fmtTime(r.started_at)}</td>
                <td><span class="badge ${r.status === 'ok' ? 'ok' : 'err'}">${esc(r.status)}</span></td>
                <td style="max-width:460px;word-break:break-word">${esc(r.output || '')}</td></tr>`).join('')}</tbody>
            </table></div>` : emptyState('🕳️', '还没有执行记录'),
            onSubmit: async () => {},
          });
        } catch (err) { toast(err.message, 'error'); }
      };
    });
    $$('[data-del]').forEach((btn) => {
      btn.onclick = async () => {
        if (!confirm(`删除任务「${btn.dataset.name}」？`)) return;
        try {
          await api(`/api/admin/tasks/${btn.dataset.del}`, { method: 'DELETE' });
          toast('已删除', 'ok');
          pageTasks();
        } catch (err) { toast(err.message, 'error'); }
      };
    });
  }

  function taskModal(task, options, types, scheduleKinds) {
    const t = task || {};
    const s = t.schedule || { kind: 'once' };
    const p = t.payload || {};
    const onceValue = s.at ? new Date(s.at).toISOString().slice(0, 16) : '';
    const userOptions = [{ value: '', label: '（不绑定）' }].concat((options.users || []).map((u) => ({ value: u.id, label: `${u.nickname} (@${u.username})` })));
    const aiOptions = (options.ai_friends || []).map((a) => ({ value: a.id, label: `${a.name} · 用户${a.user_id}` }));
    const body = `<div class="form-grid">
      ${fText('任务名称', 'name', t.name || '', { required: true, placeholder: '例如：每天 8 点说早安' })}
      ${fSelect('任务类型', 'type', types.map((x) => ({ value: x.value, label: x.label })), t.type || 'message', { id: 'task-type' })}
      ${fSelect('绑定用户', 'user_id', userOptions, t.user_id ?? '', { id: 'task-user' })}
      ${fSelect('绑定 AI 好友', 'ai_friend_id', [{ value: '', label: '（自动选择）' }].concat(aiOptions), t.ai_friend_id ?? '', { id: 'task-ai' })}
      ${fSelect('计划类型', 'schedule_kind', scheduleKinds.map((x) => ({ value: x.value, label: x.label })), s.kind || 'once', { id: 'task-sch' })}
      <div id="sch-once" class="${s.kind === 'once' || !s.kind ? '' : 'hidden'}">${fText('执行时间', 'schedule_at', onceValue, { type: 'datetime-local', hint: '一次性任务，时间必须在未来' })}</div>
      <div id="sch-interval" class="${s.kind === 'interval' ? '' : 'hidden'}">${fText('间隔分钟', 'schedule_minutes', s.minutes || 120, { type: 'number', min: 1 })}</div>
      <div id="sch-daily" class="${s.kind === 'daily' ? '' : 'hidden'}">${fText('每天时间', 'schedule_time', s.time || '08:30', { type: 'time' })}</div>
      <div id="sch-cron" class="${s.kind === 'cron' ? '' : 'hidden'}">${fText('Cron 表达式', 'schedule_cron', s.expression || '0 9 * * *', { hint: '分 时 日 月 周，例如 0 9 * * *' })}</div>
      <div class="full" id="payload-message">${fArea('消息文案（留空则由模型生成）', 'payload_text', p.text || '', { rows: 2, placeholder: '早上好呀，今天也要好好吃饭。' })}</div>
      <div class="full hidden" id="payload-plugin">${fText('插件命令', 'payload_command', p.command || '', { placeholder: '/运势' })}</div>
      <div class="full">${fSwitch('启用任务', 'enabled', t.enabled !== false)}</div>
    </div>`;
    openModal({
      title: task ? `编辑任务 · ${t.name}` : '新建未来任务',
      body,
      wide: true,
      onSubmit: async (form) => {
        const fd = new FormData(form);
        const kind = fd.get('schedule_kind');
        const schedule = { kind };
        if (kind === 'once') {
          const at = fd.get('schedule_at');
          if (!at) throw new Error('请选择执行时间');
          schedule.at = new Date(at).toISOString();
        } else if (kind === 'interval') {
          schedule.minutes = Math.max(1, Number(fd.get('schedule_minutes') || 60));
        } else if (kind === 'daily') {
          schedule.time = fd.get('schedule_time') || '08:30';
        } else if (kind === 'cron') {
          const expr = (fd.get('schedule_cron') || '').toString().trim();
          if (expr.split(/\s+/).length !== 5) throw new Error('Cron 表达式需要 5 段');
          schedule.expression = expr;
        }
        const type = fd.get('type');
        const payload = {};
        if (type === 'message' || type === 'moment') payload.text = (fd.get('payload_text') || '').toString();
        if (type === 'plugin') payload.command = (fd.get('payload_command') || '').toString();
        const bodyData = {
          name: (fd.get('name') || '').toString().trim(),
          type,
          schedule,
          payload,
          user_id: fd.get('user_id') ? Number(fd.get('user_id')) : null,
          ai_friend_id: fd.get('ai_friend_id') ? Number(fd.get('ai_friend_id')) : null,
          enabled: fd.get('enabled') === 'on',
        };
        if (!bodyData.name) throw new Error('任务名称必填');
        if (task) await api(`/api/admin/tasks/${task.id}`, { method: 'PUT', body: bodyData });
        else await api('/api/admin/tasks', { method: 'POST', body: bodyData });
        toast('任务已保存', 'ok');
        pageTasks();
      },
      after: (form) => {
        const sch = form.querySelector('#task-sch');
        const updateSch = () => {
          const kind = sch.value;
          ['once', 'interval', 'daily', 'cron'].forEach((k) => {
            const el = form.querySelector(`#sch-${k}`);
            if (el) el.classList.toggle('hidden', k !== kind);
          });
        };
        sch.addEventListener('change', updateSch);
        updateSch();
        const type = form.querySelector('#task-type');
        const updateType = () => {
          const v = type.value;
          form.querySelector('#payload-message').classList.toggle('hidden', !(v === 'message' || v === 'moment'));
          form.querySelector('#payload-plugin').classList.toggle('hidden', v !== 'plugin');
        };
        type.addEventListener('change', updateType);
        updateType();
      },
    });
  }

  // ------------------------------------------------------------------ releases
  async function pageReleases() {
    const data = await api('/api/admin/releases');
    const list = data.releases || [];
    const nextCode = list.length ? Number(list[0].version_code || 0) + 1 : 2;
    $('#page').innerHTML = `
      <section class="card">
        <div class="card-head">
          <div><h3>上传新版本</h3><p class="sub">客户端启动时会检查 versionCode；大于当前版本就弹窗提示更新</p></div>
          <span class="badge">APK 上限 200MB</span>
        </div>
        <div class="form-grid">
          <label class="field"><span>versionCode（整数，必须递增）</span>
            <input id="rel-code" type="number" value="${nextCode}" min="1"></label>
          <label class="field"><span>versionName</span>
            <input id="rel-name" placeholder="例如 1.1.0"></label>
          <label class="field full"><span>更新日志（弹窗中展示）</span>
            <textarea id="rel-log" rows="5" placeholder="1. 新增浅色/深色主题&#10;2. 支持自定义聊天背景&#10;3. 新增专属 Key 私人角色卡"></textarea></label>
          <label class="field full"><span>APK 文件</span>
            <input id="rel-file" type="file" accept=".apk,application/vnd.android.package-archive"></label>
          <div class="full row" style="gap:20px">
            ${fSwitch('强制更新（用户必须更新才能继续使用）', 'force_update', false)}
            <button class="btn primary" id="rel-upload">上传并发布</button>
          </div>
        </div>
      </section>
      <section class="card">
        <div class="card-head"><h3>已发布版本</h3><span class="badge">${list.length}</span></div>
        ${list.length ? list.map((r) => `
          <div class="card tight" style="margin-bottom:10px">
            <div class="row" style="justify-content:space-between;align-items:flex-start">
              <div>
                <div style="font-size:15px"><strong>v${esc(r.version_name)}</strong>
                  <span class="tag">code ${r.version_code}</span>
                  ${r.force_update ? '<span class="badge err">强制更新</span>' : ''}</div>
                <div class="muted" style="font-size:11.5px">${bytes(r.file_size)} · ${fmtTime(r.created_at)} · ${esc(r.file_name)}</div>
              </div>
              <div class="row">
                <a class="btn sm" href="${attr(r.download_url)}" target="_blank" rel="noopener">下载</a>
                <button class="btn sm danger" data-rel-del="${r.id}">删除</button>
              </div>
            </div>
            <div class="hint" style="white-space:pre-wrap;margin-top:8px;color:var(--muted)">${esc(r.changelog || '（无更新日志）')}</div>
          </div>`).join('') : emptyState('📦', '还没有发布过客户端版本')}
      </section>`;

    $('#rel-upload').onclick = async () => {
      const file = $('#rel-file').files[0];
      const code = Number($('#rel-code').value);
      const name = $('#rel-name').value.trim();
      const log = $('#rel-log').value.trim();
      const force = document.querySelector('input[name="force_update"]').checked;
      if (!file) return toast('请选择 APK 文件', 'error');
      if (!code || code <= 0) return toast('versionCode 必须是正整数', 'error');
      if (!name) return toast('请填写 versionName', 'error');
      const fd = new FormData();
      fd.append('file', file);
      fd.append('version_code', String(code));
      fd.append('version_name', name);
      fd.append('changelog', log);
      fd.append('force_update', force ? 'true' : 'false');
      const btn = $('#rel-upload');
      btn.disabled = true;
      btn.textContent = '上传中…';
      try {
        await api('/api/admin/releases', { method: 'POST', form: fd });
        toast('新版本已发布', 'ok');
        pageReleases();
      } catch (err) {
        toast(err.message, 'error');
      } finally {
        btn.disabled = false;
        btn.textContent = '上传并发布';
      }
    };
    $$('[data-rel-del]').forEach((btn) => {
      btn.onclick = async () => {
        if (!confirm('删除这个版本？已经安装旧版的用户将不再收到该更新。')) return;
        try {
          await api(`/api/admin/releases/${btn.dataset.relDel}`, { method: 'DELETE' });
          toast('已删除', 'ok');
          pageReleases();
        } catch (err) { toast(err.message, 'error'); }
      };
    });
  }

  // ------------------------------------------------------------------ logs
  async function pageLogs() {
    const categories = ['all', 'system', 'api', 'auth', 'admin', 'ai', 'proactive', 'memory', 'plugin', 'provider', 'usage', 'knowledge', 'task', 'scheduler', 'weather', 'ws', 'friend', 'chat'];
    const f = state.logFilters;
    const query = new URLSearchParams({ limit: '150' });
    if (f.level !== 'all') query.set('level', f.level);
    if (f.category !== 'all') query.set('category', f.category);
    if (f.q) query.set('q', f.q);
    const data = await api(`/api/admin/logs?${query.toString()}`);
    $('#page').innerHTML = `
      <section class="card">
        <div class="log-toolbar">
          ${fSelect('级别', 'level', ['all', 'debug', 'info', 'warning', 'error'].map((x) => ({ value: x, label: x === 'all' ? '全部' : x })), f.level, { id: 'log-level' })}
          ${fSelect('分类', 'category', categories.map((x) => ({ value: x, label: x === 'all' ? '全部' : x })), f.category, { id: 'log-cat' })}
          ${fText('关键词', 'q', f.q, { id: 'log-q', placeholder: '搜索日志内容' })}
          <div class="row" style="margin-left:auto">
            ${fSwitch('自动刷新（5s）', 'auto', false)}
            <button class="btn" id="log-query">查询</button>
            <button class="btn danger" id="log-clear">清理旧日志</button>
          </div>
        </div>
      </section>
      <section class="card">
        <div class="card-head"><h3>日志</h3><span class="badge">共 ${fmtNum(data.total)} 条</span></div>
        <div class="table-wrap"><table class="log-table">
          <thead><tr><th style="width:130px">时间</th><th style="width:80px">级别</th><th style="width:90px">分类</th><th>内容</th></tr></thead>
          <tbody>
            ${(data.logs || []).length ? data.logs.map((l) => `<tr>
              <td class="muted">${fmtTime(l.created_at)}</td>
              <td><span class="level ${esc(l.level)}">${esc(l.level)}</span></td>
              <td class="muted">${esc(l.category)}</td>
              <td><div class="log-msg">${esc(l.message)}</div>
                ${Object.keys(l.meta || {}).length ? `<div class="log-meta">${esc(JSON.stringify(l.meta))}</div>` : ''}</td>
            </tr>`).join('') : `<tr><td colspan="4">${emptyState('📭', '没有匹配的日志')}</td></tr>`}
          </tbody>
        </table></div>
      </section>`;
    const apply = () => {
      state.logFilters = {
        level: $('#log-level').value,
        category: $('#log-cat').value,
        q: $('#log-q').value.trim(),
      };
      pageLogs();
    };
    $('#log-query').onclick = apply;
    $('#log-q').addEventListener('keydown', (e) => { if (e.key === 'Enter') apply(); });
    $('#log-level').onchange = apply;
    $('#log-cat').onchange = apply;
    const auto = document.querySelector('input[name="auto"]');
    if (auto) {
      auto.onchange = () => {
        if (state.logTimer) { clearInterval(state.logTimer); state.logTimer = null; }
        if (auto.checked) state.logTimer = setInterval(() => { if (location.hash.startsWith('#/logs')) pageLogs(); }, 5000);
      };
    }
    $('#log-clear').onclick = async () => {
      if (!confirm('清理 30 天前的日志？')) return;
      try {
        await api('/api/admin/logs?days=30', { method: 'DELETE' });
        toast('已清理', 'ok');
        pageLogs();
      } catch (err) { toast(err.message, 'error'); }
    };
  }

  // ------------------------------------------------------------------ settings
  async function pageSettings() {
    const [data, system] = await Promise.all([api('/api/admin/settings'), api('/api/admin/system')]);
    const v = data.values || {};
    const counts = system.counts || {};
    $('#page').innerHTML = `
      <div class="split">
        <section class="card">
          <div class="card-head"><div><h3>全局设置</h3><p class="sub">保存后立即生效</p></div></div>
          <div class="form-grid" id="settings-form">
            ${fText('站点名称', 'site_name', v.site_name || '')}
            ${fText('时区', 'timezone', v.timezone || 'Asia/Shanghai')}
            <label class="field full"><span>欢迎语（管理面板首页）</span><textarea name="welcome_text" rows="2">${esc(v.welcome_text || '')}</textarea></label>
            <label class="field full"><span>公告（留空不显示）</span><textarea name="admin_notice" rows="2">${esc(v.admin_notice || '')}</textarea></label>
            ${fText('空闲多久结束会话（分钟）', 'default_idle_minutes', v.default_idle_minutes ?? 30, { type: 'number' })}
            ${fText('主动消息最小间隔（分钟）', 'proactive_min_minutes', v.proactive_min_minutes ?? 60, { type: 'number' })}
            ${fText('主动消息最大间隔（分钟）', 'proactive_max_minutes', v.proactive_max_minutes ?? 180, { type: 'number' })}
            ${fText('最多连续主动次数', 'proactive_max_streak', v.proactive_max_streak ?? 3, { type: 'number' })}
            ${fText('短期记忆保留（天）', 'short_memory_days', v.short_memory_days ?? 2, { type: 'number' })}
            ${fText('上下文消息条数', 'max_context_messages', v.max_context_messages ?? 24, { type: 'number' })}
            ${fText('应用事件回复冷却（分钟）', 'usage_reply_cooldown_minutes', v.usage_reply_cooldown_minutes ?? 15, { type: 'number' })}
            <div class="full switch-grid">
              ${fSwitch('开放注册', 'registration_enabled', v.registration_enabled !== false)}
              ${fSwitch('注册时自动创建 AI 好友', 'auto_create_ai_friend', v.auto_create_ai_friend !== false)}
              ${fSwitch('启用天气查询', 'weather_enabled', v.weather_enabled !== false)}
              ${fSwitch('启用插件指令分发', 'plugin_dispatch_enabled', v.plugin_dispatch_enabled !== false)}
            </div>
            <div class="full row"><button class="btn primary" id="save-settings">保存设置</button>
              <span class="hint">修改空闲/主动参数只影响之后新建的 AI 好友</span></div>
          </div>
        </section>

        <div class="stack">
          <section class="card">
            <div class="card-head"><h3>系统状态</h3><span class="badge ok">v${esc(system.version)}</span></div>
            <div class="kv">
              <div class="k">Python</div><div class="v">${esc(system.python)}</div>
              <div class="k">系统</div><div class="v">${esc(system.platform)}</div>
              <div class="k">运行时长</div><div class="v">${dur(system.uptime_seconds)}</div>
              <div class="k">数据目录</div><div class="v mono">${esc(system.data_dir)}</div>
              <div class="k">数据库</div><div class="v">${bytes(system.db_size)}</div>
              <div class="k">上传文件</div><div class="v">${bytes(system.uploads_size)}</div>
              <div class="k">磁盘剩余</div><div class="v">${bytes(system.disk?.free)} / ${bytes(system.disk?.total)}</div>
            </div>
            <div class="progress" style="margin-top:12px"><i style="width:${Math.min(100, Math.round(((system.disk?.used || 0) / (system.disk?.total || 1)) * 100))}%"></i></div>
          </section>
          <section class="card">
            <div class="card-head"><h3>数据概览</h3></div>
            <div class="kv">
              ${Object.entries(counts).map(([k, n]) => `<div class="k">${esc(k)}</div><div class="v">${fmtNum(n)}</div>`).join('')}
            </div>
          </section>
          <section class="card">
            <div class="card-head"><h3>修改管理员密码</h3></div>
            <div class="form-grid" id="pwd-form">
              ${fText('原密码', 'old_password', '', { type: 'password', full: true })}
              ${fText('新密码（至少 6 位）', 'new_password', '', { type: 'password', full: true })}
              <div class="full"><button class="btn" id="save-pwd">修改密码</button></div>
            </div>
          </section>
        </div>
      </div>`;

    $('#save-settings').onclick = async () => {
      const form = $('#settings-form');
      const fd = new FormData(form);
      const values = {
        site_name: fd.get('site_name'),
        timezone: fd.get('timezone'),
        welcome_text: fd.get('welcome_text'),
        admin_notice: fd.get('admin_notice'),
        default_idle_minutes: Number(fd.get('default_idle_minutes') || 30),
        proactive_min_minutes: Number(fd.get('proactive_min_minutes') || 60),
        proactive_max_minutes: Number(fd.get('proactive_max_minutes') || 180),
        proactive_max_streak: Number(fd.get('proactive_max_streak') || 3),
        short_memory_days: Number(fd.get('short_memory_days') || 2),
        max_context_messages: Number(fd.get('max_context_messages') || 24),
        usage_reply_cooldown_minutes: Number(fd.get('usage_reply_cooldown_minutes') || 15),
        registration_enabled: fd.get('registration_enabled') === 'on',
        auto_create_ai_friend: fd.get('auto_create_ai_friend') === 'on',
        weather_enabled: fd.get('weather_enabled') === 'on',
        plugin_dispatch_enabled: fd.get('plugin_dispatch_enabled') === 'on',
      };
      try {
        await api('/api/admin/settings', { method: 'PUT', body: { values } });
        toast('设置已保存', 'ok');
      } catch (err) { toast(err.message, 'error'); }
    };

    $('#save-pwd').onclick = async () => {
      const form = $('#pwd-form');
      const fd = new FormData(form);
      const oldPwd = (fd.get('old_password') || '').toString();
      const newPwd = (fd.get('new_password') || '').toString();
      if (newPwd.length < 6) return toast('新密码至少 6 位', 'error');
      try {
        await api('/api/v1/auth/password', { method: 'PUT', body: { old_password: oldPwd, new_password: newPwd } });
        toast('密码已修改，请重新登录', 'ok');
        setTimeout(() => logout(false), 900);
      } catch (err) { toast(err.message, 'error'); }
    };
  }

  // ------------------------------------------------------------------ boot
  async function boot() {
    buildNav();
    $('#login-form').addEventListener('submit', doLogin);
    $('#refresh-btn').addEventListener('click', () => renderRoute());
    $('#logout-btn').addEventListener('click', () => logout(true));
    window.addEventListener('hashchange', renderRoute);

    if (!state.token) {
      showLogin();
      return;
    }
    showApp();
    try {
      const me = await api('/api/v1/auth/me');
      if (!me.user || me.user.role !== 'admin') {
        toast('当前账号不是管理员', 'error');
        logout(false);
        return;
      }
      state.user = me.user;
      if (!location.hash) location.hash = '#/welcome';
      await renderRoute();
      startHealthPing();
    } catch (err) {
      showLogin();
    }
  }

  document.addEventListener('DOMContentLoaded', boot);
})();
