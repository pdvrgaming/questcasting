/**
 * QuestCast Operator Hub - Native WebRTC Multi-Peer PWA
 * Direct independent streaming and Push-to-Talk Intercom for Meta Quest headsets.
 * 100% Offline-capable, zero external runtime dependencies.
 */

(function () {
  'use strict';

  // --- Configuration & Default Stations ---
  const STORAGE_KEY = 'questcast_pwa_stations_v1';
  const DEFAULT_STATIONS = [
    {
      id: 'station_168',
      name: 'Station 1 (Quest 2)',
      ip: '192.168.0.168',
      protocol: window.location.protocol === 'https:' ? 'wss' : 'ws',
      port: window.location.protocol === 'https:' ? 8089 : 8088
    },
    {
      id: 'station_232',
      name: 'Station 2 (Quest 2)',
      ip: '192.168.0.232',
      protocol: window.location.protocol === 'https:' ? 'wss' : 'ws',
      port: window.location.protocol === 'https:' ? 8089 : 8088
    }
  ];

  // App State
  let stations = loadStations();
  const stationClients = new Map(); // id -> { ws, pc, statsInterval, reconnectTimer, lastBytes, lastTs }
  let activePttTarget = null; // null = inactive, 'broadcast' = all, stationId = solo
  let pttAudioContext = null;
  let pttMediaStream = null;
  let pttProcessor = null;
  let pttAnalyser = null;
  let visualizerAnimId = null;
  let wakeLock = null;

  // DOM Elements
  const stationsGrid = document.getElementById('stationsGrid');
  const activeStationCountEl = document.getElementById('activeStationCount');
  const totalStationCountEl = document.getElementById('totalStationCount');
  const btnBroadcastPtt = document.getElementById('btnBroadcastPtt');
  const broadcastVisualizer = document.getElementById('broadcastVisualizer');
  const btnAddStation = document.getElementById('btnAddStation');
  const btnScanSubnet = document.getElementById('btnScanSubnet');
  const btnWakeLock = document.getElementById('btnWakeLock');
  const iconWakeLock = document.getElementById('iconWakeLock');
  const modalAddStation = document.getElementById('modalAddStation');
  const btnCloseModal = document.getElementById('btnCloseModal');
  const btnCancelAdd = document.getElementById('btnCancelAdd');
  const btnSaveStation = document.getElementById('btnSaveStation');
  const inputStationName = document.getElementById('inputStationName');
  const inputStationIp = document.getElementById('inputStationIp');
  const protocolChips = document.querySelectorAll('.protocol-chip');
  const modalScanSubnet = document.getElementById('modalScanSubnet');
  const btnCloseScanModal = document.getElementById('btnCloseScanModal');
  const inputSubnetBase = document.getElementById('inputSubnetBase');
  const btnStartScan = document.getElementById('btnStartScan');
  const scanProgress = document.getElementById('scanProgress');
  const scanResultsList = document.getElementById('scanResultsList');
  const offlineBanner = document.getElementById('offlineBanner');

  let selectedProtocol = window.location.protocol === 'https:' ? 'wss' : 'ws';

  // --- Initialization ---
  function init() {
    registerServiceWorker();
    setupNetworkListeners();
    renderStationGrid();
    setupGlobalEvents();
    autoRequestWakeLock();
    connectAllStations();
  }

  // --- Offline Service Worker Registration ---
  function registerServiceWorker() {
    if ('serviceWorker' in navigator) {
      window.addEventListener('load', () => {
        navigator.serviceWorker
          .register('./sw.js')
          .then((reg) => {
            console.log('[QuestCast PWA] ServiceWorker registered with scope:', reg.scope);
          })
          .catch((err) => {
            console.warn('[QuestCast PWA] ServiceWorker registration failed:', err);
          });
      });
    }
  }

  function setupNetworkListeners() {
    window.addEventListener('offline', () => {
      offlineBanner.classList.add('visible');
    });
    window.addEventListener('online', () => {
      offlineBanner.classList.remove('visible');
    });
    if (!navigator.onLine) {
      offlineBanner.classList.add('visible');
    }
  }

  // --- Station Persistence ---
  function loadStations() {
    try {
      const data = localStorage.getItem(STORAGE_KEY);
      if (data) {
        const parsed = JSON.parse(data);
        if (Array.isArray(parsed) && parsed.length > 0) return parsed;
      }
    } catch (_) {}
    return JSON.parse(JSON.stringify(DEFAULT_STATIONS));
  }

  function saveStations() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(stations));
    } catch (_) {}
    updateHeaderCounters();
  }

  function updateHeaderCounters() {
    totalStationCountEl.textContent = `${stations.length} Station${stations.length === 1 ? '' : 's'}`;
    let connected = 0;
    stationClients.forEach((client) => {
      if (client.pc && (client.pc.connectionState === 'connected' || client.pc.iceConnectionState === 'connected')) {
        connected++;
      }
    });
    activeStationCountEl.textContent = `${connected} Streaming`;
  }

  // --- DOM Rendering for Station Cards ---
  function renderStationGrid() {
    stationsGrid.innerHTML = '';
    stations.forEach((station) => {
      const card = createStationCardElement(station);
      stationsGrid.appendChild(card);
    });
    updateHeaderCounters();
  }

  function createStationCardElement(station) {
    const card = document.createElement('div');
    card.className = 'station-card';
    card.id = `card_${station.id}`;

    card.innerHTML = `
      <div class="card-header">
        <div class="station-info">
          <div>
            <div class="station-title" id="title_${station.id}">${escapeHtml(station.name)}</div>
            <div class="station-ip">${escapeHtml(station.ip)} (${station.protocol.toUpperCase()})</div>
          </div>
        </div>
        <div class="station-badges">
          <span class="badge-battery" id="battery_${station.id}">--%</span>
          <span class="badge-status" id="badge_${station.id}">Standby</span>
        </div>
      </div>

      <div class="video-stage" id="stage_${station.id}">
        <video class="video-stream" id="video_${station.id}" autoplay playsinline muted></video>
        
        <div class="video-hud">
          <span class="hud-stat" id="fps_${station.id}">0 FPS</span>
          <span class="hud-stat" id="res_${station.id}">--</span>
          <span class="hud-stat" id="bitrate_${station.id}">0 kbps</span>
        </div>

        <div class="video-overlay" id="overlay_${station.id}">
          <div class="overlay-spinner" id="spinner_${station.id}"></div>
          <div class="overlay-title" id="overlayTitle_${station.id}">Connecting to Headset...</div>
          <div class="overlay-desc" id="overlayDesc_${station.id}">Establishing direct WebRTC stream to ${escapeHtml(station.ip)}</div>
          <div class="proximity-warning" id="proxWarn_${station.id}" style="display: none;">
            <span>⚠️ Headset Standby: Wear Quest or cover forehead sensor</span>
          </div>
          <button class="btn-glass" id="btnRetry_${station.id}" style="display: none; margin-top: 8px;">Retry Connection</button>
        </div>
      </div>

      <div class="card-controls">
        <button class="btn-card-ptt" id="btnPtt_${station.id}">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/><line x1="8" y1="23" x2="16" y2="23"/></svg>
          <span id="pttText_${station.id}">HOLD TO TALK</span>
        </button>

        <div class="card-action-btns">
          <button class="btn-glass btn-icon-only" id="btnFullscreen_${station.id}" title="Toggle Fullscreen">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/></svg>
          </button>
          <button class="btn-glass btn-icon-only" id="btnReconnect_${station.id}" title="Reconnect">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67"/></svg>
          </button>
          <button class="btn-glass btn-icon-only" id="btnRemove_${station.id}" title="Remove Station" style="color: #ff5252;">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          </button>
        </div>
      </div>
    `;

    // Hook card button events
    const btnPtt = card.querySelector(`#btnPtt_${station.id}`);
    const btnFullscreen = card.querySelector(`#btnFullscreen_${station.id}`);
    const btnReconnect = card.querySelector(`#btnReconnect_${station.id}`);
    const btnRemove = card.querySelector(`#btnRemove_${station.id}`);
    const btnRetry = card.querySelector(`#btnRetry_${station.id}`);

    bindPttEvents(btnPtt, station.id);

    btnFullscreen.addEventListener('click', () => {
      toggleStationFullscreen(card);
    });

    btnReconnect.addEventListener('click', () => {
      connectStation(station);
    });

    btnRetry.addEventListener('click', () => {
      connectStation(station);
    });

    btnRemove.addEventListener('click', () => {
      if (confirm(`Remove station "${station.name}" (${station.ip})?`)) {
        removeStation(station.id);
      }
    });

    return card;
  }

  function toggleStationFullscreen(card) {
    if (card.classList.contains('fullscreen-mode')) {
      card.classList.remove('fullscreen-mode');
      if (document.fullscreenElement) {
        document.exitFullscreen().catch(() => {});
      }
    } else {
      document.querySelectorAll('.station-card').forEach((c) => c.classList.remove('fullscreen-mode'));
      card.classList.add('fullscreen-mode');
      if (card.requestFullscreen) {
        card.requestFullscreen().catch(() => {});
      }
    }
  }

  // --- Independent Direct WebRTC Pipeline per Station ---
  function connectAllStations() {
    stations.forEach((station) => {
      connectStation(station);
    });
  }

  function connectStation(station) {
    disconnectStation(station.id);

    const client = {
      ws: null,
      pc: null,
      statsInterval: null,
      reconnectTimer: null,
      lastBytes: 0,
      lastTs: 0,
      pendingCandidates: []
    };
    stationClients.set(station.id, client);

    const badge = document.getElementById(`badge_${station.id}`);
    const overlay = document.getElementById(`overlay_${station.id}`);
    const overlayTitle = document.getElementById(`overlayTitle_${station.id}`);
    const overlayDesc = document.getElementById(`overlayDesc_${station.id}`);
    const spinner = document.getElementById(`spinner_${station.id}`);
    const btnRetry = document.getElementById(`btnRetry_${station.id}`);
    const proxWarn = document.getElementById(`proxWarn_${station.id}`);

    if (badge) {
      badge.textContent = 'Connecting';
      badge.className = 'badge-status connecting';
    }
    if (overlay) overlay.classList.remove('hidden');
    if (spinner) spinner.style.display = 'block';
    if (btnRetry) btnRetry.style.display = 'none';
    if (proxWarn) proxWarn.style.display = 'none';
    if (overlayTitle) overlayTitle.textContent = 'Connecting...';
    if (overlayDesc) overlayDesc.textContent = `Establishing signaling to ${station.ip}:${station.port}`;

    const proto = station.protocol || (window.location.protocol === 'https:' ? 'wss' : 'ws');
    const port = station.port || (proto === 'wss' ? 8089 : 8088);
    const wsUrl = `${proto}://${station.ip}:${port}/`;

    try {
      client.ws = new WebSocket(wsUrl);
      client.ws.binaryType = 'arraybuffer';

      client.ws.onopen = () => {
        if (overlayTitle) overlayTitle.textContent = 'Signaling Connected';
        if (overlayDesc) overlayDesc.textContent = 'Waiting for Quest screen capture offer...';
        // Request offer from headset in case it didn't send immediately
        try {
          client.ws.send(JSON.stringify({ type: 'request_offer' }));
        } catch (_) {}
      };

      client.ws.onmessage = async (event) => {
        try {
          if (typeof event.data === 'string') {
            const msg = JSON.parse(event.data);
            await handleSignalingMessage(station, client, msg);
          }
        } catch (err) {
          console.error(`[QuestCast ${station.id}] Error parsing message:`, err);
        }
      };

      client.ws.onclose = (event) => {
        console.warn(`[QuestCast ${station.id}] WebSocket closed (code ${event.code})`);
        if (badge) {
          badge.textContent = 'Offline';
          badge.className = 'badge-status error';
        }
        if (overlay) overlay.classList.remove('hidden');
        if (spinner) spinner.style.display = 'none';
        if (btnRetry) btnRetry.style.display = 'inline-flex';
        if (overlayTitle) overlayTitle.textContent = 'Connection Disconnected';
        if (overlayDesc) {
          if (window.location.protocol === 'https:' && proto === 'wss') {
            overlayDesc.innerHTML = `Cannot reach ${station.ip}. If using self-signed SSL, <a href="https://${station.ip}:8443" target="_blank" style="color:#00e5ff;text-decoration:underline;">click here to trust certificate</a>, then retry.`;
          } else {
            overlayDesc.textContent = `Could not reach headset at ${station.ip}. Check Wi-Fi or app status.`;
          }
        }
        updateHeaderCounters();
        scheduleReconnect(station);
      };

      client.ws.onerror = (err) => {
        console.warn(`[QuestCast ${station.id}] WebSocket error:`, err);
      };

    } catch (err) {
      console.error(`[QuestCast ${station.id}] Failed creating WebSocket:`, err);
      if (badge) {
        badge.textContent = 'Error';
        badge.className = 'badge-status error';
      }
      if (overlayTitle) overlayTitle.textContent = 'Connection Error';
      scheduleReconnect(station);
    }

    // Also poll device info (battery, game) via HTTP API every 8s
    fetchDeviceInfo(station);
  }

  function scheduleReconnect(station) {
    const client = stationClients.get(station.id);
    if (!client) return;
    clearTimeout(client.reconnectTimer);
    client.reconnectTimer = setTimeout(() => {
      connectStation(station);
    }, 4000);
  }

  function disconnectStation(stationId) {
    const client = stationClients.get(stationId);
    if (!client) return;
    clearTimeout(client.reconnectTimer);
    clearInterval(client.statsInterval);
    if (client.ws) {
      try { client.ws.close(); } catch (_) {}
      client.ws = null;
    }
    if (client.pc) {
      try { client.pc.close(); } catch (_) {}
      client.pc = null;
    }
  }

  async function handleSignalingMessage(station, client, msg) {
    const type = (msg.type || msg.kind || '').toLowerCase();

    if (type === 'offer') {
      await handleRemoteOffer(station, client, msg.sdp);
    } else if (type === 'ice') {
      await handleRemoteIce(client, msg.candidate);
    } else if (type === 'ping') {
      if (client.ws && client.ws.readyState === WebSocket.OPEN) {
        client.ws.send(JSON.stringify({ type: 'pong' }));
      }
    }
  }

  async function handleRemoteOffer(station, client, sdp) {
    if (client.pc) {
      try { client.pc.close(); } catch (_) {}
    }

    client.pc = new RTCPeerConnection({
      iceServers: [],
      bundlePolicy: 'max-bundle',
      rtcpMuxPolicy: 'require'
    });

    const videoEl = document.getElementById(`video_${station.id}`);
    const badge = document.getElementById(`badge_${station.id}`);
    const overlay = document.getElementById(`overlay_${station.id}`);
    const proxWarn = document.getElementById(`proxWarn_${station.id}`);

    client.pc.onicecandidate = (event) => {
      if (event.candidate && client.ws && client.ws.readyState === WebSocket.OPEN) {
        client.ws.send(JSON.stringify({
          type: 'ice',
          candidate: event.candidate.toJSON()
        }));
      }
    };

    client.pc.ontrack = (event) => {
      if (videoEl) {
        if (event.streams && event.streams[0]) {
          videoEl.srcObject = event.streams[0];
        } else {
          const stream = new MediaStream();
          stream.addTrack(event.track);
          videoEl.srcObject = stream;
        }
        videoEl.play().catch(() => {});
      }
      if (overlay) overlay.classList.add('hidden');
      if (badge) {
        badge.textContent = 'Live';
        badge.className = 'badge-status connected';
      }
      startStationStats(station, client);
      updateHeaderCounters();
    };

    client.pc.onconnectionstatechange = () => {
      const state = client.pc.connectionState;
      if (state === 'connected') {
        if (overlay) overlay.classList.add('hidden');
        if (badge) {
          badge.textContent = 'Live';
          badge.className = 'badge-status connected';
        }
      } else if (state === 'failed' || state === 'disconnected') {
        if (badge) {
          badge.textContent = 'Interrupted';
          badge.className = 'badge-status error';
        }
      }
      updateHeaderCounters();
    };

    try {
      await client.pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp }));
      while (client.pendingCandidates.length > 0) {
        const candidate = client.pendingCandidates.shift();
        try {
          await client.pc.addIceCandidate(new RTCIceCandidate(candidate));
        } catch (_) {}
      }

      const answer = await client.pc.createAnswer();
      await client.pc.setLocalDescription(answer);

      if (client.ws && client.ws.readyState === WebSocket.OPEN) {
        client.ws.send(JSON.stringify({
          type: 'answer',
          sdp: client.pc.localDescription.sdp
        }));
      }
    } catch (err) {
      console.error(`[QuestCast ${station.id}] SDP negotiation error:`, err);
    }
  }

  async function handleRemoteIce(client, candidateData) {
    if (!candidateData) return;
    if (!client.pc || !client.pc.remoteDescription) {
      client.pendingCandidates.push(candidateData);
      return;
    }
    try {
      await client.pc.addIceCandidate(new RTCIceCandidate(candidateData));
    } catch (_) {}
  }

  function startStationStats(station, client) {
    clearInterval(client.statsInterval);
    client.lastBytes = 0;
    client.lastTs = 0;

    const fpsEl = document.getElementById(`fps_${station.id}`);
    const resEl = document.getElementById(`res_${station.id}`);
    const bitrateEl = document.getElementById(`bitrate_${station.id}`);
    const videoEl = document.getElementById(`video_${station.id}`);
    const proxWarn = document.getElementById(`proxWarn_${station.id}`);

    client.statsInterval = setInterval(async () => {
      if (!client.pc || !videoEl) return;

      if (videoEl.videoWidth > 0 && videoEl.videoHeight > 0 && resEl) {
        resEl.textContent = `${videoEl.videoWidth} \u00D7 ${videoEl.videoHeight}`;
      }

      try {
        const stats = await client.pc.getStats();
        stats.forEach((report) => {
          if (report.type === 'inbound-rtp' && report.kind === 'video') {
            if (report.framesPerSecond !== undefined && fpsEl) {
              const fps = Math.round(report.framesPerSecond);
              fpsEl.textContent = `${fps} FPS`;

              // Proximity Sensor detection: if fps is 0 for prolonged periods while video is open
              if (fps === 0 && proxWarn) {
                proxWarn.style.display = 'flex';
              } else if (proxWarn) {
                proxWarn.style.display = 'none';
              }
            }

            const now = report.timestamp;
            const bytes = report.bytesReceived || 0;
            if (client.lastTs > 0 && now > client.lastTs && bitrateEl) {
              const bitrateKbps = Math.round(((bytes - client.lastBytes) * 8) / (now - client.lastTs));
              if (bitrateKbps >= 0) {
                bitrateEl.textContent = `${bitrateKbps} kbps`;
              }
            }
            client.lastBytes = bytes;
            client.lastTs = now;
          }
        });
      } catch (_) {}
    }, 1000);
  }

  async function fetchDeviceInfo(station) {
    try {
      const httpProto = station.protocol === 'wss' ? 'https' : 'http';
      const httpPort = station.protocol === 'wss' ? 8443 : 8080;
      const resp = await fetch(`${httpProto}://${station.ip}:${httpPort}/api/device-info`, {
        cache: 'no-store',
        mode: 'cors'
      });
      if (resp.ok) {
        const info = await resp.json();
        const battEl = document.getElementById(`battery_${station.id}`);
        const titleEl = document.getElementById(`title_${station.id}`);
        if (battEl && info.battery !== undefined && info.battery >= 0) {
          battEl.textContent = `${info.battery}%${info.isCharging ? ' ⚡' : ''}`;
        }
        if (titleEl && info.currentGame && info.currentGame !== 'Standby') {
          titleEl.textContent = `${station.name} - ${info.currentGame}`;
        }
      }
    } catch (_) {}
  }

  // --- Push-to-Talk (PTT) Audio Intercom Engine ---
  function bindPttEvents(button, stationId) {
    // Touch events for mobile phones (iOS & Android)
    button.addEventListener('touchstart', (e) => {
      e.preventDefault();
      startPtt(stationId);
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
      startPtt(stationId);
    });

    button.addEventListener('mouseup', (e) => {
      e.preventDefault();
      stopPtt();
    });

    button.addEventListener('mouseleave', () => {
      if (activePttTarget === stationId) {
        stopPtt();
      }
    });
  }

  async function startPtt(target) {
    if (activePttTarget) return;

    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      alert('Microphone access requires a Secure Context (HTTPS or localhost).\nPlease access via GitHub Pages or https://');
      return;
    }

    try {
      // 1. Acquire microphone stream
      pttMediaStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          channelCount: 1
        }
      });

      // 2. Initialize Web Audio Context at 16,000 Hz
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      pttAudioContext = new AudioCtx({ sampleRate: 16000 });
      if (pttAudioContext.state === 'suspended') {
        await pttAudioContext.resume();
      }

      const source = pttAudioContext.createMediaStreamSource(pttMediaStream);
      pttProcessor = pttAudioContext.createScriptProcessor(2048, 1, 1);

      // Setup analyser for visualizer
      pttAnalyser = pttAudioContext.createAnalyser();
      pttAnalyser.fftSize = 64;
      source.connect(pttAnalyser);

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
          // Send to ALL open headset WebSockets
          stationClients.forEach((client) => {
            if (client.ws && client.ws.readyState === WebSocket.OPEN) {
              client.ws.send(buffer);
            }
          });
        } else {
          // Send only to the specific station
          const client = stationClients.get(activePttTarget);
          if (client && client.ws && client.ws.readyState === WebSocket.OPEN) {
            client.ws.send(buffer);
          }
        }
      };

      source.connect(pttProcessor);
      pttProcessor.connect(pttAudioContext.destination);

      activePttTarget = target;

      // Send ptt_start message
      const startMsg = JSON.stringify({ type: 'ptt_start' });
      if (target === 'broadcast') {
        stationClients.forEach((c) => {
          if (c.ws && c.ws.readyState === WebSocket.OPEN) c.ws.send(startMsg);
        });
        btnBroadcastPtt.classList.add('active');
        btnBroadcastPtt.querySelector('span').textContent = 'BROADCASTING...';
        startVisualizerAnimation();
      } else {
        const c = stationClients.get(target);
        if (c && c.ws && c.ws.readyState === WebSocket.OPEN) c.ws.send(startMsg);
        const btn = document.getElementById(`btnPtt_${target}`);
        const pttTxt = document.getElementById(`pttText_${target}`);
        const card = document.getElementById(`card_${target}`);
        if (btn) btn.classList.add('active');
        if (pttTxt) pttTxt.textContent = 'TALKING...';
        if (card) card.classList.add('talking-active');
      }

    } catch (err) {
      console.error('[QuestCast PTT] Microphone activation error:', err);
      alert('Could not start microphone intercom: ' + err.message);
      stopPtt();
    }
  }

  function stopPtt() {
    if (!activePttTarget) return;

    const stopMsg = JSON.stringify({ type: 'ptt_stop' });

    if (activePttTarget === 'broadcast') {
      stationClients.forEach((c) => {
        if (c.ws && c.ws.readyState === WebSocket.OPEN) {
          try { c.ws.send(stopMsg); } catch (_) {}
        }
      });
      btnBroadcastPtt.classList.remove('active');
      btnBroadcastPtt.querySelector('span').textContent = 'BROADCAST TO ALL';
      stopVisualizerAnimation();
    } else {
      const c = stationClients.get(activePttTarget);
      if (c && c.ws && c.ws.readyState === WebSocket.OPEN) {
        try { c.ws.send(stopMsg); } catch (_) {}
      }
      const btn = document.getElementById(`btnPtt_${activePttTarget}`);
      const pttTxt = document.getElementById(`pttText_${activePttTarget}`);
      const card = document.getElementById(`card_${activePttTarget}`);
      if (btn) btn.classList.remove('active');
      if (pttTxt) pttTxt.textContent = 'HOLD TO TALK';
      if (card) card.classList.remove('talking-active');
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

  function startVisualizerAnimation() {
    if (!broadcastVisualizer || !pttAnalyser) return;
    const ctx = broadcastVisualizer.getContext('2d');
    const width = broadcastVisualizer.width;
    const height = broadcastVisualizer.height;
    const dataArray = new Uint8Array(pttAnalyser.frequencyBinCount);

    function draw() {
      if (!activePttTarget) return;
      visualizerAnimId = requestAnimationFrame(draw);
      pttAnalyser.getByteFrequencyData(dataArray);

      ctx.clearRect(0, 0, width, height);
      const barWidth = width / 12;
      for (let i = 0; i < 12; i++) {
        const val = dataArray[i * 2] || 0;
        const barHeight = (val / 255) * height;
        ctx.fillStyle = '#ff2a6d';
        ctx.fillRect(i * (barWidth + 2), height - barHeight, barWidth, barHeight);
      }
    }
    draw();
  }

  function stopVisualizerAnimation() {
    if (visualizerAnimId) {
      cancelAnimationFrame(visualizerAnimId);
      visualizerAnimId = null;
    }
    if (broadcastVisualizer) {
      const ctx = broadcastVisualizer.getContext('2d');
      ctx.clearRect(0, 0, broadcastVisualizer.width, broadcastVisualizer.height);
    }
  }

  // --- Screen Wake Lock API ---
  async function autoRequestWakeLock() {
    if ('wakeLock' in navigator) {
      try {
        wakeLock = await navigator.wakeLock.request('screen');
        btnWakeLock.classList.add('active');
        wakeLock.addEventListener('release', () => {
          btnWakeLock.classList.remove('active');
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
      btnWakeLock.classList.remove('active');
    } else {
      await autoRequestWakeLock();
    }
  }

  document.addEventListener('visibilitychange', async () => {
    if (wakeLock !== null && document.visibilityState === 'visible') {
      await autoRequestWakeLock();
    }
  });

  // --- Global Event Handlers ---
  function setupGlobalEvents() {
    // Broadcast PTT button events
    bindPttEvents(btnBroadcastPtt, 'broadcast');

    // Wake Lock toggle
    btnWakeLock.addEventListener('click', toggleWakeLock);

    // Add Station Modal
    btnAddStation.addEventListener('click', () => {
      inputStationName.value = `Station ${stations.length + 1}`;
      inputStationIp.value = '';
      modalAddStation.classList.add('open');
    });

    btnCloseModal.addEventListener('click', () => modalAddStation.classList.remove('open'));
    btnCancelAdd.addEventListener('click', () => modalAddStation.classList.remove('open'));

    protocolChips.forEach((chip) => {
      chip.addEventListener('click', () => {
        protocolChips.forEach((c) => c.classList.remove('selected'));
        chip.classList.add('selected');
        selectedProtocol = chip.dataset.proto;
      });
    });

    btnSaveStation.addEventListener('click', () => {
      const name = inputStationName.value.trim() || `Station ${stations.length + 1}`;
      const ip = inputStationIp.value.trim();
      if (!ip) {
        alert('Please enter a valid IP address for the headset (e.g. 192.168.0.168)');
        return;
      }

      const id = 'station_' + ip.replace(/[^a-zA-Z0-9]/g, '_');
      const newStation = {
        id,
        name,
        ip,
        protocol: selectedProtocol,
        port: selectedProtocol === 'wss' ? 8089 : 8088
      };

      // Check for duplicate IP
      const existingIdx = stations.findIndex((s) => s.ip === ip);
      if (existingIdx >= 0) {
        stations[existingIdx] = newStation;
      } else {
        stations.push(newStation);
      }

      saveStations();
      renderStationGrid();
      connectStation(newStation);
      modalAddStation.classList.remove('open');
    });

    // Subnet Auto-Scanner Modal
    btnScanSubnet.addEventListener('click', () => {
      modalScanSubnet.classList.add('open');
      scanResultsList.innerHTML = '<div style="color:var(--text-muted);text-align:center;padding:12px;">Click "Start Scan" to discover active Quest headsets on your Wi-Fi network.</div>';
    });

    btnCloseScanModal.addEventListener('click', () => modalScanSubnet.classList.remove('open'));

    btnStartScan.addEventListener('click', () => {
      runSubnetScan();
    });
  }

  function removeStation(stationId) {
    disconnectStation(stationId);
    stationClients.delete(stationId);
    stations = stations.filter((s) => s.id !== stationId);
    saveStations();
    renderStationGrid();
  }

  // --- Subnet Auto-Scanner ---
  async function runSubnetScan() {
    const base = inputSubnetBase.value.trim();
    if (!base) return;

    btnStartScan.disabled = true;
    scanProgress.textContent = 'Scanning 1-254 for active QuestCast devices...';
    scanResultsList.innerHTML = '';

    const found = [];
    const concurrency = 20;

    for (let i = 1; i <= 254; i += concurrency) {
      const batch = [];
      for (let j = i; j < i + concurrency && j <= 254; j++) {
        const ip = `${base}${j}`;
        batch.push(probeIp(ip));
      }
      const results = await Promise.all(batch);
      results.forEach((res) => {
        if (res) {
          found.push(res);
          addFoundDeviceToScanList(res);
        }
      });
      scanProgress.textContent = `Scanned ${Math.min(i + concurrency - 1, 254)}/254 IPs... Found ${found.length} headset(s)`;
    }

    btnStartScan.disabled = false;
    scanProgress.textContent = `Scan complete. Found ${found.length} headset(s).`;
    if (found.length === 0) {
      scanResultsList.innerHTML = '<div style="color:var(--text-muted);text-align:center;padding:12px;">No active QuestCast headsets responded on this subnet. Make sure QuestCast app is open on the headsets.</div>';
    }
  }

  async function probeIp(ip) {
    return new Promise((resolve) => {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => {
        controller.abort();
        resolve(null);
      }, 700);

      fetch(`http://${ip}:8080/api/device-info`, {
        signal: controller.signal,
        mode: 'cors'
      })
        .then((r) => r.json())
        .then((data) => {
          clearTimeout(timeoutId);
          resolve({ ip, data });
        })
        .catch(() => {
          clearTimeout(timeoutId);
          resolve(null);
        });
    });
  }

  function addFoundDeviceToScanList(item) {
    const div = document.createElement('div');
    div.style.cssText = 'display:flex;align-items:center;justify-content:space-between;padding:10px;background:rgba(255,255,255,0.05);border-radius:8px;margin-bottom:8px;border:1px solid var(--border-glow);';
    div.innerHTML = `
      <div>
        <div style="font-weight:700;color:var(--accent-cyan);">${escapeHtml(item.data.stationName || 'Quest Headset')}</div>
        <div style="font-size:0.75rem;color:var(--text-secondary);font-family:monospace;">${item.ip} (Battery: ${item.data.battery || '--'}%)</div>
      </div>
      <button class="btn-glass btn-primary" style="padding:6px 12px;font-size:0.75rem;">+ Add to Grid</button>
    `;
    div.querySelector('button').addEventListener('click', () => {
      const id = 'station_' + item.ip.replace(/[^a-zA-Z0-9]/g, '_');
      const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
      const newStation = {
        id,
        name: item.data.stationName || `Station ${stations.length + 1}`,
        ip: item.ip,
        protocol: proto,
        port: proto === 'wss' ? 8089 : 8088
      };
      if (!stations.some((s) => s.ip === item.ip)) {
        stations.push(newStation);
        saveStations();
        renderStationGrid();
        connectStation(newStation);
      }
      modalScanSubnet.classList.remove('open');
    });
    scanResultsList.appendChild(div);
  }

  function escapeHtml(str) {
    if (!str) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  // Boot on DOM Ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
