/**
 * QuestCast Operator Hub - Native WebRTC Dynamic Multi-Peer PWA
 * Shows ONLY connected headsets in a responsive live grid.
 * Zero laptop required. 100% Offline-capable.
 */

(function () {
  'use strict';

  const STORAGE_KEY = 'questcast_saved_ips_v2';
  const DEFAULT_IPS = ['192.168.0.232', '192.168.0.168'];

  // Application State
  let savedIps = loadSavedIps();
  const activeStations = new Map(); // ip -> { ip, name, ws, pc, cardEl, videoEl, statsInterval, reconnectTimer, lastBytes, lastTs }
  let activePttTarget = null; // null | 'broadcast' | ip
  let pttAudioContext = null;
  let pttMediaStream = null;
  let pttProcessor = null;
  let wakeLock = null;

  const isHttps = window.location.protocol === 'https:';
  const WS_PROTO = isHttps ? 'wss:' : 'ws:';
  const WS_PORT = isHttps ? 8089 : 8088;
  const HTTP_PROTO = isHttps ? 'https:' : 'http:';
  const HTTP_PORT = isHttps ? 8443 : 8080;

  // DOM Elements
  const stationsGrid = document.getElementById('stationsGrid');
  const standbyHero = document.getElementById('standbyHero');
  const activePill = document.getElementById('activePill');
  const statusBadge = document.getElementById('statusBadge');
  const savedIpsList = document.getElementById('savedIpsList');
  const quickAddIp = document.getElementById('quickAddIp');
  const btnQuickAdd = document.getElementById('btnQuickAdd');
  const broadcastBar = document.getElementById('broadcastBar');
  const btnBroadcastPtt = document.getElementById('btnBroadcastPtt');
  const btnWakeLock = document.getElementById('btnWakeLock');
  const btnAddStation = document.getElementById('btnAddStation');
  const modalAddStation = document.getElementById('modalAddStation');
  const btnCloseModal = document.getElementById('btnCloseModal');
  const btnCancelAdd = document.getElementById('btnCancelAdd');
  const btnSaveStation = document.getElementById('btnSaveStation');
  const inputStationIp = document.getElementById('inputStationIp');
  const inputStationName = document.getElementById('inputStationName');
  const sslLinks = document.getElementById('sslLinks');
  const offlineBanner = document.getElementById('offlineBanner');

  // --- Initialize Hub ---
  function init() {
    registerServiceWorker();
    setupNetworkStatus();
    setupGlobalEvents();
    renderStandbyIps();
    updateGridVisibility();
    autoRequestWakeLock();
    startMonitoringAllIps();
  }

  // --- Service Worker ---
  function registerServiceWorker() {
    if ('serviceWorker' in navigator) {
      window.addEventListener('load', () => {
        navigator.serviceWorker
          .register('./sw.js')
          .then((reg) => {
            console.log('[QuestCast Hub] ServiceWorker active:', reg.scope);
          })
          .catch((err) => {
            console.warn('[QuestCast Hub] SW register error:', err);
          });
      });
    }
  }

  function setupNetworkStatus() {
    window.addEventListener('offline', () => offlineBanner?.classList.add('visible'));
    window.addEventListener('online', () => offlineBanner?.classList.remove('visible'));
    if (!navigator.onLine && offlineBanner) offlineBanner.classList.add('visible');
  }

  // --- IP Persistence ---
  function loadSavedIps() {
    try {
      const data = localStorage.getItem(STORAGE_KEY);
      if (data) {
        const parsed = JSON.parse(data);
        if (Array.isArray(parsed) && parsed.length > 0) return parsed;
      }
    } catch (_) {}
    return [...DEFAULT_IPS];
  }

  function saveIps() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(savedIps));
    } catch (_) {}
    renderStandbyIps();
  }

  function addIp(rawIp, optionalName) {
    const ip = rawIp.trim();
    if (!ip) return;
    if (!savedIps.includes(ip)) {
      savedIps.unshift(ip);
      saveIps();
    }
    connectToHeadset(ip, optionalName);
  }

  function removeIp(ip) {
    disconnectHeadset(ip);
    savedIps = savedIps.filter((item) => item !== ip);
    saveIps();
    updateGridVisibility();
  }

  // --- Connection Engine per Headset ---
  function startMonitoringAllIps() {
    savedIps.forEach((ip) => {
      connectToHeadset(ip);
    });
  }

  function connectToHeadset(ip, customName) {
    // If already streaming, don't restart
    const existing = activeStations.get(ip);
    if (existing && existing.ws && existing.ws.readyState === WebSocket.OPEN && existing.isStreaming) {
      return;
    }

    disconnectHeadset(ip);

    const station = {
      ip,
      name: customName || `Quest 2 (${ip.substring(ip.lastIndexOf('.') + 1)})`,
      ws: null,
      pc: null,
      cardEl: null,
      videoEl: null,
      statsInterval: null,
      reconnectTimer: null,
      lastBytes: 0,
      lastTs: 0,
      isStreaming: false,
      pendingCandidates: []
    };
    activeStations.set(ip, station);

    const wsUrl = `${WS_PROTO}//${ip}:${WS_PORT}/`;
    updateStandbyIpStatus(ip, 'Connecting...');

    try {
      station.ws = new WebSocket(wsUrl);
      station.ws.binaryType = 'arraybuffer';

      station.ws.onopen = () => {
        updateStandbyIpStatus(ip, 'Signaling ready, waiting for video...');
      };

      station.ws.onmessage = async (event) => {
        try {
          if (typeof event.data === 'string') {
            const msg = JSON.parse(event.data);
            await handleSignalingMessage(station, msg);
          }
        } catch (err) {
          console.error(`[QuestCast ${ip}] JSON parse error:`, err);
        }
      };

      station.ws.onclose = () => {
        updateStandbyIpStatus(ip, 'Offline');
        handleStationDisconnected(station);
      };

      station.ws.onerror = () => {
        updateStandbyIpStatus(ip, 'Connection error');
      };

    } catch (err) {
      console.warn(`[QuestCast ${ip}] WebSocket init error:`, err);
      updateStandbyIpStatus(ip, 'Cannot reach IP');
      handleStationDisconnected(station);
    }
  }

  function handleStationDisconnected(station) {
    station.isStreaming = false;
    clearInterval(station.statsInterval);

    // Remove from the connected grid if present
    if (station.cardEl && station.cardEl.parentNode) {
      station.cardEl.parentNode.removeChild(station.cardEl);
      station.cardEl = null;
      station.videoEl = null;
    }

    if (station.pc) {
      try { station.pc.close(); } catch (_) {}
      station.pc = null;
    }

    updateGridVisibility();

    // Auto-reconnect in 4 seconds
    clearTimeout(station.reconnectTimer);
    station.reconnectTimer = setTimeout(() => {
      connectToHeadset(station.ip, station.name);
    }, 4000);
  }

  function disconnectHeadset(ip) {
    const station = activeStations.get(ip);
    if (!station) return;

    clearTimeout(station.reconnectTimer);
    clearInterval(station.statsInterval);

    if (station.ws) {
      try { station.ws.close(); } catch (_) {}
      station.ws = null;
    }
    if (station.pc) {
      try { station.pc.close(); } catch (_) {}
      station.pc = null;
    }
    if (station.cardEl && station.cardEl.parentNode) {
      station.cardEl.parentNode.removeChild(station.cardEl);
    }
    activeStations.delete(ip);
  }

  // --- WebRTC Signaling & Media Flow ---
  async function handleSignalingMessage(station, msg) {
    const type = (msg.type || msg.kind || '').toLowerCase();

    if (type === 'offer') {
      await handleOffer(station, msg.sdp);
    } else if (type === 'ice') {
      await handleIceCandidate(station, msg.candidate);
    } else if (type === 'ping') {
      if (station.ws && station.ws.readyState === WebSocket.OPEN) {
        station.ws.send(JSON.stringify({ type: 'pong' }));
      }
    }
  }

  async function handleOffer(station, sdp) {
    if (station.pc) {
      try { station.pc.close(); } catch (_) {}
    }

    station.pc = new RTCPeerConnection({
      iceServers: [],
      bundlePolicy: 'max-bundle',
      rtcpMuxPolicy: 'require'
    });

    station.pc.onicecandidate = (event) => {
      if (event.candidate && station.ws && station.ws.readyState === WebSocket.OPEN) {
        station.ws.send(JSON.stringify({
          type: 'ice',
          candidate: event.candidate.toJSON()
        }));
      }
    };

    station.pc.ontrack = (event) => {
      console.log(`[QuestCast ${station.ip}] WebRTC video track received!`);
      station.isStreaming = true;

      // Ensure the card exists in the grid
      attachStationCardToGrid(station);

      if (station.videoEl) {
        const stream = event.streams && event.streams[0] ? event.streams[0] : new MediaStream([event.track]);
        station.videoEl.srcObject = stream;
        station.videoEl.muted = true;
        station.videoEl.defaultMuted = true;
        station.videoEl.playsInline = true;
        station.videoEl.setAttribute('playsinline', '');
        station.videoEl.setAttribute('muted', '');
        station.videoEl.play().catch((err) => {
          console.warn(`[QuestCast ${station.ip}] Autoplay blocked:`, err);
        });
      }

      startStatsMonitoring(station);
      updateGridVisibility();
    };

    station.pc.onconnectionstatechange = () => {
      const state = station.pc.connectionState;
      if (state === 'failed' || state === 'disconnected') {
        handleStationDisconnected(station);
      }
    };

    try {
      await station.pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp }));
      
      // Flush any queued candidates
      while (station.pendingCandidates.length > 0) {
        const cand = station.pendingCandidates.shift();
        try {
          await station.pc.addIceCandidate(new RTCIceCandidate(cand));
        } catch (_) {}
      }

      const answer = await station.pc.createAnswer();
      await station.pc.setLocalDescription(answer);

      if (station.ws && station.ws.readyState === WebSocket.OPEN) {
        station.ws.send(JSON.stringify({
          type: 'answer',
          sdp: station.pc.localDescription.sdp
        }));
      }
    } catch (err) {
      console.error(`[QuestCast ${station.ip}] SDP negotiation failure:`, err);
    }
  }

  async function handleIceCandidate(station, candidateData) {
    if (!candidateData) return;
    if (!station.pc || !station.pc.remoteDescription) {
      station.pendingCandidates.push(candidateData);
      return;
    }
    try {
      await station.pc.addIceCandidate(new RTCIceCandidate(candidateData));
    } catch (_) {}
  }

  // --- Dynamic Grid Card Management ---
  function attachStationCardToGrid(station) {
    // If card already exists in DOM, keep it to avoid video interruption
    if (station.cardEl && document.getElementById(`card_${station.ip.replace(/\./g, '_')}`)) {
      return;
    }

    const safeId = station.ip.replace(/\./g, '_');
    const card = document.createElement('div');
    card.className = 'station-card';
    card.id = `card_${safeId}`;

    card.innerHTML = `
      <div class="card-header">
        <div class="station-meta">
          <span class="station-name">${escapeHtml(station.name)}</span>
          <span class="station-ip">${escapeHtml(station.ip)}</span>
        </div>
        <div class="card-badges">
          <span class="badge-hud" id="stats_${safeId}">Connecting...</span>
          <span class="badge-battery" id="batt_${safeId}">--%</span>
          <button class="btn-icon" id="btnFull_${safeId}" title="Fullscreen">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"/></svg>
          </button>
        </div>
      </div>

      <div class="video-container">
        <video class="station-video" id="video_${safeId}" autoplay playsinline muted></video>
      </div>

      <div class="card-actions">
        <button class="btn-talk" id="btnTalk_${safeId}">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/><line x1="8" y1="23" x2="16" y2="23"/></svg>
          <span id="talkLabel_${safeId}">HOLD TO TALK</span>
        </button>
      </div>
    `;

    station.cardEl = card;
    station.videoEl = card.querySelector(`#video_${safeId}`);

    // PTT bind
    const btnTalk = card.querySelector(`#btnTalk_${safeId}`);
    bindPttTouchEvents(btnTalk, station.ip);

    // Fullscreen bind
    const btnFull = card.querySelector(`#btnFull_${safeId}`);
    btnFull.addEventListener('click', () => toggleCardFullscreen(card));

    stationsGrid.appendChild(card);
  }

  function toggleCardFullscreen(card) {
    if (document.fullscreenElement) {
      document.exitFullscreen().catch(() => {});
      card.classList.remove('fullscreen-active');
    } else {
      if (card.requestFullscreen) {
        card.requestFullscreen().catch(() => {});
        card.classList.add('fullscreen-active');
      }
    }
  }

  function startStatsMonitoring(station) {
    clearInterval(station.statsInterval);
    station.lastBytes = 0;
    station.lastTs = 0;

    const safeId = station.ip.replace(/\./g, '_');

    station.statsInterval = setInterval(async () => {
      if (!station.pc) return;

      const statsEl = document.getElementById(`stats_${safeId}`);
      const battEl = document.getElementById(`batt_${safeId}`);

      try {
        const stats = await station.pc.getStats();
        stats.forEach((report) => {
          if (report.type === 'inbound-rtp' && report.kind === 'video') {
            const fps = report.framesPerSecond !== undefined ? Math.round(report.framesPerSecond) : 0;
            const now = report.timestamp;
            const bytes = report.bytesReceived || 0;
            let kbps = 0;
            if (station.lastTs > 0 && now > station.lastTs) {
              kbps = Math.round(((bytes - station.lastBytes) * 8) / (now - station.lastTs));
            }
            station.lastBytes = bytes;
            station.lastTs = now;

            if (statsEl) {
              const res = station.videoEl && station.videoEl.videoWidth > 0 ? `${station.videoEl.videoWidth}p` : '';
              statsEl.textContent = `${fps} FPS • ${kbps > 1000 ? (kbps / 1000).toFixed(1) + 'M' : kbps + 'k'}${res ? ' • ' + res : ''}`;
            }
          }
        });
      } catch (_) {}

      // Query battery every 10s safely via HTTPS if allowed
      pollDeviceInfo(station, battEl);
    }, 1500);
  }

  async function pollDeviceInfo(station, battEl) {
    if (!battEl) return;
    try {
      const resp = await fetch(`${HTTP_PROTO}//${station.ip}:${HTTP_PORT}/api/device-info`, {
        cache: 'no-store',
        mode: 'cors'
      });
      if (resp.ok) {
        const data = await resp.json();
        if (data.battery !== undefined && data.battery >= 0) {
          battEl.textContent = `🔋 ${data.battery}%`;
        }
      }
    } catch (_) {}
  }

  // --- Grid Visibility & Dynamic Layout Switcher ---
  function updateGridVisibility() {
    let streamingCount = 0;
    activeStations.forEach((s) => {
      if (s.isStreaming) streamingCount++;
    });

    activePill.textContent = `${streamingCount} Online`;
    statusBadge.textContent = streamingCount > 0 ? 'Live' : 'Hub';
    if (streamingCount > 0) {
      statusBadge.classList.add('live');
    } else {
      statusBadge.classList.remove('live');
    }

    if (streamingCount === 0) {
      standbyHero.style.display = 'flex';
      stationsGrid.style.display = 'none';
      broadcastBar.classList.add('hidden');
    } else {
      standbyHero.style.display = 'none';
      stationsGrid.style.display = 'grid';

      // Set grid columns based on number of active headsets
      if (streamingCount === 1) {
        stationsGrid.style.gridTemplateColumns = '1fr';
        broadcastBar.classList.add('hidden'); // 1 headset doesn't need duplicate broadcast button
      } else {
        stationsGrid.style.gridTemplateColumns = 'repeat(auto-fit, minmax(360px, 1fr))';
        broadcastBar.classList.remove('hidden'); // 2+ headsets gets broadcast bar
      }
    }
  }

  // --- Standby IP List Management ---
  function renderStandbyIps() {
    savedIpsList.innerHTML = '';
    sslLinks.innerHTML = '';

    savedIps.forEach((ip) => {
      const div = document.createElement('div');
      div.className = 'standby-ip-row';
      div.id = `row_${ip.replace(/\./g, '_')}`;
      div.innerHTML = `
        <div class="ip-info">
          <span class="ip-dot"></span>
          <span class="ip-text">${escapeHtml(ip)}</span>
          <span class="ip-status-badge" id="status_${ip.replace(/\./g, '_')}">Searching...</span>
        </div>
        <button class="btn-delete-ip" title="Remove IP">✕</button>
      `;

      div.querySelector('.btn-delete-ip').addEventListener('click', (e) => {
        e.stopPropagation();
        removeIp(ip);
      });

      div.addEventListener('click', () => {
        connectToHeadset(ip);
      });

      savedIpsList.appendChild(div);

      // Add SSL auth link to modal
      const a = document.createElement('a');
      a.href = `https://${ip}:8443`;
      a.target = '_blank';
      a.textContent = `Trust ${ip}`;
      a.style.marginRight = '8px';
      sslLinks.appendChild(a);
    });
  }

  function updateStandbyIpStatus(ip, statusText) {
    const el = document.getElementById(`status_${ip.replace(/\./g, '_')}`);
    const row = document.getElementById(`row_${ip.replace(/\./g, '_')}`);
    if (el) el.textContent = statusText;
    if (row) {
      if (statusText === 'Offline' || statusText === 'Connection error') {
        row.classList.remove('online');
      } else if (statusText.includes('ready') || statusText.includes('video')) {
        row.classList.add('online');
      }
    }
  }

  // --- Push-to-Talk (PTT) Audio Engine ---
  function bindPttTouchEvents(button, target) {
    // Touch events for mobile phones (iOS & Android)
    button.addEventListener('touchstart', (e) => {
      e.preventDefault();
      startPtt(target);
    }, { passive: false });

    button.addEventListener('touchend', (e) => {
      e.preventDefault();
      stopPtt();
    }, { passive: false });

    button.addEventListener('touchcancel', (e) => {
      e.preventDefault();
      stopPtt();
    }, { passive: false });

    // Mouse events for desktop/laptop fallback
    button.addEventListener('mousedown', (e) => {
      e.preventDefault();
      startPtt(target);
    });

    button.addEventListener('mouseup', (e) => {
      e.preventDefault();
      stopPtt();
    });

    button.addEventListener('mouseleave', () => {
      if (activePttTarget === target) {
        stopPtt();
      }
    });
  }

  async function startPtt(target) {
    if (activePttTarget) return;

    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      alert('Microphone access requires a Secure Context (HTTPS or localhost).\nPlease access via GitHub Pages.');
      return;
    }

    try {
      pttMediaStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          channelCount: 1
        }
      });

      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      pttAudioContext = new AudioCtx({ sampleRate: 16000 });
      if (pttAudioContext.state === 'suspended') {
        await pttAudioContext.resume();
      }

      const source = pttAudioContext.createMediaStreamSource(pttMediaStream);
      pttProcessor = pttAudioContext.createScriptProcessor(2048, 1, 1);

      pttProcessor.onaudioprocess = (e) => {
        if (!activePttTarget) return;
        const inputData = e.inputBuffer.getChannelData(0);
        const pcm16 = new Int16Array(inputData.length);
        for (let i = 0; i < inputData.length; i++) {
          const s = Math.max(-1, Math.min(1, inputData[i]));
          pcm16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
        }

        const buffer = pcm16.buffer;

        if (activePttTarget === 'broadcast') {
          activeStations.forEach((station) => {
            if (station.ws && station.ws.readyState === WebSocket.OPEN) {
              station.ws.send(buffer);
            }
          });
        } else {
          const station = activeStations.get(activePttTarget);
          if (station && station.ws && station.ws.readyState === WebSocket.OPEN) {
            station.ws.send(buffer);
          }
        }
      };

      source.connect(pttProcessor);
      pttProcessor.connect(pttAudioContext.destination);

      activePttTarget = target;
      const startMsg = JSON.stringify({ type: 'ptt_start' });

      if (target === 'broadcast') {
        activeStations.forEach((s) => {
          if (s.ws && s.ws.readyState === WebSocket.OPEN) s.ws.send(startMsg);
        });
        btnBroadcastPtt?.classList.add('talking');
        if (btnBroadcastPtt) btnBroadcastPtt.querySelector('span').textContent = 'BROADCASTING...';
      } else {
        const s = activeStations.get(target);
        if (s && s.ws && s.ws.readyState === WebSocket.OPEN) s.ws.send(startMsg);
        const safeId = target.replace(/\./g, '_');
        const btn = document.getElementById(`btnTalk_${safeId}`);
        const label = document.getElementById(`talkLabel_${safeId}`);
        if (btn) btn.classList.add('talking');
        if (label) label.textContent = 'TALKING...';
      }

    } catch (err) {
      console.error('[QuestCast Intercom] Mic activation failed:', err);
      alert('Microphone access is required for Push-to-Talk intercom: ' + err.message);
      stopPtt();
    }
  }

  function stopPtt() {
    if (!activePttTarget) return;

    const stopMsg = JSON.stringify({ type: 'ptt_stop' });

    if (activePttTarget === 'broadcast') {
      activeStations.forEach((s) => {
        if (s.ws && s.ws.readyState === WebSocket.OPEN) {
          try { s.ws.send(stopMsg); } catch (_) {}
        }
      });
      btnBroadcastPtt?.classList.remove('talking');
      if (btnBroadcastPtt) btnBroadcastPtt.querySelector('span').textContent = 'BROADCAST TO ALL';
    } else {
      const s = activeStations.get(activePttTarget);
      if (s && s.ws && s.ws.readyState === WebSocket.OPEN) {
        try { s.ws.send(stopMsg); } catch (_) {}
      }
      const safeId = activePttTarget.replace(/\./g, '_');
      const btn = document.getElementById(`btnTalk_${safeId}`);
      const label = document.getElementById(`talkLabel_${safeId}`);
      if (btn) btn.classList.remove('talking');
      if (label) label.textContent = 'HOLD TO TALK';
    }

    activePttTarget = null;

    if (pttProcessor) {
      try { pttProcessor.disconnect(); } catch (_) {}
      pttProcessor = null;
    }
    if (pttMediaStream) {
      try { pttMediaStream.getTracks().forEach((t) => t.stop()); } catch (_) {}
      pttMediaStream = null;
    }
    if (pttAudioContext) {
      try { pttAudioContext.close(); } catch (_) {}
      pttAudioContext = null;
    }
  }

  // --- Screen Wake Lock API ---
  async function autoRequestWakeLock() {
    if ('wakeLock' in navigator) {
      try {
        wakeLock = await navigator.wakeLock.request('screen');
        btnWakeLock?.classList.add('active');
        wakeLock.addEventListener('release', () => {
          btnWakeLock?.classList.remove('active');
          wakeLock = null;
        });
      } catch (err) {
        console.warn('[QuestCast WakeLock] Request failed:', err);
      }
    }
  }

  async function toggleWakeLock() {
    if (wakeLock) {
      await wakeLock.release();
      wakeLock = null;
      btnWakeLock?.classList.remove('active');
    } else {
      await autoRequestWakeLock();
    }
  }

  document.addEventListener('visibilitychange', async () => {
    if (wakeLock !== null && document.visibilityState === 'visible') {
      await autoRequestWakeLock();
    }
  });

  // --- UI Event Handlers ---
  function setupGlobalEvents() {
    btnWakeLock?.addEventListener('click', toggleWakeLock);

    // Quick Add on Standby Screen
    btnQuickAdd?.addEventListener('click', () => {
      const ip = quickAddIp?.value.trim();
      if (ip) {
        addIp(ip);
        quickAddIp.value = '';
      }
    });

    quickAddIp?.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        const ip = quickAddIp.value.trim();
        if (ip) {
          addIp(ip);
          quickAddIp.value = '';
        }
      }
    });

    // Broadcast PTT Bind
    if (btnBroadcastPtt) {
      bindPttTouchEvents(btnBroadcastPtt, 'broadcast');
    }

    // Modal Add IP
    btnAddStation?.addEventListener('click', () => {
      inputStationIp.value = '';
      inputStationName.value = '';
      modalAddStation?.classList.add('open');
    });

    btnCloseModal?.addEventListener('click', () => modalAddStation?.classList.remove('open'));
    btnCancelAdd?.addEventListener('click', () => modalAddStation?.classList.remove('open'));

    btnSaveStation?.addEventListener('click', () => {
      const ip = inputStationIp?.value.trim();
      const name = inputStationName?.value.trim();
      if (ip) {
        addIp(ip, name);
        modalAddStation?.classList.remove('open');
      } else {
        alert('Please enter a valid Quest IP address.');
      }
    });
  }

  function escapeHtml(str) {
    if (!str) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  // Run on DOM ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
