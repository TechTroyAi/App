/* ======================================================
   CleanLead — script.js
   Application logic, data model, and rendering
   ====================================================== */

(function () {
  'use strict';

  // ─── Constants ───────────────────────────────────────
  const STORAGE_KEY = 'cleanlead_data';
  const TASK_STATUSES = [
    'Not started',
    'In progress',
    'Completed',
    'Partially completed',
    'Needs review',
    'Unable to complete',
    'Not observed'
  ];
  const ATTENDANCE_STATUSES = ['Present', 'Late', 'Excused', 'Absent', 'Left early'];
  const DIFFICULTIES = ['Easy', 'Medium', 'Hard'];

  // ─── State ───────────────────────────────────────────
  let data = null;
  let currentScreen = 'dashboard';
  let activeSessionId = null;

  // ─── Utilities ───────────────────────────────────────
  function uid() {
    return Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
  }

  function formatDate(d) {
    if (!d) return '';
    const dt = new Date(d);
    return dt.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' });
  }

  function formatTime(t) {
    if (!t) return '';
    const [h, m] = t.split(':');
    const hr = parseInt(h, 10);
    const ampm = hr >= 12 ? 'PM' : 'AM';
    const hr12 = hr % 12 || 12;
    return `${hr12}:${m} ${ampm}`;
  }

  function getGreeting() {
    const h = new Date().getHours();
    if (h < 12) return 'Good morning';
    if (h < 17) return 'Good afternoon';
    return 'Good evening';
  }

  function getInitials(name) {
    return name.split(' ').map(w => w[0]).join('').toUpperCase().slice(0, 2);
  }

  function statusBadgeClass(status) {
    const map = {
      'Completed': 'badge-completed',
      'In progress': 'badge-in-progress',
      'Not started': 'badge-not-started',
      'Needs review': 'badge-needs-review',
      'Partially completed': 'badge-partially-completed',
      'Unable to complete': 'badge-unable',
      'Not observed': 'badge-not-observed'
    };
    return map[status] || 'badge-not-started';
  }

  function statusDotClass(status) {
    const map = {
      'Completed': 'status-completed',
      'In progress': 'status-in-progress',
      'Not started': 'status-not-started',
      'Needs review': 'status-needs-review',
      'Partially completed': 'status-partially-completed',
      'Unable to complete': 'status-unable-to-complete',
      'Not observed': 'status-not-observed'
    };
    return map[status] || 'status-not-started';
  }

  function diffBadgeClass(d) {
    if (d === 'Easy') return 'diff-easy';
    if (d === 'Medium') return 'diff-medium';
    return 'diff-hard';
  }

  function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
  }

  // ─── Toast ───────────────────────────────────────────
  function showToast(msg, type = 'info') {
    const container = document.getElementById('toast-container');
    const t = document.createElement('div');
    t.className = `toast toast-${type}`;
    t.textContent = msg;
    container.appendChild(t);
    setTimeout(() => { t.remove(); }, 3000);
  }

  // ─── Modal ───────────────────────────────────────────
  function openModal(title, bodyHtml, footerHtml) {
    document.getElementById('modal-title').textContent = title;
    document.getElementById('modal-body').innerHTML = bodyHtml;
    document.getElementById('modal-footer').innerHTML = footerHtml || '';
    document.getElementById('modal-overlay').classList.add('open');
    document.getElementById('modal-overlay').setAttribute('aria-hidden', 'false');
  }

  function closeModal() {
    document.getElementById('modal-overlay').classList.remove('open');
    document.getElementById('modal-overlay').setAttribute('aria-hidden', 'true');
  }

  // ─── Data persistence ────────────────────────────────
  function saveData() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
    } catch (e) {
      console.warn('Could not save to localStorage', e);
    }
  }

  function loadData() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        data = JSON.parse(raw);
        return true;
      }
    } catch (e) {
      console.warn('Could not load from localStorage', e);
    }
    return false;
  }

  // ─── Sample data ─────────────────────────────────────
  function generateSampleData() {
    const members = [
      { id: uid(), name: 'Alex Rivera', joinedAt: '2025-09-01' },
      { id: uid(), name: 'Sam Chen', joinedAt: '2025-09-01' },
      { id: uid(), name: 'Jordan Lee', joinedAt: '2025-09-05' },
      { id: uid(), name: 'Taylor Kim', joinedAt: '2025-09-10' },
      { id: uid(), name: 'Morgan Patel', joinedAt: '2025-09-12' }
    ];

    const sessionId = uid();
    const today = new Date().toISOString().split('T')[0];

    const zones = [
      { id: uid(), sessionId, name: 'Main Hall' },
      { id: uid(), sessionId, name: 'Kitchen Area' },
      { id: uid(), sessionId, name: 'Restrooms' }
    ];

    const tasks = [
      { id: uid(), sessionId, zoneId: zones[0].id, name: 'Sweep and mop floors', assignedTo: members[0].id, instructions: 'Start from the entrance and work toward the back.', difficulty: 'Medium', estimatedTime: 25, status: 'Completed', notes: 'All floors done.', photos: [], createdAt: today },
      { id: uid(), sessionId, zoneId: zones[0].id, name: 'Wipe down tables', assignedTo: members[1].id, instructions: 'Use disinfectant spray on all surfaces.', difficulty: 'Easy', estimatedTime: 15, status: 'In progress', notes: '', photos: [], createdAt: today },
      { id: uid(), sessionId, zoneId: zones[0].id, name: 'Clean windows', assignedTo: members[2].id, instructions: 'Use glass cleaner and lint-free cloth.', difficulty: 'Hard', estimatedTime: 30, status: 'Needs review', notes: 'Some streaks visible on east side.', photos: [], createdAt: today },
      { id: uid(), sessionId, zoneId: zones[1].id, name: 'Sanitize counters', assignedTo: members[3].id, instructions: 'Clear all items, spray, wait 2 minutes, wipe.', difficulty: 'Easy', estimatedTime: 10, status: 'Completed', notes: '', photos: [], createdAt: today },
      { id: uid(), sessionId, zoneId: zones[1].id, name: 'Clean appliances', assignedTo: members[4].id, instructions: 'Microwave, toaster, and coffee machine.', difficulty: 'Medium', estimatedTime: 20, status: 'Not started', notes: '', photos: [], createdAt: today },
      { id: uid(), sessionId, zoneId: zones[2].id, name: 'Restock supplies', assignedTo: members[0].id, instructions: 'Paper towels, soap, and tissue.', difficulty: 'Easy', estimatedTime: 10, status: 'In progress', notes: '', photos: [], createdAt: today },
      { id: uid(), sessionId, zoneId: zones[2].id, name: 'Deep clean sinks', assignedTo: members[2].id, instructions: 'Scrub with baking soda paste, rinse thoroughly.', difficulty: 'Medium', estimatedTime: 15, status: 'Not started', notes: '', photos: [], createdAt: today }
    ];

    const attendance = members.map((m, i) => ({
      id: uid(),
      sessionId,
      memberId: m.id,
      status: i === 3 ? 'Late' : i === 4 ? 'Excused' : 'Present',
      notes: ''
    }));

    const session = {
      id: sessionId,
      areaName: 'Community Center — Building A',
      date: today,
      time: '14:00',
      instructions: 'Focus on high-traffic areas first. Report any damage found. Check supply closet before restocking.',
      memberIds: members.map(m => m.id),
      zones: zones,
      tasks: tasks,
      attendance: attendance,
      createdAt: today,
      status: 'active'
    };

    // A completed session for history
    const sessionId2 = uid();
    const pastDate = new Date(Date.now() - 7 * 86400000).toISOString().split('T')[0];
    const pastTasks = [
      { id: uid(), sessionId: sessionId2, zoneId: 'z1', name: 'Vacuum carpet', assignedTo: members[1].id, instructions: '', difficulty: 'Easy', estimatedTime: 20, status: 'Completed', notes: '', photos: [], createdAt: pastDate },
      { id: uid(), sessionId: sessionId2, zoneId: 'z1', name: 'Dust shelves', assignedTo: members[3].id, instructions: '', difficulty: 'Easy', estimatedTime: 15, status: 'Completed', notes: '', photos: [], createdAt: pastDate }
    ];

    const session2 = {
      id: sessionId2,
      areaName: 'Office Wing — Floor 2',
      date: pastDate,
      time: '09:00',
      instructions: 'Standard weekly clean.',
      memberIds: [members[1].id, members[3].id],
      zones: [{ id: 'z1', sessionId: sessionId2, name: 'Open Office' }],
      tasks: pastTasks,
      attendance: [
        { id: uid(), sessionId: sessionId2, memberId: members[1].id, status: 'Present', notes: '' },
        { id: uid(), sessionId: sessionId2, memberId: members[3].id, status: 'Present', notes: '' }
      ],
      createdAt: pastDate,
      status: 'completed'
    };

    // A correction request
    const corrections = [
      {
        id: uid(),
        sessionId: sessionId,
        taskId: tasks[2].id,
        memberId: members[2].id,
        memberName: 'Jordan Lee',
        taskName: 'Clean windows',
        reason: 'I completed the windows on the west side thoroughly. The streaks noted may be from condensation, not incomplete work. Requesting a re-review.',
        status: 'pending',
        createdAt: today
      }
    ];

    data = {
      members,
      sessions: [session, session2],
      corrections,
      settings: { activeSessionId: sessionId }
    };
    activeSessionId = sessionId;
    saveData();
  }

  // ─── Data helpers ────────────────────────────────────
  function getActiveSession() {
    const sid = data.settings.activeSessionId;
    return data.sessions.find(s => s.id === sid) || null;
  }

  function getMember(id) {
    return data.members.find(m => m.id === id);
  }

  function getMemberName(id) {
    const m = getMember(id);
    return m ? m.name : 'Unassigned';
  }

  function getSessionTasks(session) {
    return session ? session.tasks : [];
  }

  function getSessionZones(session) {
    return session ? session.zones : [];
  }

  // ─── Navigation ──────────────────────────────────────
  const SCREEN_TITLES = {
    dashboard: 'Dashboard',
    sessions: 'Sessions',
    tasks: 'Task Board',
    team: 'Team',
    reports: 'Reports',
    settings: 'Settings'
  };

  function navigateTo(screen) {
    currentScreen = screen;
    document.querySelectorAll('.screen').forEach(el => el.classList.remove('active'));
    document.getElementById('screen-' + screen).classList.add('active');

    document.querySelectorAll('.nav-item').forEach(el => {
      el.classList.toggle('active', el.dataset.screen === screen);
      if (el.dataset.screen === screen) el.setAttribute('aria-current', 'page');
      else el.removeAttribute('aria-current');
    });

    document.getElementById('top-bar-title').textContent = SCREEN_TITLES[screen];

    // Update subtitle
    const session = getActiveSession();
    const subtitle = document.getElementById('top-bar-subtitle');
    if (session && screen !== 'settings') {
      subtitle.textContent = `${session.areaName} — ${formatDate(session.date)}`;
    } else {
      subtitle.textContent = '';
    }

    // Close mobile sidebar
    document.getElementById('sidebar').classList.remove('open');

    // Render screen
    renderScreen(screen);
  }

  function renderScreen(screen) {
    switch (screen) {
      case 'dashboard': renderDashboard(); break;
      case 'sessions': renderSessions(); break;
      case 'tasks': renderTasks(); break;
      case 'team': renderTeam(); break;
      case 'reports': renderReports(); break;
      case 'settings': break;
    }
  }

  // ─── Dashboard ───────────────────────────────────────
  function renderDashboard() {
    const session = getActiveSession();

    // Greeting
    document.getElementById('dash-greeting').textContent = `${getGreeting()}, Leader`;

    const cardsContainer = document.getElementById('dash-summary-cards');
    if (!session) {
      cardsContainer.innerHTML = `
        <div class="summary-card">
          <div class="summary-card-icon card-purple">&#9673;</div>
          <div class="summary-card-value">0</div>
          <div class="summary-card-label">No Active Session</div>
        </div>`;
      document.getElementById('dash-progress-bar').style.width = '0%';
      document.getElementById('dash-progress-label').textContent = '0%';
      document.getElementById('dash-task-board-mini').innerHTML = '<div class="empty-state"><div class="empty-state-icon">&#9776;</div><div class="empty-state-text">Create a session to get started</div></div>';
      return;
    }

    const tasks = session.tasks;
    const members = session.memberIds;
    const presentCount = (session.attendance || []).filter(a => a.status === 'Present' || a.status === 'Late').length;
    const completedCount = tasks.filter(t => t.status === 'Completed').length;
    const reviewCount = tasks.filter(t => t.status === 'Needs review').length;

    cardsContainer.innerHTML = `
      <div class="summary-card">
        <div class="summary-card-icon card-purple">&#9673;</div>
        <div class="summary-card-value">${members.length}</div>
        <div class="summary-card-label">Team Members</div>
      </div>
      <div class="summary-card">
        <div class="summary-card-icon card-green">&#10003;</div>
        <div class="summary-card-value">${presentCount}</div>
        <div class="summary-card-label">Present Today</div>
      </div>
      <div class="summary-card">
        <div class="summary-card-icon card-blue">&#9745;</div>
        <div class="summary-card-value">${completedCount}</div>
        <div class="summary-card-label">Tasks Completed</div>
      </div>
      <div class="summary-card">
        <div class="summary-card-icon card-yellow">&#9888;</div>
        <div class="summary-card-value">${reviewCount}</div>
        <div class="summary-card-label">Needs Review</div>
      </div>`;

    // Progress
    const total = tasks.length;
    const pct = total > 0 ? Math.round((completedCount / total) * 100) : 0;
    document.getElementById('dash-progress-bar').style.width = pct + '%';
    document.getElementById('dash-progress-bar').setAttribute('aria-valuenow', pct);
    document.getElementById('dash-progress-label').textContent = pct + '%';

    // Mini task board
    const boardContainer = document.getElementById('dash-task-board-mini');
    const showStatuses = ['Not started', 'In progress', 'Completed', 'Needs review'];
    boardContainer.innerHTML = showStatuses.map(status => {
      const statusTasks = tasks.filter(t => t.status === status);
      return `<div class="mini-column">
        <div class="mini-column-title"><span class="status-dot ${statusDotClass(status)}" aria-hidden="true"></span> ${escapeHtml(status)} (${statusTasks.length})</div>
        ${statusTasks.length === 0 ? '<div class="mini-task" style="color:var(--text-muted)">No tasks</div>' :
          statusTasks.map(t => `<div class="mini-task">${escapeHtml(t.name)}</div>`).join('')}
      </div>`;
    }).join('');
  }

  // ─── Sessions ────────────────────────────────────────
  function renderSessions() {
    const container = document.getElementById('sessions-list');
    if (data.sessions.length === 0) {
      container.innerHTML = '<div class="empty-state"><div class="empty-state-icon">&#9776;</div><div class="empty-state-text">No sessions yet. Create your first cleaning session.</div></div>';
      return;
    }

    container.innerHTML = data.sessions.map(s => {
      const isActive = s.id === data.settings.activeSessionId;
      const taskCount = s.tasks.length;
      const completedCount = s.tasks.filter(t => t.status === 'Completed').length;
      const memberCount = s.memberIds.length;
      return `<div class="session-card ${isActive ? 'active-session' : ''}">
        <div class="session-card-header">
          <div class="session-card-title">${escapeHtml(s.areaName)}</div>
          <span class="session-badge ${s.status === 'active' ? 'badge-active' : 'badge-completed'}">${s.status === 'active' ? 'Active' : 'Completed'}</span>
        </div>
        <div class="session-card-meta">
          <span>&#128197; ${formatDate(s.date)}</span>
          <span>&#128336; ${formatTime(s.time)}</span>
          <span>&#9673; ${memberCount} members</span>
          <span>&#9745; ${completedCount}/${taskCount} tasks done</span>
        </div>
        ${s.instructions ? `<div class="session-instructions">${escapeHtml(s.instructions)}</div>` : ''}
        <div class="session-card-actions">
          ${isActive ? `<button class="btn btn-sm btn-outline" disabled aria-label="Already active session">Active Session</button>` :
            `<button class="btn btn-sm btn-primary" data-action="activate-session" data-id="${s.id}">Set as Active</button>`}
          ${s.status === 'active' ?
            `<button class="btn btn-sm btn-outline" data-action="complete-session" data-id="${s.id}">Mark Complete</button>` :
            `<button class="btn btn-sm btn-outline" data-action="reactivate-session" data-id="${s.id}">Reactivate</button>`}
          <button class="btn btn-sm btn-ghost" data-action="delete-session" data-id="${s.id}">&#128465; Delete</button>
        </div>
      </div>`;
    }).join('');
  }

  // ─── Create Session Modal ────────────────────────────
  function openCreateSessionModal() {
    const memberCheckboxes = data.members.map(m =>
      `<label class="checkbox-item"><input type="checkbox" name="session-member" value="${m.id}" checked> ${escapeHtml(m.name)}</label>`
    ).join('');

    const today = new Date().toISOString().split('T')[0];

    const body = `
      <div class="form-group">
        <label for="session-area">Area Name *</label>
        <input type="text" id="session-area" class="input" placeholder="e.g. Community Center — Building A" required>
      </div>
      <div class="form-row">
        <div class="form-group">
          <label for="session-date">Date *</label>
          <input type="date" id="session-date" class="input" value="${today}" required>
        </div>
        <div class="form-group">
          <label for="session-time">Time</label>
          <input type="time" id="session-time" class="input" value="09:00">
        </div>
      </div>
      <div class="form-group">
        <label for="session-instructions">Instructions</label>
        <textarea id="session-instructions" class="textarea" placeholder="Special instructions for this session..."></textarea>
      </div>
      <div class="form-group">
        <label>Team Members</label>
        <div class="checkbox-group" id="session-members-group">
          ${memberCheckboxes || '<span style="color:var(--text-muted);font-size:0.85rem">Add team members first</span>'}
        </div>
      </div>`;

    const footer = `
      <button class="btn btn-outline" id="modal-cancel-btn">Cancel</button>
      <button class="btn btn-primary" id="modal-save-session-btn">Create Session</button>`;

    openModal('Create Cleaning Session', body, footer);

    document.getElementById('modal-cancel-btn').onclick = closeModal;
    document.getElementById('modal-save-session-btn').onclick = () => {
      const area = document.getElementById('session-area').value.trim();
      const date = document.getElementById('session-date').value;
      const time = document.getElementById('session-time').value;
      const instructions = document.getElementById('session-instructions').value.trim();
      const memberCheckboxes = document.querySelectorAll('#session-members-group input[type="checkbox"]:checked');
      const memberIds = Array.from(memberCheckboxes).map(cb => cb.value);

      if (!area) { showToast('Please enter an area name', 'error'); return; }
      if (!date) { showToast('Please select a date', 'error'); return; }

      const session = {
        id: uid(),
        areaName: area,
        date,
        time,
        instructions,
        memberIds,
        zones: [],
        tasks: [],
        attendance: memberIds.map(mid => ({ id: uid(), sessionId: '', memberId: mid, status: 'Present', notes: '' })),
        createdAt: new Date().toISOString().split('T')[0],
        status: 'active'
      };
      session.attendance.forEach(a => a.sessionId = session.id);

      data.sessions.unshift(session);
      data.settings.activeSessionId = session.id;
      activeSessionId = session.id;
      saveData();
      closeModal();
      showToast('Session created successfully', 'success');
      renderScreen(currentScreen);
      updateTopBarSubtitle();
    };
  }

  // ─── Tasks ───────────────────────────────────────────
  function renderTasks() {
    const session = populateSessionSelect('task-session-select');
    if (!session) {
      document.getElementById('task-board').style.display = 'none';
      document.getElementById('other-statuses').innerHTML = '<div class="empty-state"><div class="empty-state-icon">&#9745;</div><div class="empty-state-text">Create a session first to add tasks</div></div>';
      return;
    }
    document.getElementById('task-board').style.display = '';

    // Populate zone filter
    const zoneFilter = document.getElementById('task-zone-filter');
    const zones = getSessionZones(session);
    const currentZoneVal = zoneFilter.value;
    zoneFilter.innerHTML = '<option value="all">All Zones</option>' +
      zones.map(z => `<option value="${z.id}">${escapeHtml(z.name)}</option>`).join('');
    zoneFilter.value = currentZoneVal || 'all';

    // Populate member filter
    const memberFilter = document.getElementById('task-member-filter');
    const currentMemberVal = memberFilter.value;
    memberFilter.innerHTML = '<option value="all">All Members</option>' +
      session.memberIds.map(mid => {
        const name = getMemberName(mid);
        return `<option value="${mid}">${escapeHtml(name)}</option>`;
      }).join('');
    memberFilter.value = currentMemberVal || 'all';

    // Filter tasks
    let tasks = session.tasks;
    if (zoneFilter.value !== 'all') {
      tasks = tasks.filter(t => t.zoneId === zoneFilter.value);
    }
    if (memberFilter.value !== 'all') {
      tasks = tasks.filter(t => t.assignedTo === memberFilter.value);
    }

    // Render main 4 columns
    const mainStatuses = ['Not started', 'In progress', 'Completed', 'Needs review'];
    mainStatuses.forEach(status => {
      const colId = 'col-' + status.toLowerCase().replace(/ /g, '-');
      const countId = 'count-' + status.toLowerCase().replace(/ /g, '-');
      const colEl = document.getElementById(colId);
      const countEl = document.getElementById(countId);
      const statusTasks = tasks.filter(t => t.status === status);
      countEl.textContent = statusTasks.length;
      if (statusTasks.length === 0) {
        colEl.innerHTML = '<div class="empty-state" style="padding:20px"><div class="empty-state-text" style="font-size:0.8rem">No tasks</div></div>';
      } else {
        colEl.innerHTML = statusTasks.map(t => renderTaskCard(t, session)).join('');
      }
    });

    // Other statuses
    const otherStatuses = TASK_STATUSES.filter(s => !mainStatuses.includes(s));
    const otherContainer = document.getElementById('other-statuses');
    const otherTasks = tasks.filter(t => otherStatuses.includes(t.status));
    if (otherTasks.length === 0) {
      otherContainer.innerHTML = '';
    } else {
      otherContainer.innerHTML = otherStatuses.map(status => {
        const st = tasks.filter(t => t.status === status);
        if (st.length === 0) return '';
        return `<div class="other-status-group">
          <div class="other-status-group-title"><span class="status-dot ${statusDotClass(status)}" aria-hidden="true"></span> ${escapeHtml(status)} (${st.length})</div>
          <div style="display:flex;flex-direction:column;gap:8px;">${st.map(t => renderTaskCard(t, session)).join('')}</div>
        </div>`;
      }).join('');
    }
  }

  function renderTaskCard(task, session) {
    const zone = (session.zones || []).find(z => z.id === task.zoneId);
    const zoneName = zone ? zone.name : 'No zone';
    const assignee = getMember(task.assignedTo);
    const initials = assignee ? getInitials(assignee.name) : '?';
    const assigneeName = assignee ? assignee.name : 'Unassigned';

    return `<div class="task-card" data-task-id="${task.id}" tabindex="0" role="button" aria-label="Task: ${escapeHtml(task.name)}">
      <div class="task-card-title">${escapeHtml(task.name)}</div>
      <div class="task-card-zone">${escapeHtml(zoneName)}</div>
      <div class="task-card-meta">
        <div class="task-card-assignee">
          <span class="assignee-avatar">${initials}</span>
          <span>${escapeHtml(assigneeName)}</span>
        </div>
        <span class="task-difficulty ${diffBadgeClass(task.difficulty)}">${escapeHtml(task.difficulty)}</span>
      </div>
    </div>`;
  }

  function populateSessionSelect(selectId) {
    const sel = document.getElementById(selectId);
    const currentVal = sel.value;
    sel.innerHTML = data.sessions.map(s =>
      `<option value="${s.id}">${escapeHtml(s.areaName)} (${formatDate(s.date)})</option>`
    ).join('');

    if (data.sessions.length === 0) return null;

    // Prefer active session
    if (currentVal && data.sessions.find(s => s.id === currentVal)) {
      sel.value = currentVal;
    } else if (data.settings.activeSessionId && data.sessions.find(s => s.id === data.settings.activeSessionId)) {
      sel.value = data.settings.activeSessionId;
    }
    return data.sessions.find(s => s.id === sel.value) || data.sessions[0];
  }

  // ─── Add Task Modal ──────────────────────────────────
  function openAddTaskModal() {
    const session = getActiveSession();
    if (!session) { showToast('Create a session first', 'error'); return; }

    const zones = session.zones || [];
    const zoneOptions = zones.map(z => `<option value="${z.id}">${escapeHtml(z.name)}</option>`).join('');
    const memberOptions = session.memberIds.map(mid => {
      const name = getMemberName(mid);
      return `<option value="${mid}">${escapeHtml(name)}</option>`;
    }).join('');

    const body = `
      <div class="form-group">
        <label for="task-name">Task Name *</label>
        <input type="text" id="task-name" class="input" placeholder="e.g. Sweep and mop floors" required>
      </div>
      <div class="form-group">
        <label for="task-zone-select">Zone</label>
        <div style="display:flex;gap:8px;align-items:center;">
          <select id="task-zone-select" class="select-input" style="flex:1">${zoneOptions || '<option value="">No zones — add one below</option>'}</select>
          <button class="btn btn-sm btn-outline" id="btn-add-zone-inline" aria-label="Add new zone">+ Zone</button>
        </div>
      </div>
      <div class="form-row">
        <div class="form-group">
          <label for="task-assignee">Assigned To</label>
          <select id="task-assignee" class="select-input">${memberOptions || '<option>No members</option>'}</select>
        </div>
        <div class="form-group">
          <label for="task-difficulty">Difficulty</label>
          <select id="task-difficulty" class="select-input">${DIFFICULTIES.map(d => `<option value="${d}">${d}</option>`).join('')}</select>
        </div>
      </div>
      <div class="form-row">
        <div class="form-group">
          <label for="task-est-time">Estimated Time (min)</label>
          <input type="number" id="task-est-time" class="input" min="1" value="15">
        </div>
        <div class="form-group">
          <label for="task-status-select">Status</label>
          <select id="task-status-select" class="select-input">${TASK_STATUSES.map(s => `<option value="${s}" ${s === 'Not started' ? 'selected' : ''}>${s}</option>`).join('')}</select>
        </div>
      </div>
      <div class="form-group">
        <label for="task-instructions">Instructions</label>
        <textarea id="task-instructions" class="textarea" placeholder="Specific instructions for this task..."></textarea>
      </div>`;

    const footer = `
      <button class="btn btn-outline" id="modal-cancel-btn">Cancel</button>
      <button class="btn btn-primary" id="modal-save-task-btn">Add Task</button>`;

    openModal('Add Task', body, footer);

    document.getElementById('modal-cancel-btn').onclick = closeModal;

    document.getElementById('btn-add-zone-inline').onclick = () => {
      const zoneName = prompt('Enter zone name:');
      if (!zoneName || !zoneName.trim()) return;
      const newZone = { id: uid(), sessionId: session.id, name: zoneName.trim() };
      session.zones.push(newZone);
      saveData();
      const sel = document.getElementById('task-zone-select');
      const opt = document.createElement('option');
      opt.value = newZone.id;
      opt.textContent = newZone.name;
      sel.appendChild(opt);
      sel.value = newZone.id;
      showToast(`Zone "${newZone.name}" added`, 'success');
    };

    document.getElementById('modal-save-task-btn').onclick = () => {
      const name = document.getElementById('task-name').value.trim();
      if (!name) { showToast('Please enter a task name', 'error'); return; }

      const task = {
        id: uid(),
        sessionId: session.id,
        zoneId: document.getElementById('task-zone-select').value,
        name,
        assignedTo: document.getElementById('task-assignee').value,
        instructions: document.getElementById('task-instructions').value.trim(),
        difficulty: document.getElementById('task-difficulty').value,
        estimatedTime: parseInt(document.getElementById('task-est-time').value, 10) || 15,
        status: document.getElementById('task-status-select').value,
        notes: '',
        photos: [],
        createdAt: new Date().toISOString().split('T')[0]
      };

      session.tasks.push(task);
      saveData();
      closeModal();
      showToast(`Task "${name}" added`, 'success');
      renderScreen('tasks');
    };
  }

  // ─── Task Detail Modal ───────────────────────────────
  function openTaskDetailModal(taskId) {
    const session = getActiveSession() || data.sessions.find(s => s.tasks.some(t => t.id === taskId));
    if (!session) return;
    const task = session.tasks.find(t => t.id === taskId);
    if (!task) return;

    const zone = (session.zones || []).find(z => z.id === task.zoneId);
    const zoneName = zone ? zone.name : 'No zone';
    const assignee = getMember(task.assignedTo);
    const assigneeName = assignee ? assignee.name : 'Unassigned';

    const body = `
      <div class="task-detail-grid">
        <div class="task-detail-item">
          <div class="task-detail-label">Zone</div>
          <div class="task-detail-value">${escapeHtml(zoneName)}</div>
        </div>
        <div class="task-detail-item">
          <div class="task-detail-label">Assigned To</div>
          <div class="task-detail-value">${escapeHtml(assigneeName)}</div>
        </div>
        <div class="task-detail-item">
          <div class="task-detail-label">Difficulty</div>
          <div class="task-detail-value"><span class="task-difficulty ${diffBadgeClass(task.difficulty)}">${escapeHtml(task.difficulty)}</span></div>
        </div>
        <div class="task-detail-item">
          <div class="task-detail-label">Estimated Time</div>
          <div class="task-detail-value">${task.estimatedTime} min</div>
        </div>
        <div class="task-detail-item">
          <div class="task-detail-label">Status</div>
          <div class="task-detail-value"><span class="status-badge ${statusBadgeClass(task.status)}"><span class="status-dot ${statusDotClass(task.status)}" aria-hidden="true"></span> ${escapeHtml(task.status)}</span></div>
        </div>
        <div class="task-detail-item">
          <div class="task-detail-label">Created</div>
          <div class="task-detail-value">${formatDate(task.createdAt)}</div>
        </div>
      </div>
      ${task.instructions ? `<div class="task-detail-item" style="margin-bottom:12px"><div class="task-detail-label">Instructions</div><div class="task-detail-value">${escapeHtml(task.instructions)}</div></div>` : ''}
      <div class="form-group">
        <label for="task-detail-status">Change Status</label>
        <select id="task-detail-status" class="select-input">${TASK_STATUSES.map(s => `<option value="${s}" ${s === task.status ? 'selected' : ''}>${s}</option>`).join('')}</select>
      </div>
      <div class="task-notes-area">
        <label for="task-detail-notes" style="font-size:0.85rem;color:var(--text-secondary);margin-bottom:6px;display:block">Leader Notes</label>
        <textarea id="task-detail-notes" class="textarea" placeholder="Add private notes about this task...">${escapeHtml(task.notes || '')}</textarea>
      </div>
      <div class="task-photos-section">
        <label style="font-size:0.85rem;color:var(--text-secondary);margin-bottom:6px;display:block">Before/After Photos</label>
        <input type="file" id="task-photo-input" accept="image/*" multiple aria-label="Upload photos" style="font-size:0.85rem;color:var(--text-muted);">
        <div class="photo-preview" id="task-photo-preview"></div>
      </div>`;

    const footer = `
      <button class="btn btn-ghost" data-action="request-correction" data-task-id="${task.id}">Request Correction</button>
      <button class="btn btn-outline" id="modal-cancel-btn">Close</button>
      <button class="btn btn-primary" id="modal-save-task-btn">Save Changes</button>`;

    openModal(task.name, body, footer);

    // Render existing photos
    renderPhotoPreview(task.photos);

    document.getElementById('modal-cancel-btn').onclick = closeModal;

    // Photo upload
    document.getElementById('task-photo-input').onchange = function (e) {
      const files = Array.from(e.target.files);
      files.forEach(file => {
        const reader = new FileReader();
        reader.onload = function (ev) {
          task.photos.push({ name: file.name, data: ev.target.result, uploadedAt: new Date().toISOString() });
          saveData();
          renderPhotoPreview(task.photos);
        };
        reader.readAsDataURL(file);
      });
    };

    // Correction request
    const corrBtn = document.querySelector('[data-action="request-correction"]');
    if (corrBtn) {
      corrBtn.onclick = () => openCorrectionRequestModal(task, session);
    }

    document.getElementById('modal-save-task-btn').onclick = () => {
      const newStatus = document.getElementById('task-detail-status').value;
      const notes = document.getElementById('task-detail-notes').value.trim();
      task.status = newStatus;
      task.notes = notes;
      saveData();
      closeModal();
      showToast('Task updated', 'success');
      renderScreen(currentScreen);
    };
  }

  function renderPhotoPreview(photos) {
    const container = document.getElementById('task-photo-preview');
    if (!container) return;
    if (!photos || photos.length === 0) {
      container.innerHTML = '<span style="font-size:0.8rem;color:var(--text-muted)">No photos yet</span>';
      return;
    }
    container.innerHTML = photos.map((p, i) => {
      if (p.data) {
        return `<img src="${p.data}" alt="${escapeHtml(p.name)}" class="photo-thumb" style="width:80px;height:80px;">`;
      }
      return `<div class="photo-thumb">${escapeHtml(p.name)}</div>`;
    }).join('');
  }

  // ─── Correction Request Modal ────────────────────────
  function openCorrectionRequestModal(task, session) {
    const body = `
      <div class="form-group">
        <p style="font-size:0.85rem;color:var(--text-secondary);margin-bottom:12px;">
          Submit a request for the leader to review the record for <strong>${escapeHtml(task.name)}</strong>.
          This will appear in the Correction Requests tab.
        </p>
        <label for="correction-reason">Reason for Correction *</label>
        <textarea id="correction-reason" class="textarea" placeholder="Explain why you believe the record should be reviewed..."></textarea>
      </div>`;

    const footer = `
      <button class="btn btn-outline" id="modal-cancel-btn">Cancel</button>
      <button class="btn btn-primary" id="modal-submit-correction">Submit Request</button>`;

    openModal('Request Correction', body, footer);

    document.getElementById('modal-cancel-btn').onclick = closeModal;
    document.getElementById('modal-submit-correction').onclick = () => {
      const reason = document.getElementById('correction-reason').value.trim();
      if (!reason) { showToast('Please provide a reason', 'error'); return; }

      const correction = {
        id: uid(),
        sessionId: session.id,
        taskId: task.id,
        memberId: task.assignedTo,
        memberName: getMemberName(task.assignedTo),
        taskName: task.name,
        reason,
        status: 'pending',
        createdAt: new Date().toISOString().split('T')[0]
      };
      data.corrections.push(correction);
      saveData();
      closeModal();
      showToast('Correction request submitted', 'success');
    };
  }

  // ─── Team ────────────────────────────────────────────
  function renderTeam() {
    renderTeamRoster();
    renderAttendance();
    renderCorrections();
    populateSessionSelect('attendance-session-select');
  }

  function renderTeamRoster() {
    const container = document.getElementById('team-list');
    if (data.members.length === 0) {
      container.innerHTML = '<div class="empty-state"><div class="empty-state-icon">&#9673;</div><div class="empty-state-text">No team members yet. Add your first member.</div></div>';
      return;
    }

    container.innerHTML = data.members.map(m => {
      // Count tasks assigned across all sessions
      let totalTasks = 0;
      let completedTasks = 0;
      data.sessions.forEach(s => {
        s.tasks.forEach(t => {
          if (t.assignedTo === m.id) {
            totalTasks++;
            if (t.status === 'Completed') completedTasks++;
          }
        });
      });

      return `<div class="member-card">
        <div class="member-avatar">${getInitials(m.name)}</div>
        <div class="member-info">
          <div class="member-name">${escapeHtml(m.name)}</div>
          <div class="member-stats">${totalTasks} tasks assigned · ${completedTasks} completed</div>
        </div>
        <div class="member-actions">
          <button class="btn btn-xs btn-ghost" data-action="delete-member" data-id="${m.id}" aria-label="Remove ${escapeHtml(m.name)}">&#128465;</button>
        </div>
      </div>`;
    }).join('');
  }

  function renderAttendance() {
    const sel = document.getElementById('attendance-session-select');
    const session = data.sessions.find(s => s.id === sel.value) || getActiveSession();
    if (!session) {
      document.getElementById('attendance-list').innerHTML = '<div class="empty-state"><div class="empty-state-text" style="font-size:0.85rem">Select a session to view attendance</div></div>';
      return;
    }

    const container = document.getElementById('attendance-list');
    const attendance = session.attendance || [];

    if (session.memberIds.length === 0) {
      container.innerHTML = '<div class="empty-state"><div class="empty-state-text" style="font-size:0.85rem">No members in this session</div></div>';
      return;
    }

    container.innerHTML = session.memberIds.map(mid => {
      const member = getMember(mid);
      if (!member) return '';
      const att = attendance.find(a => a.memberId === mid);
      const currentStatus = att ? att.status : 'Present';

      return `<div class="attendance-row">
        <div class="attendance-row-left">
          <div class="member-avatar" style="width:36px;height:36px;font-size:0.8rem">${getInitials(member.name)}</div>
          <span style="font-weight:600;font-size:0.9rem">${escapeHtml(member.name)}</span>
        </div>
        <div class="attendance-status-buttons">
          ${ATTENDANCE_STATUSES.map(status => {
            const selected = status === currentStatus;
            const selClass = selected ? ` selected-${status.toLowerCase().replace(/ /g, '-')}` : '';
            return `<button class="att-btn${selClass}" data-action="set-attendance" data-session-id="${session.id}" data-member-id="${mid}" data-status="${status}">${status}</button>`;
          }).join('')}
        </div>
      </div>`;
    }).join('');
  }

  function renderCorrections() {
    const container = document.getElementById('corrections-list');
    const pending = data.corrections.filter(c => c.status === 'pending');
    const resolved = data.corrections.filter(c => c.status !== 'pending');

    if (data.corrections.length === 0) {
      container.innerHTML = '<div class="empty-state"><div class="empty-state-icon">&#9989;</div><div class="empty-state-text">No correction requests</div></div>';
      return;
    }

    container.innerHTML = data.corrections.map(c => {
      const statusClass = c.status === 'pending' ? 'correction-pending' : c.status === 'approved' ? 'correction-approved' : 'correction-denied';
      return `<div class="correction-card">
        <div class="correction-header">
          <div>
            <strong>${escapeHtml(c.memberName)}</strong> — <span style="color:var(--text-muted)">${escapeHtml(c.taskName)}</span>
          </div>
          <span class="correction-status ${statusClass}">${c.status.charAt(0).toUpperCase() + c.status.slice(1)}</span>
        </div>
        <div class="correction-text">${escapeHtml(c.reason)}</div>
        <div style="font-size:0.75rem;color:var(--text-muted);margin-bottom:8px">${formatDate(c.createdAt)}</div>
        ${c.status === 'pending' ? `<div class="correction-actions">
          <button class="btn btn-sm btn-success" data-action="resolve-correction" data-id="${c.id}" data-resolution="approved">Approve</button>
          <button class="btn btn-sm btn-danger" data-action="resolve-correction" data-id="${c.id}" data-resolution="denied">Deny</button>
        </div>` : ''}
      </div>`;
    }).join('');
  }

  function openAddMemberModal() {
    const body = `
      <div class="form-group">
        <label for="member-name">Member Name *</label>
        <input type="text" id="member-name" class="input" placeholder="e.g. Alex Rivera" required>
      </div>`;

    const footer = `
      <button class="btn btn-outline" id="modal-cancel-btn">Cancel</button>
      <button class="btn btn-primary" id="modal-save-member-btn">Add Member</button>`;

    openModal('Add Team Member', body, footer);

    document.getElementById('modal-cancel-btn').onclick = closeModal;
    document.getElementById('modal-save-member-btn').onclick = () => {
      const name = document.getElementById('member-name').value.trim();
      if (!name) { showToast('Please enter a name', 'error'); return; }

      const member = {
        id: uid(),
        name,
        joinedAt: new Date().toISOString().split('T')[0]
      };
      data.members.push(member);
      saveData();
      closeModal();
      showToast(`Member "${name}" added`, 'success');
      renderScreen('team');
    };
  }

  // ─── Reports ─────────────────────────────────────────
  function renderReports() {
    renderReportSummary();
    renderReportHistory();
  }

  function renderReportSummary() {
    const sel = document.getElementById('report-session-select');
    const currentVal = sel.value;
    sel.innerHTML = data.sessions.map(s =>
      `<option value="${s.id}">${escapeHtml(s.areaName)} (${formatDate(s.date)})</option>`
    ).join('');
    if (currentVal && data.sessions.find(s => s.id === currentVal)) {
      sel.value = currentVal;
    } else if (data.settings.activeSessionId) {
      sel.value = data.settings.activeSessionId;
    }

    const session = data.sessions.find(s => s.id === sel.value);
    const container = document.getElementById('report-summary-content');

    if (!session) {
      container.innerHTML = '<div class="empty-state"><div class="empty-state-text">No sessions to report</div></div>';
      return;
    }

    const tasks = session.tasks;
    const completedCount = tasks.filter(t => t.status === 'Completed').length;
    const totalEstTime = tasks.reduce((sum, t) => sum + (t.estimatedTime || 0), 0);

    // Member summary table
    const memberRows = session.memberIds.map(mid => {
      const member = getMember(mid);
      if (!member) return '';
      const memberTasks = tasks.filter(t => t.assignedTo === mid);
      const memberCompleted = memberTasks.filter(t => t.status === 'Completed').length;
      const att = (session.attendance || []).find(a => a.memberId === mid);
      const attStatus = att ? att.status : 'Not recorded';
      const extraHelp = tasks.filter(t => t.assignedTo !== mid && t.notes && t.notes.toLowerCase().includes(member.name.toLowerCase())).length;

      return `<tr>
        <td><strong>${escapeHtml(member.name)}</strong></td>
        <td>${memberTasks.length}</td>
        <td>${memberCompleted}</td>
        <td><span class="status-badge ${attStatus === 'Present' ? 'badge-completed' : attStatus === 'Late' ? 'badge-needs-review' : 'badge-not-started'}">${escapeHtml(attStatus)}</span></td>
        <td>${extraHelp > 0 ? extraHelp + ' references' : '—'}</td>
      </tr>`;
    }).join('');

    // Notes
    const notesWithContent = tasks.filter(t => t.notes && t.notes.trim());

    container.innerHTML = `
      <div class="report-section">
        <div class="report-section-title">&#9776; Session Overview</div>
        <div class="task-detail-grid">
          <div class="task-detail-item"><div class="task-detail-label">Area</div><div class="task-detail-value">${escapeHtml(session.areaName)}</div></div>
          <div class="task-detail-item"><div class="task-detail-label">Date</div><div class="task-detail-value">${formatDate(session.date)} at ${formatTime(session.time)}</div></div>
          <div class="task-detail-item"><div class="task-detail-label">Status</div><div class="task-detail-value">${session.status === 'active' ? 'Active' : 'Completed'}</div></div>
          <div class="task-detail-item"><div class="task-detail-label">Total Est. Time</div><div class="task-detail-value">${totalEstTime} min</div></div>
        </div>
        ${session.instructions ? `<div style="margin-top:8px"><div class="task-detail-label">Instructions</div><div style="color:var(--text-secondary);font-size:0.9rem;margin-top:4px">${escapeHtml(session.instructions)}</div></div>` : ''}
      </div>

      <div class="report-section">
        <div class="report-section-title">&#9673; Member Summary</div>
        <div style="overflow-x:auto">
          <table class="report-table">
            <thead><tr><th>Member</th><th>Tasks</th><th>Completed</th><th>Attendance</th><th>Extra Help</th></tr></thead>
            <tbody>${memberRows}</tbody>
          </table>
        </div>
      </div>

      <div class="report-section">
        <div class="report-section-title">&#9745; All Tasks</div>
        <div style="overflow-x:auto">
          <table class="report-table">
            <thead><tr><th>Task</th><th>Zone</th><th>Assigned To</th><th>Status</th><th>Difficulty</th><th>Est. Time</th></tr></thead>
            <tbody>${tasks.map(t => {
              const zone = (session.zones || []).find(z => z.id === t.zoneId);
              return `<tr>
                <td><strong>${escapeHtml(t.name)}</strong></td>
                <td>${zone ? escapeHtml(zone.name) : '—'}</td>
                <td>${escapeHtml(getMemberName(t.assignedTo))}</td>
                <td><span class="status-badge ${statusBadgeClass(t.status)}"><span class="status-dot ${statusDotClass(t.status)}" aria-hidden="true"></span> ${escapeHtml(t.status)}</span></td>
                <td><span class="task-difficulty ${diffBadgeClass(t.difficulty)}">${escapeHtml(t.difficulty)}</span></td>
                <td>${t.estimatedTime} min</td>
              </tr>`;
            }).join('')}</tbody>
          </table>
        </div>
      </div>

      ${notesWithContent.length > 0 ? `<div class="report-section">
        <div class="report-section-title">&#128221; Notes</div>
        ${notesWithContent.map(t => `<div style="margin-bottom:10px"><strong>${escapeHtml(t.name)}</strong>: <span style="color:var(--text-secondary)">${escapeHtml(t.notes)}</span></div>`).join('')}
      </div>` : ''}

      ${(data.corrections.filter(c => c.sessionId === session.id).length > 0) ? `<div class="report-section">
        <div class="report-section-title">&#9888; Correction Requests</div>
        ${data.corrections.filter(c => c.sessionId === session.id).map(c => `<div style="margin-bottom:8px">
          <strong>${escapeHtml(c.memberName)}</strong> on <em>${escapeHtml(c.taskName)}</em>: ${escapeHtml(c.reason)}
          <span class="correction-status ${c.status === 'pending' ? 'correction-pending' : c.status === 'approved' ? 'correction-approved' : 'correction-denied'}" style="margin-left:8px">${c.status}</span>
        </div>`).join('')}
      </div>` : ''}`;
  }

  function renderReportHistory() {
    const container = document.getElementById('report-history-content');
    if (data.sessions.length === 0) {
      container.innerHTML = '<div class="empty-state"><div class="empty-state-text">No session history</div></div>';
      return;
    }

    container.innerHTML = data.sessions.map(s => {
      const totalTasks = s.tasks.length;
      const completed = s.tasks.filter(t => t.status === 'Completed').length;
      const pct = totalTasks > 0 ? Math.round((completed / totalTasks) * 100) : 0;

      return `<div class="session-card">
        <div class="session-card-header">
          <div class="session-card-title">${escapeHtml(s.areaName)}</div>
          <span class="session-badge ${s.status === 'active' ? 'badge-active' : 'badge-completed'}">${s.status === 'active' ? 'Active' : 'Completed'}</span>
        </div>
        <div class="session-card-meta">
          <span>&#128197; ${formatDate(s.date)}</span>
          <span>&#128336; ${formatTime(s.time)}</span>
          <span>&#9745; ${completed}/${totalTasks} tasks (${pct}%)</span>
        </div>
        <div class="progress-bar-container" style="margin-top:8px">
          <div class="progress-bar" style="width:${pct}%"></div>
        </div>
      </div>`;
    }).join('');
  }

  // ─── CSV Export ──────────────────────────────────────
  function exportCSV() {
    const sel = document.getElementById('report-session-select');
    const session = data.sessions.find(s => s.id === sel.value);
    if (!session) { showToast('Select a session to export', 'error'); return; }

    const rows = [['Task', 'Zone', 'Assigned To', 'Status', 'Difficulty', 'Estimated Time (min)', 'Notes']];
    session.tasks.forEach(t => {
      const zone = (session.zones || []).find(z => z.id === t.zoneId);
      rows.push([
        t.name,
        zone ? zone.name : '',
        getMemberName(t.assignedTo),
        t.status,
        t.difficulty,
        t.estimatedTime,
        t.notes || ''
      ]);
    });

    // Add attendance section
    rows.push([]);
    rows.push(['--- Attendance ---']);
    rows.push(['Member', 'Status']);
    (session.attendance || []).forEach(a => {
      rows.push([getMemberName(a.memberId), a.status]);
    });

    const csv = rows.map(r => r.map(cell => `"${String(cell).replace(/"/g, '""')}"`).join(',')).join('\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `cleanlead-${session.areaName.replace(/\s+/g, '-')}-${session.date}.csv`;
    a.click();
    URL.revokeObjectURL(url);
    showToast('CSV exported', 'success');
  }

  // ─── Global event delegation ─────────────────────────
  function setupEvents() {
    // Sidebar navigation
    document.querySelectorAll('.nav-item').forEach(btn => {
      btn.addEventListener('click', () => navigateTo(btn.dataset.screen));
    });

    // Mobile menu
    document.getElementById('menu-toggle').addEventListener('click', () => {
      const sidebar = document.getElementById('sidebar');
      sidebar.classList.toggle('open');
      document.getElementById('menu-toggle').setAttribute('aria-expanded', sidebar.classList.contains('open'));
    });

    // Close modal
    document.getElementById('modal-close').addEventListener('click', closeModal);
    document.getElementById('modal-overlay').addEventListener('click', (e) => {
      if (e.target === document.getElementById('modal-overlay')) closeModal();
    });

    // Tabs
    document.querySelectorAll('.tab-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const tabId = btn.dataset.tab;
        const parent = btn.closest('.screen');
        parent.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
        parent.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
        btn.classList.add('active');
        document.getElementById(tabId).classList.add('active');
      });
    });

    // Quick actions
    document.getElementById('btn-create-session-dash').addEventListener('click', openCreateSessionModal);
    document.getElementById('btn-add-task-dash').addEventListener('click', () => { navigateTo('tasks'); openAddTaskModal(); });
    document.getElementById('btn-mark-attendance-dash').addEventListener('click', () => navigateTo('team'));

    // Session buttons
    document.getElementById('btn-create-session').addEventListener('click', openCreateSessionModal);

    // Task buttons
    document.getElementById('btn-add-task').addEventListener('click', openAddTaskModal);
    document.getElementById('task-session-select').addEventListener('change', () => renderTasks());
    document.getElementById('task-zone-filter').addEventListener('change', () => renderTasks());
    document.getElementById('task-member-filter').addEventListener('change', () => renderTasks());

    // Team buttons
    document.getElementById('btn-add-member').addEventListener('click', openAddMemberModal);
    document.getElementById('attendance-session-select').addEventListener('change', () => renderAttendance());

    // Report buttons
    document.getElementById('report-session-select').addEventListener('change', () => renderReportSummary());
    document.getElementById('btn-export-csv').addEventListener('click', exportCSV);

    // Reset data buttons
    document.getElementById('btn-reset-data').addEventListener('click', resetData);
    document.getElementById('btn-reset-data-settings').addEventListener('click', resetData);

    // Global click delegation for dynamic buttons
    document.addEventListener('click', (e) => {
      const btn = e.target.closest && e.target.closest('[data-action]');
      if (!btn) return;
      const action = btn.dataset.action;

      switch (action) {
        case 'activate-session':
          data.settings.activeSessionId = btn.dataset.id;
          saveData();
          showToast('Session activated', 'success');
          renderScreen(currentScreen);
          updateTopBarSubtitle();
          break;

        case 'complete-session': {
          const s = data.sessions.find(x => x.id === btn.dataset.id);
          if (s) { s.status = 'completed'; saveData(); showToast('Session marked complete', 'success'); renderScreen(currentScreen); }
          break;
        }

        case 'reactivate-session': {
          const s = data.sessions.find(x => x.id === btn.dataset.id);
          if (s) { s.status = 'active'; saveData(); showToast('Session reactivated', 'success'); renderScreen(currentScreen); }
          break;
        }

        case 'delete-session':
          if (confirm('Delete this session? This cannot be undone.')) {
            data.sessions = data.sessions.filter(x => x.id !== btn.dataset.id);
            if (data.settings.activeSessionId === btn.dataset.id) {
              data.settings.activeSessionId = data.sessions.length > 0 ? data.sessions[0].id : null;
            }
            saveData();
            showToast('Session deleted', 'info');
            renderScreen(currentScreen);
            updateTopBarSubtitle();
          }
          break;

        case 'delete-member':
          if (confirm('Remove this member? Their task assignments will become "Unassigned".')) {
            data.members = data.members.filter(m => m.id !== btn.dataset.id);
            saveData();
            showToast('Member removed', 'info');
            renderScreen('team');
          }
          break;

        case 'set-attendance': {
          const sid = btn.dataset.sessionId;
          const mid = btn.dataset.memberId;
          const status = btn.dataset.status;
          const session = data.sessions.find(x => x.id === sid);
          if (!session) break;
          let att = session.attendance.find(a => a.memberId === mid);
          if (att) {
            att.status = status;
          } else {
            session.attendance.push({ id: uid(), sessionId: sid, memberId: mid, status, notes: '' });
          }
          saveData();
          renderAttendance();
          showToast(`Attendance updated: ${status}`, 'success');
          break;
        }

        case 'resolve-correction': {
          const c = data.corrections.find(x => x.id === btn.dataset.id);
          if (c) {
            c.status = btn.dataset.resolution;
            saveData();
            showToast(`Correction ${c.status}`, 'success');
            renderCorrections();
          }
          break;
        }
      }
    });

    // Task card clicks
    document.addEventListener('click', (e) => {
      const card = e.target.closest && e.target.closest('.task-card');
      if (card) {
        openTaskDetailModal(card.dataset.taskId);
      }
    });
    document.addEventListener('keydown', (e) => {
      const card = e.target.closest && e.target.closest('.task-card');
      if (card && (e.key === 'Enter' || e.key === ' ')) {
        e.preventDefault();
        openTaskDetailModal(card.dataset.taskId);
      }
    });

    // Keyboard shortcuts
    document.addEventListener('keydown', (e) => {
      const tag = e.target && e.target.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
      const key = e.key;
      if (key === 'Escape') { closeModal(); return; }
      const screenMap = { '1': 'dashboard', '2': 'sessions', '3': 'tasks', '4': 'team', '5': 'reports', '6': 'settings' };
      if (screenMap[key]) { navigateTo(screenMap[key]); return; }
      if (key === 'n' || key === 'N') {
        if (currentScreen === 'sessions') openCreateSessionModal();
        else if (currentScreen === 'tasks') openAddTaskModal();
        else if (currentScreen === 'team') openAddMemberModal();
      }
    });
  }

  function updateTopBarSubtitle() {
    const session = getActiveSession();
    const subtitle = document.getElementById('top-bar-subtitle');
    if (session) {
      subtitle.textContent = `${session.areaName} — ${formatDate(session.date)}`;
    } else {
      subtitle.textContent = '';
    }
  }

  function resetData() {
    if (!confirm('Reset all data? This will reload sample data and erase any changes.')) return;
    generateSampleData();
    navigateTo('dashboard');
    showToast('Sample data restored', 'success');
  }

  // ─── Top bar date ────────────────────────────────────
  function updateTopBarDate() {
    const now = new Date();
    document.getElementById('top-bar-date').textContent = now.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' });
  }

  // ─── Init ────────────────────────────────────────────
  function init() {
    if (!loadData()) {
      generateSampleData();
    } else {
      activeSessionId = data.settings.activeSessionId;
    }

    updateTopBarDate();
    setupEvents();
    navigateTo('dashboard');
  }

  // Start
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
