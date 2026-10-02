/**
 * QuestCast Receiver Application - Native WebRTC Browser Client
 * Includes Push-to-Talk (PTT) Operator Intercom and App/Date Audit Logging.
 * 100% Offline / Local Network Only. Zero external dependencies.
 */

(function () {
  'use strict';

  // DOM Elements - Video & Diagnostics
  const remoteVideo = document.getElementById('remoteVideo');
  const statusBadge = document.getElementById('statusBadge');
  const resStat = document.getElementById('resStat');
  const fpsStat = document.getElementById('fpsStat');
  const bitrateStat = document.getElementById('bitrateStat');
  const overlayCard = document.getElementById('overlayCard');
  const overlayTitle = document.getElementById('overlayTitle');
  const overlayMessage = document.getElementById('overlayMessage');
  const spinner = document.getElementById('spinner');
  const btnRetryManual = document.getElementById('btnRetryManual');
  const btnFullscreen = document.getElementById('btnFullscreen');
  const btnReconnect = document.getElementById('btnReconnect');
  const btnSound = document.getElementById('btnSound');
  const iconSoundOn = document.getElementById('iconSoundOn');
  const iconSoundOff = document.getElementById('iconSoundOff');
  const iconExpand = document.getElementById('iconExpand');
  const iconCompress = document.getElementById('iconCompress');
  const btnExitFullscreen = document.getElementById('btnExitFullscreen');
  const appContainer = document.getElementById('appContainer');

  // DOM Elements - Push-to-Talk Intercom
  const btnPtt = document.getElementById('btnPtt');
  const pttText = document.getElementById('pttText');
  const pttFloatingIndicator = document.getElementById('pttFloatingIndicator');

  // DOM Elements - Audit Log Modal
  const btnAuditLog = document.getElementById('btnAuditLog');
  const modalAuditLog = document.getElementById('modalAuditLog');
  const btnCloseAuditModal = document.getElementById('btnCloseAuditModal');
  const tabByApp = document.getElementById('tabByApp');
  const tabByDate = document.getElementById('tabByDate');
  const tabRawSessions = document.getElementById('tabRawSessions');
  const filterDateSelect = document.getElementById('filterDateSelect');
  const filterAppSelect = document.getElementById('filterAppSelect');
  const btnExportCsv = document.getElementById('btnExportCsv');
  const btnClearLog = document.getElementById('btnClearLog');
  const auditContent = document.getElementById('auditContent');
  const metricTotalTime = document.getElementById('metricTotalTime');
  const metricTotalSessions = document.getElementById('metricTotalSessions');
  const metricAppsCount = document.getElementById('metricAppsCount');
  const metricActiveApp = document.getElementById('metricActiveApp');

  // WebRTC & WebSocket State
  let ws = null;
  let pc = null;
  let statsInterval = null;
  let reconnectTimer = null;
  let lastBytesReceived = 0;
  let lastTimestamp = 0;
  let isManuallyDisconnected = false;
  let pendingCandidates = [];

  const isHttps = window.location.protocol === 'https:';
  const WS_PORT = isHttps ? 8089 : 8088;
  const currentHost = window.location.hostname || '127.0.0.1';

  function getWsUrl() {
    const proto = isHttps ? 'wss:' : 'ws:';
    return `${proto}//${currentHost}:${WS_PORT}/`;
  }

  function setStatus(text, type) {
    statusBadge.textContent = text;
    statusBadge.className = 'badge';
    if (type) statusBadge.classList.add(type);
  }

  function showOverlay(title, message, isError = false) {
    overlayTitle.textContent = title;
    overlayMessage.textContent = message;
    overlayCard.classList.remove('hidden');

    if (isError) {
      spinner.classList.add('hidden');
      btnRetryManual.classList.remove('hidden');
    } else {
      spinner.classList.remove('hidden');
      btnRetryManual.classList.add('hidden');
    }
  }

  function hideOverlay() {
    overlayCard.classList.add('hidden');
  }

  // --- WebRTC Screen Mirroring Pipeline ---
  function connect() {
    if (ws) {
      try { ws.close(); } catch (_) {}
      ws = null;
    }
    if (pc) {
      try { pc.close(); } catch (_) {}
      pc = null;
    }

    clearTimeout(reconnectTimer);
    clearInterval(statsInterval);
    pendingCandidates = [];

    const wsUrl = getWsUrl();
    setStatus('Connecting...', null);
    showOverlay('Connecting to QuestCast...', `Connecting to WebSocket signaling at ${wsUrl}`);

    try {
      ws = new WebSocket(wsUrl);
      ws.binaryType = 'arraybuffer';

      ws.onopen = () => {
        setStatus('Ready for Stream', null);
        showOverlay('Connected to Quest', 'Waiting for Meta Quest 2 screen capture...');
      };

      ws.onmessage = async (event) => {
        try {
          if (typeof event.data === 'string') {
            const msg = JSON.parse(event.data);
            handleSignalingMessage(msg);
          }
        } catch (e) {
          console.error('[QuestCast] Invalid message from signaling server:', e);
        }
      };

      ws.onclose = () => {
        setStatus('Disconnected', 'error');
        showOverlay('Connection Closed', 'Signaling server disconnected. Reconnecting in 3 seconds...', true);
        scheduleReconnect();
      };

      ws.onerror = (err) => {
        console.warn('[QuestCast] WebSocket signaling error:', err);
        setStatus('Error', 'error');
      };
    } catch (e) {
      console.error('[QuestCast] Failed to initialize WebSocket:', e);
      setStatus('Error', 'error');
      showOverlay('Connection Failed', `Could not connect to ${wsUrl}: ${e.message}`, true);
      scheduleReconnect();
    }
  }

  function scheduleReconnect() {
    if (isManuallyDisconnected) return;
    clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(connect, 3000);
  }

  async function handleSignalingMessage(msg) {
    const type = (msg.type || msg.kind || '').toLowerCase();
    if (type === 'offer') {
      await handleOffer(msg.sdp);
    } else if (type === 'ice') {
      await handleRemoteIceCandidate(msg.candidate);
    } else if (type === 'ping') {
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'pong' }));
      }
    }
  }

  function initPeerConnection() {
    if (pc) {
      try { pc.close(); } catch (_) {}
    }

    pc = new RTCPeerConnection({
      iceServers: [],
      bundlePolicy: 'max-bundle',
      rtcpMuxPolicy: 'require'
    });

    pc.onicecandidate = (event) => {
      if (event.candidate && ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
          type: 'ice',
          candidate: event.candidate.toJSON()
        }));
      }
    };

    pc.ontrack = (event) => {
      if (event.streams && event.streams[0]) {
        remoteVideo.srcObject = event.streams[0];
      } else {
        const stream = new MediaStream();
        stream.addTrack(event.track);
        remoteVideo.srcObject = stream;
      }

      remoteVideo.play().catch((e) => {
        console.warn('[QuestCast] Autoplay prevented, requires user interaction:', e);
      });

      hideOverlay();
      setStatus('Live Stream', 'connected');
      startStatsPolling();
    };

    pc.onconnectionstatechange = () => {
      const state = pc.connectionState;
      if (state === 'connected') {
        setStatus('Live Stream', 'connected');
        hideOverlay();
      } else if (state === 'connecting') {
        setStatus('Negotiating...', null);
      } else if (state === 'disconnected' || state === 'failed') {
        setStatus('Reconnecting', 'error');
        showOverlay('Stream Interrupted', 'WebRTC connection dropped. Recovering...', true);
        if (state === 'failed') {
          setTimeout(() => {
            if (pc && pc.connectionState === 'failed') {
              connect();
            }
          }, 2000);
        }
      }
    };

    pc.oniceconnectionstatechange = () => {
      if (pc.iceConnectionState === 'connected' || pc.iceConnectionState === 'completed') {
        setStatus('Live Stream', 'connected');
        hideOverlay();
      }
    };
  }

  async function handleOffer(sdp) {
    initPeerConnection();
    try {
      await pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp }));
      while (pendingCandidates.length > 0) {
        const candidate = pendingCandidates.shift();
        try {
          await pc.addIceCandidate(new RTCIceCandidate(candidate));
        } catch (_) {}
      }

      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);

      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
          type: 'answer',
          sdp: pc.localDescription.sdp
        }));
      }
    } catch (e) {
      console.error('[QuestCast] Failed to handle SDP offer:', e);
      showOverlay('Handshake Error', 'Failed to negotiate parameters: ' + e.message, true);
    }
  }

  async function handleRemoteIceCandidate(candidateData) {
    if (!candidateData) return;
    if (!pc || !pc.remoteDescription) {
      pendingCandidates.push(candidateData);
      return;
    }
    try {
      await pc.addIceCandidate(new RTCIceCandidate(candidateData));
    } catch (_) {}
  }

  function startStatsPolling() {
    clearInterval(statsInterval);
    lastBytesReceived = 0;
    lastTimestamp = 0;

    statsInterval = setInterval(async () => {
      if (!pc || remoteVideo.paused) return;

      if (remoteVideo.videoWidth > 0 && remoteVideo.videoHeight > 0) {
        resStat.textContent = `${remoteVideo.videoWidth} \u00D7 ${remoteVideo.videoHeight}`;
      }

      try {
        const stats = await pc.getStats();
        stats.forEach((report) => {
          if (report.type === 'inbound-rtp' && report.kind === 'video') {
            if (report.framesPerSecond !== undefined) {
              fpsStat.textContent = `${Math.round(report.framesPerSecond)} FPS`;
            }
            const now = report.timestamp;
            const bytes = report.bytesReceived || 0;
            if (lastTimestamp > 0 && now > lastTimestamp) {
              const bitrateKbps = Math.round(((bytes - lastBytesReceived) * 8) / (now - lastTimestamp));
              if (bitrateKbps >= 0) {
                bitrateStat.textContent = `${bitrateKbps} kbps`;
              }
            }
            lastBytesReceived = bytes;
            lastTimestamp = now;
          }
        });
      } catch (_) {}
    }, 1000);
  }

  // --- Push-to-Talk (PTT) Audio Intercom Engine ---
  let pttAudioContext = null;
  let pttMediaStream = null;
  let pttProcessor = null;
  let isPttActive = false;

  async function startPtt() {
    if (isPttActive) return;

    if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
      const httpsUrl = `https://${window.location.hostname}:8443${window.location.pathname}`;
      const proceed = confirm(
        'Push-to-Talk Microphone requires a Secure Connection (HTTPS).\n\n' +
        'Click OK to switch to the secure HTTPS receiver page:\n' + httpsUrl + '\n\n' +
        '(Your browser will prompt you once to tap "Advanced" -> "Proceed" to trust the local headset certificate).'
      );
      if (proceed) {
        window.location.href = httpsUrl;
      }
      return;
    }

    if (!ws || ws.readyState !== WebSocket.OPEN) {
      alert('WebSocket is disconnected from Quest. Cannot start intercom.');
      return;
    }

    try {
      // 1. Request microphone access from browser
      pttMediaStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          channelCount: 1
        }
      });

      // 2. Initialize Web Audio Context at 16000 Hz
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      pttAudioContext = new AudioCtx({ sampleRate: 16000 });
      if (pttAudioContext.state === 'suspended') {
        await pttAudioContext.resume();
      }

      const source = pttAudioContext.createMediaStreamSource(pttMediaStream);
      // 2048 samples = ~128ms chunks for low-latency voice
      pttProcessor = pttAudioContext.createScriptProcessor(2048, 1, 1);

      pttProcessor.onaudioprocess = (e) => {
        if (!isPttActive) return;
        const inputData = e.inputBuffer.getChannelData(0);
        const pcm16 = new Int16Array(inputData.length);
        for (let i = 0; i < inputData.length; i++) {
          const s = Math.max(-1, Math.min(1, inputData[i]));
          pcm16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
        }

        if (ws && ws.readyState === WebSocket.OPEN) {
          ws.send(pcm16.buffer);
        }
      };

      source.connect(pttProcessor);
      pttProcessor.connect(pttAudioContext.destination);

      isPttActive = true;
      ws.send(JSON.stringify({ type: 'ptt_start' }));

      // Update UI
      btnPtt.classList.add('ptt-active');
      pttText.textContent = 'TALKING...';
      pttFloatingIndicator.classList.remove('hidden');
    } catch (err) {
      console.error('[QuestCast PTT] Microphone permission failed:', err);
      alert('Microphone access is required for Push-to-Talk. Please allow microphone permissions in your browser.');
      stopPtt();
    }
  }

  function stopPtt() {
    if (!isPttActive) return;
    isPttActive = false;

    if (ws && ws.readyState === WebSocket.OPEN) {
      try {
        ws.send(JSON.stringify({ type: 'ptt_stop' }));
      } catch (_) {}
    }

    if (pttProcessor) {
      try { pttProcessor.disconnect(); } catch (_) {}
      pttProcessor = null;
    }
    if (pttMediaStream) {
      try { pttMediaStream.getTracks().forEach(t => t.stop()); } catch (_) {}
      pttMediaStream = null;
    }
    if (pttAudioContext) {
      try { pttAudioContext.close(); } catch (_) {}
      pttAudioContext = null;
    }

    btnPtt.classList.remove('ptt-active');
    pttText.textContent = 'Push to Talk';
    pttFloatingIndicator.classList.add('hidden');
  }

  // PTT event bindings: hold to talk & click toggle
  btnPtt.addEventListener('mousedown', (e) => {
    e.preventDefault();
    startPtt();
  });
  window.addEventListener('mouseup', () => {
    if (isPttActive) stopPtt();
  });

  // Mobile touch support for PTT
  btnPtt.addEventListener('touchstart', (e) => {
    e.preventDefault();
    startPtt();
  }, { passive: false });
  btnPtt.addEventListener('touchend', (e) => {
    e.preventDefault();
    stopPtt();
  });

  // --- App-Based & Date-Based Audit Log Engine ---
  let activeAuditView = 'app'; // 'app', 'date', or 'raw'
  let cachedAuditData = null;

  async function fetchAuditLog() {
    auditContent.innerHTML = '<p style="color:var(--text-dim); text-align:center; padding:20px;">Fetching audit records...</p>';
    const dateFilter = filterDateSelect.value;
    const appFilter = filterAppSelect.value;

    let url = `/api/audit-log?view=full`;
    if (dateFilter) url += `&date=${encodeURIComponent(dateFilter)}`;
    if (appFilter) url += `&app=${encodeURIComponent(appFilter)}`;

    try {
      const res = await fetch(url);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      cachedAuditData = data;

      // Update Top Metrics
      if (data.summary) {
        metricTotalTime.textContent = data.summary.totalDurationFormatted || '0m';
        metricTotalSessions.textContent = data.summary.totalSessions || 0;
        metricAppsCount.textContent = data.summary.totalAppsCount || 0;
        metricActiveApp.textContent = data.summary.activeApp ? `${data.summary.activeApp} (${data.summary.activeDuration})` : 'Standby';
      }

      // Populate filter dropdowns if not already populated
      if (filterDateSelect.options.length <= 1 && data.availableDates) {
        data.availableDates.forEach(d => {
          const opt = document.createElement('option');
          opt.value = d;
          opt.textContent = d;
          filterDateSelect.appendChild(opt);
        });
      }

      if (filterAppSelect.options.length <= 1 && data.availableApps) {
        data.availableApps.forEach(a => {
          const opt = document.createElement('option');
          opt.value = a;
          opt.textContent = a;
          filterAppSelect.appendChild(opt);
        });
      }

      renderAuditView();
    } catch (e) {
      auditContent.innerHTML = `<p style="color:var(--accent-red); text-align:center; padding:20px;">Failed to load audit records: ${e.message}</p>`;
    }
  }

  function renderAuditView() {
    if (!cachedAuditData) return;

    if (activeAuditView === 'app') {
      renderByAppView(cachedAuditData.byApp || []);
    } else if (activeAuditView === 'date') {
      renderByDateView(cachedAuditData.byDate || []);
    } else {
      renderRawSessionsView(cachedAuditData.sessions || []);
    }
  }

  function renderByAppView(byApp) {
    if (byApp.length === 0) {
      auditContent.innerHTML = '<p style="color:var(--text-dim); text-align:center; padding:24px;">No app activity recorded yet.</p>';
      return;
    }

    let html = '<table class="audit-table">';
    html += '<thead><tr><th>Game / Application</th><th>Total Play Time</th><th>Sessions</th><th>Dates Active</th><th>First Played</th><th>Last Played</th></tr></thead><tbody>';

    byApp.forEach(item => {
      const datesCount = (item.datesPlayed && item.datesPlayed.length) || 1;
      html += `<tr>
        <td>
          <div class="game-title">
            <span>${escapeHtml(item.appName)}</span>
            <span class="game-badge">${datesCount} day${datesCount > 1 ? 's' : ''}</span>
          </div>
          <div style="font-size:0.75rem; color:var(--text-dim); font-family:monospace; margin-top:2px;">${escapeHtml(item.packageName)}</div>
        </td>
        <td><span class="duration-tag">${item.totalDurationFormatted}</span></td>
        <td><strong>${item.totalSessions}</strong></td>
        <td><span class="date-pill">${(item.datesPlayed || []).join(', ')}</span></td>
        <td><span class="time-pill">${item.firstPlayed || '-'}</span></td>
        <td><span class="time-pill">${item.lastPlayed || '-'}</span></td>
      </tr>`;
    });

    html += '</tbody></table>';
    auditContent.innerHTML = html;
  }

  function renderByDateView(byDate) {
    if (byDate.length === 0) {
      auditContent.innerHTML = '<p style="color:var(--text-dim); text-align:center; padding:24px;">No date records found.</p>';
      return;
    }

    let html = '';
    byDate.forEach(day => {
      html += `
        <div style="background:rgba(255,255,255,0.03); border:1px solid rgba(255,255,255,0.08); border-radius:10px; margin-bottom:14px; padding:14px;">
          <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:10px;">
            <div style="font-size:1.05rem; font-weight:700; color:var(--accent-amber);">
              Date: ${day.date}
            </div>
            <div style="display:flex; gap:12px; font-size:0.85rem;">
              <span>Total Gaming: <strong class="duration-tag">${day.totalDurationFormatted}</strong></span>
              <span>Sessions: <strong>${day.totalSessions}</strong></span>
            </div>
          </div>
          <div style="font-size:0.8rem; color:var(--text-dim); margin-bottom:8px;">
            Games played: <strong style="color:var(--text-main);">${(day.appsPlayed || []).join(', ')}</strong>
          </div>
          <table class="audit-table" style="font-size:0.8rem;">
            <thead><tr><th>Game</th><th>Start Time</th><th>End Time</th><th>Duration</th><th>Status</th></tr></thead>
            <tbody>
      `;

      (day.sessions || []).forEach(s => {
        html += `<tr>
          <td style="font-weight:600; color:var(--accent-cyan);">${escapeHtml(s.appName)}</td>
          <td>${s.formattedStartTime}</td>
          <td>${s.formattedEndTime}</td>
          <td><span class="duration-tag">${s.formattedDuration}</span></td>
          <td>${s.isActive ? '<span style="color:var(--accent-green); font-weight:700;">Active</span>' : 'Ended'}</td>
        </tr>`;
      });

      html += '</tbody></table></div>';
    });

    auditContent.innerHTML = html;
  }

  function renderRawSessionsView(sessions) {
    if (sessions.length === 0) {
      auditContent.innerHTML = '<p style="color:var(--text-dim); text-align:center; padding:24px;">No sessions found matching current filter.</p>';
      return;
    }

    let html = '<table class="audit-table"><thead><tr><th>Date</th><th>Game Name</th><th>Start</th><th>End</th><th>Duration</th><th>Status</th></tr></thead><tbody>';
    sessions.forEach(s => {
      html += `<tr>
        <td><span class="date-pill">${s.date}</span></td>
        <td style="font-weight:600; color:var(--text-main);">${escapeHtml(s.appName)}</td>
        <td><span class="time-pill">${s.formattedStartTime}</span></td>
        <td><span class="time-pill">${s.formattedEndTime}</span></td>
        <td><span class="duration-tag">${s.formattedDuration}</span></td>
        <td>${s.isActive ? '<span style="color:var(--accent-green); font-weight:700;">Active</span>' : 'Ended'}</td>
      </tr>`;
    });
    html += '</tbody></table>';
    auditContent.innerHTML = html;
  }

  function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  // Audit Tab Buttons
  tabByApp.addEventListener('click', () => {
    activeAuditView = 'app';
    tabByApp.classList.add('active');
    tabByDate.classList.remove('active');
    tabRawSessions.classList.remove('active');
    renderAuditView();
  });

  tabByDate.addEventListener('click', () => {
    activeAuditView = 'date';
    tabByDate.classList.add('active');
    tabByApp.classList.remove('active');
    tabRawSessions.classList.remove('active');
    renderAuditView();
  });

  tabRawSessions.addEventListener('click', () => {
    activeAuditView = 'raw';
    tabRawSessions.classList.add('active');
    tabByApp.classList.remove('active');
    tabByDate.classList.remove('active');
    renderAuditView();
  });

  filterDateSelect.addEventListener('change', fetchAuditLog);
  filterAppSelect.addEventListener('change', fetchAuditLog);

  // CSV Export Trigger
  btnExportCsv.addEventListener('click', () => {
    const date = filterDateSelect.value;
    const app = filterAppSelect.value;
    let url = `/api/audit-log/export.csv?`;
    if (date) url += `date=${encodeURIComponent(date)}&`;
    if (app) url += `app=${encodeURIComponent(app)}&`;
    window.location.href = url;
  });

  // Clear Audit Log
  btnClearLog.addEventListener('click', async () => {
    if (!confirm('Are you sure you want to permanently clear the audit log records?')) return;
    try {
      const res = await fetch(`/api/audit-log/clear`, { method: 'POST' });
      if (res.ok) {
        alert('Audit log cleared.');
        fetchAuditLog();
      }
    } catch (e) {
      alert('Failed to clear log: ' + e.message);
    }
  });

  btnAuditLog.addEventListener('click', () => {
    modalAuditLog.classList.remove('hidden');
    fetchAuditLog();
  });
  btnCloseAuditModal.addEventListener('click', () => {
    modalAuditLog.classList.add('hidden');
  });

  // --- Fullscreen & Layout Controls ---
  let exitBtnTimer = null;

  function wakeExitButton() {
    if (!document.fullscreenElement) return;
    btnExitFullscreen.classList.remove('autohide');
    clearTimeout(exitBtnTimer);
    exitBtnTimer = setTimeout(() => {
      if (document.fullscreenElement) {
        btnExitFullscreen.classList.add('autohide');
      }
    }, 3000);
  }

  function enterFullscreen() {
    const el = appContainer || document.documentElement;
    const req = el.requestFullscreen || el.webkitRequestFullscreen || el.mozRequestFullScreen || el.msRequestFullscreen;
    if (req) {
      req.call(el).then(() => applyFullscreenUI(true)).catch(() => {});
    }
  }

  function exitFullscreen() {
    const exit = document.exitFullscreen || document.webkitExitFullscreen || document.mozCancelFullScreen || document.msExitFullscreen;
    if (exit && document.fullscreenElement) {
      exit.call(document).then(() => applyFullscreenUI(false)).catch(() => {});
    } else {
      applyFullscreenUI(false);
    }
  }

  function toggleFullscreen() {
    if (!document.fullscreenElement) enterFullscreen();
    else exitFullscreen();
  }

  function applyFullscreenUI(isFullscreen) {
    if (isFullscreen) {
      appContainer.classList.add('fullscreen');
      iconExpand.classList.add('hidden');
      iconCompress.classList.remove('hidden');
      btnExitFullscreen.classList.remove('hidden');
      wakeExitButton();
    } else {
      appContainer.classList.remove('fullscreen');
      iconExpand.classList.remove('hidden');
      iconCompress.classList.add('hidden');
      btnExitFullscreen.classList.add('hidden');
      btnExitFullscreen.classList.remove('autohide');
      clearTimeout(exitBtnTimer);
    }
  }

  document.addEventListener('fullscreenchange', () => applyFullscreenUI(!!document.fullscreenElement));
  document.addEventListener('webkitfullscreenchange', () => applyFullscreenUI(!!document.webkitFullscreenElement));

  function toggleSound() {
    remoteVideo.muted = !remoteVideo.muted;
    if (remoteVideo.muted) {
      iconSoundOn.classList.add('hidden');
      iconSoundOff.classList.remove('hidden');
    } else {
      iconSoundOn.classList.remove('hidden');
      iconSoundOff.classList.add('hidden');
    }
  }

  btnFullscreen.addEventListener('click', toggleFullscreen);
  btnExitFullscreen.addEventListener('click', (e) => {
    e.stopPropagation();
    exitFullscreen();
  });
  remoteVideo.addEventListener('dblclick', toggleFullscreen);

  appContainer.addEventListener('mousemove', wakeExitButton);
  appContainer.addEventListener('touchstart', wakeExitButton, { passive: true });

  btnSound.addEventListener('click', toggleSound);
  btnReconnect.addEventListener('click', () => {
    isManuallyDisconnected = false;
    connect();
  });
  btnRetryManual.addEventListener('click', () => {
    isManuallyDisconnected = false;
    connect();
  });

  // Start live stream connection
  connect();
})();
