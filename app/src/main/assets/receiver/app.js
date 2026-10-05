/**
 * QuestCast Operator Hub - Native WebRTC Dynamic Multi-Peer PWA
 * Auto-Discovers, Auto-Connects, and Streams Meta Quest Headsets.
 * Zero hardcoded IPs. 100% Offline-capable.
 */

(function () {
  'use strict';

  const STORAGE_KEY = 'questcast_saved_ips_v4';
  const DEFAULT_IPS = []; // Zero hardcoded IPs

  // Application State
  let savedIps = loadSavedIps();
  const activeStations = new Map(); // ip -> StationObject
  const dismissedIps = new Set(); // Explicitly closed headsets (will not auto-reconnect)
  let isScanning = false;
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
  const standbyTitle = document.getElementById('standbyTitle');
  const standbySubtitle = document.getElementById('standbySubtitle');
  const activePill = document.getElementById('activePill');
  const statusBadge = document.getElementById('statusBadge');
  const savedIpsList = document.getElementById('savedIpsList');
  const sleepingDevicesContainer = document.getElementById('sleepingDevicesContainer');
  const sleepingList = document.getElementById('sleepingList');
  const quickAddIp = document.getElementById('quickAddIp');
  const btnQuickAdd = document.getElementById('btnQuickAdd');
  const btnScanNetwork = document.getElementById('btnScanNetwork');
  const btnScanHero = document.getElementById('btnScanHero');
  const scanProgressBanner = document.getElementById('scanProgressBanner');
  const scanStatusText = document.getElementById('scanStatusText');
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
  const toastContainer = document.getElementById('toastContainer');

  // --- URL Helpers for 1-Tap Open / Auth ---
  function getAuthUrl(ip) {
    return isHttps ? `https://${ip}:8443/auth` : `http://${ip}:8080/auth`;
  }

  function getDirectUrl(ip) {
    return isHttps ? `https://${ip}:8443/` : `http://${ip}:8080/`;
  }

  // --- Initialize Hub ---
  function init() {
    registerServiceWorker();
    setupNetworkStatus();
    setupGlobalEvents();

    processUrlParams();
    checkSelfHost();

    renderStandbyIps();
    updateGridVisibility();
    autoRequestWakeLock();
    startMonitoringAllIps();
  }

  // --- Auto-detect IP from URL params or Hostname ---
  function processUrlParams() {
    try {
      const params = new URLSearchParams(window.location.search);
      const queryIp = params.get('ip') || params.get('connect');
      if (queryIp) {
        const ips = queryIp.split(',').map((s) => s.trim()).filter(Boolean);
        ips.forEach((ip) => {
          if (!savedIps.includes(ip)) {
            savedIps.push(ip);
          }
        });
        saveIps();
      }
    } catch (_) {}
  }

  function checkSelfHost() {
    const host = window.location.hostname;
    if (/^(\d{1,3}\.){3}\d{1,3}$/.test(host) && host !== '127.0.0.1' && !dismissedIps.has(host)) {
      if (!savedIps.includes(host)) {
        savedIps.unshift(host);
        saveIps();
      }
    }
  }

  // --- Service Worker & PWA Caching ---
  function registerServiceWorker() {
    if ('serviceWorker' in navigator) {
      window.addEventListener('load', () => {
        navigator.serviceWorker
          .register('./sw.js')
          .then((reg) => {
            console.log('[QuestCast Hub] ServiceWorker registered with scope:', reg.scope);
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
      // Purge legacy storage keys to ensure deleted devices never resurrect
      localStorage.removeItem('questcast_saved_ips_v3');
      localStorage.removeItem('questcast_saved_ips_v2');
      localStorage.removeItem('questcast_saved_ips');

      const data = localStorage.getItem(STORAGE_KEY);
      if (data !== null) {
        const parsed = JSON.parse(data);
        if (Array.isArray(parsed)) return parsed; // If user deleted all cards, return []
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
    if (!ip || !/^(\d{1,3}\.){3}\d{1,3}$/.test(ip)) return;
    dismissedIps.delete(ip);
    if (!savedIps.includes(ip)) {
      savedIps.push(ip);
      saveIps();
      showToast(`Added Headset: ${ip}`);
    }
    connectToHeadset(ip, optionalName);
  }

  function removeIp(ip) {
    dismissedIps.add(ip);
    disconnectHeadset(ip, true);
    savedIps = savedIps.filter((item) => item !== ip);
    saveIps();
    updateGridVisibility();
    showToast(`Removed Headset: ${ip}`);
  }

  // --- Connection Engine per Headset ---
  function startMonitoringAllIps() {
    savedIps.forEach((ip) => {
      connectToHeadset(ip);
    });
  }

  function connectToHeadset(ip, customName) {
    const existing = activeStations.get(ip);
    if (existing && existing.ws && existing.ws.readyState === WebSocket.OPEN && existing.isStreaming) {
      return;
    }

    disconnectHeadset(ip, false);

    let station = existing;
    if (!station) {
      station = {
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
        lastVideoTime: -1,
        consecutiveZeroFps: 0,
        isStreaming: false,
        isSleeping: false,
        sslNeeded: false,
        pendingCandidates: []
      };
      activeStations.set(ip, station);
    } else {
      if (customName) station.name = customName;
      station.ws = null;
      station.pc = null;
      station.lastBytes = 0;
      station.lastTs = 0;
      station.lastVideoTime = -1;
      station.consecutiveZeroFps = 0;
      station.isStreaming = false;
      station.isSleeping = false;
      station.pendingCandidates = [];
    }

    // Mount card in grid immediately upon connecting!
    attachStationCardToGrid(station);
    setCardState(station, 'connecting', 'Connecting...', 'Establishing WebSocket signaling link...');
    updateGridVisibility();

    const wsUrl = `${WS_PROTO}//${ip}:${WS_PORT}/`;
    updateStandbyIpStatus(ip, 'Connecting...');

    try {
      station.ws = new WebSocket(wsUrl);
      station.ws.binaryType = 'arraybuffer';

      station.ws.onopen = () => {
        station.sslNeeded = false;
        updateStandbyIpStatus(ip, 'Signaling ready, waiting for video...');
        setCardState(station, 'signaling', 'Signaling Connected', 'Waiting for WebRTC video stream...');
        // Query peer stations via LAN discovery table on this headset
        queryPeerStations(ip);
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
        updateStandbyIpStatus(ip, station.sslNeeded ? 'Needs SSL Authorization' : 'Offline');
        if (station.sslNeeded) {
          setCardState(station, 'ssl_needed', 'SSL Authorization Required', 'Self-signed certificate untrusted.');
        } else {
          setCardState(station, 'connecting', 'Reconnecting...', 'Signal disconnected, retrying...');
        }
        handleStationDisconnected(station);
      };

      station.ws.onerror = () => {
        if (isHttps) {
          station.sslNeeded = true;
          updateStandbyIpStatus(ip, 'Needs SSL Authorization');
          setCardState(station, 'ssl_needed', 'SSL Authorization Required', 'Open and accept certificate in new tab.');
        }
      };

    } catch (err) {
      console.warn(`[QuestCast ${ip}] WebSocket init error:`, err);
      station.sslNeeded = true;
      updateStandbyIpStatus(ip, 'Needs SSL Authorization');
      setCardState(station, 'ssl_needed', 'SSL Authorization Required', 'Tap below to authorize certificate.');
      handleStationDisconnected(station);
    }
  }

  function handleStationDisconnected(station) {
    station.isStreaming = false;
    station.isSleeping = false;
    station.consecutiveZeroFps = 0;
    clearInterval(station.statsInterval);

    if (station.pc) {
      try { station.pc.close(); } catch (_) {}
      station.pc = null;
    }

    updateGridVisibility();

    // Auto-reconnect in 4 seconds if not permanently removed
    clearTimeout(station.reconnectTimer);
    station.reconnectTimer = setTimeout(() => {
      if (activeStations.has(station.ip)) {
        connectToHeadset(station.ip, station.name);
      }
    }, 4000);
  }

  function disconnectHeadset(ip, removeCard = false) {
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
    if (removeCard) {
      if (station.cardEl && station.cardEl.parentNode) {
        station.cardEl.parentNode.removeChild(station.cardEl);
      }
      activeStations.delete(ip);
      updateGridVisibility();
    }
  }

  // --- Query /api/stations to automatically find all LAN peers ---
  async function queryPeerStations(ip) {
    try {
      const resp = await fetch(`${HTTP_PROTO}//${ip}:${HTTP_PORT}/api/stations`, {
        cache: 'no-store',
        mode: 'cors',
        signal: AbortSignal.timeout(2500)
      });
      if (resp.ok) {
        const peers = await resp.json();
        if (Array.isArray(peers)) {
          peers.forEach((peer) => {
            if (peer.ip && !savedIps.includes(peer.ip) && !dismissedIps.has(peer.ip)) {
              console.log(`[QuestCast Auto-Discovery] Found peer station via ${ip}:`, peer);
              addIp(peer.ip, peer.name);
            }
          });
        }
      }
    } catch (_) {}
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
    } else if (type === 'headset_sleep') {
      console.log(`[QuestCast ${station.ip}] Headset entered standby (proximity sensor / screen off)`);
      markStationSleeping(station, true);
    } else if (type === 'headset_wake') {
      console.log(`[QuestCast ${station.ip}] Headset resumed active state (proximity sensor / screen on)`);
      markStationSleeping(station, false);
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
      station.isSleeping = false;
      station.consecutiveZeroFps = 0;

      // Ensure card is in grid
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

      setCardState(station, 'streaming');
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
      
      // Flush queued candidates
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
    const safeId = station.ip.replace(/\./g, '_');
    let card = document.getElementById(`card_${safeId}`);

    if (card) {
      station.cardEl = card;
      station.videoEl = card.querySelector(`#video_${safeId}`);
      return;
    }

    card = document.createElement('div');
    card.className = 'station-card';
    card.id = `card_${safeId}`;

    const authUrl = getAuthUrl(station.ip);

    card.innerHTML = `
      <div class="card-header">
        <div class="station-meta">
          <span class="station-name">${escapeHtml(station.name)}</span>
          <span class="station-ip">${escapeHtml(station.ip)}</span>
        </div>
        <div class="card-badges">
          <span class="badge-hud" id="stats_${safeId}">Connecting...</span>
          <span class="badge-battery" id="batt_${safeId}" title="Headset Charge">--%</span>
          <span class="badge-battery-ctrl" id="ctrlBatt_${safeId}" title="Touch Controllers Battery">🎮 L:-- R:--</span>
          <a href="${authUrl}" target="_blank" class="btn-icon btn-open-tab" id="btnOpen_${safeId}" title="Open ${station.ip} in new tab (Authorize SSL / Receiver)">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
          </a>
          <button class="btn-icon" id="btnFull_${safeId}" title="Fullscreen">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"/></svg>
          </button>
          <button class="btn-icon btn-close-station" id="btnRemove_${safeId}" title="Remove Headset">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          </button>
        </div>
      </div>

      <div class="video-container">
        <video class="station-video" id="video_${safeId}" autoplay playsinline muted></video>
        
        <!-- Live Card Overlay for Standby / Connecting / SSL -->
        <div class="card-overlay" id="overlay_${safeId}">
          <div class="overlay-content">
            <div class="overlay-icon" id="overlayIcon_${safeId}">⚡</div>
            <div class="overlay-title" id="overlayTitle_${safeId}">Connecting...</div>
            <div class="overlay-desc" id="overlayDesc_${safeId}">Connecting to Quest signaling...</div>
            <div class="overlay-actions" id="overlayActions_${safeId}">
              <a href="${authUrl}" target="_blank" class="btn-overlay-open" id="overlayBtn_${safeId}">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
                <span>Open / Trust SSL in Tab ↗</span>
              </a>
            </div>
          </div>
        </div>
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

    // Remove bind
    const btnRemove = card.querySelector(`#btnRemove_${safeId}`);
    btnRemove.addEventListener('click', () => removeIp(station.ip));

    stationsGrid.appendChild(card);

    // Immediate initial poll for battery telemetry
    pollDeviceInfo(station, card.querySelector(`#batt_${safeId}`));
  }

  function setCardState(station, state, customTitle, customDesc) {
    const safeId = station.ip.replace(/\./g, '_');
    const overlay = document.getElementById(`overlay_${safeId}`);
    const overlayIcon = document.getElementById(`overlayIcon_${safeId}`);
    const overlayTitle = document.getElementById(`overlayTitle_${safeId}`);
    const overlayDesc = document.getElementById(`overlayDesc_${safeId}`);
    const overlayBtn = document.getElementById(`overlayBtn_${safeId}`);
    const statsEl = document.getElementById(`stats_${safeId}`);

    if (!overlay) return;

    if (state === 'streaming') {
      overlay.classList.add('hidden');
      station.cardEl?.classList.remove('sleeping');
      return;
    }

    overlay.classList.remove('hidden');

    if (state === 'standby') {
      station.cardEl?.classList.add('sleeping');
      if (overlayIcon) overlayIcon.textContent = '💤';
      if (overlayTitle) overlayTitle.textContent = customTitle || 'Headset in Standby';
      if (overlayDesc) overlayDesc.textContent = customDesc || 'Put on Quest to resume live stream automatically.';
      if (overlayBtn) overlayBtn.innerHTML = `<span>Open ${station.ip} in Tab ↗</span>`;
      if (statsEl) statsEl.textContent = '💤 Standby';
    } else if (state === 'ssl_needed') {
      station.cardEl?.classList.remove('sleeping');
      if (overlayIcon) overlayIcon.textContent = '🔒';
      if (overlayTitle) overlayTitle.textContent = customTitle || 'SSL Authorization Required';
      if (overlayDesc) overlayDesc.textContent = customDesc || 'Self-signed certificate must be accepted once:';
      if (overlayBtn) overlayBtn.innerHTML = `<span>🔒 Click to Authorize ${station.ip} ↗</span>`;
      if (statsEl) statsEl.textContent = 'Needs SSL';
    } else if (state === 'signaling') {
      station.cardEl?.classList.remove('sleeping');
      if (overlayIcon) overlayIcon.textContent = '📡';
      if (overlayTitle) overlayTitle.textContent = customTitle || 'Signaling Connected';
      if (overlayDesc) overlayDesc.textContent = customDesc || 'Put on headset to start live stream.';
      if (overlayBtn) overlayBtn.innerHTML = `<span>Open in New Tab ↗</span>`;
      if (statsEl) statsEl.textContent = 'Signaling Ready';
    } else {
      station.cardEl?.classList.remove('sleeping');
      if (overlayIcon) overlayIcon.textContent = '⚡';
      if (overlayTitle) overlayTitle.textContent = customTitle || 'Connecting...';
      if (overlayDesc) overlayDesc.textContent = customDesc || 'Connecting to Quest...';
      if (overlayBtn) overlayBtn.innerHTML = `<span>Open in New Tab ↗</span>`;
      if (statsEl) statsEl.textContent = 'Connecting...';
    }
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

  document.addEventListener('fullscreenchange', () => {
    if (!document.fullscreenElement) {
      document.querySelectorAll('.station-card.fullscreen-active').forEach((c) => {
        c.classList.remove('fullscreen-active');
      });
    }
  });

  // --- Real-time Stats & Standby Detection Engine ---
  function startStatsMonitoring(station) {
    clearInterval(station.statsInterval);
    station.lastBytes = 0;
    station.lastTs = 0;
    station.lastVideoTime = -1;
    station.consecutiveZeroFps = 0;

    const safeId = station.ip.replace(/\./g, '_');

    station.statsInterval = setInterval(async () => {
      if (!station.pc) return;

      const statsEl = document.getElementById(`stats_${safeId}`);
      const battEl = document.getElementById(`batt_${safeId}`);

      try {
        const stats = await station.pc.getStats();
        let currentFps = 0;
        let currentBytes = 0;
        let foundVideo = false;

        stats.forEach((report) => {
          if (report.type === 'inbound-rtp' && report.kind === 'video') {
            foundVideo = true;
            currentFps = report.framesPerSecond !== undefined ? Math.round(report.framesPerSecond) : 0;
            const now = report.timestamp;
            currentBytes = report.bytesReceived || 0;
            let kbps = 0;
            if (station.lastTs > 0 && now > station.lastTs) {
              kbps = Math.round(((currentBytes - station.lastBytes) * 8) / (now - station.lastTs));
            }

            // Check if video currentTime is advancing
            let isAdvancing = false;
            if (station.videoEl && station.videoEl.currentTime > 0) {
              if (station.videoEl.currentTime !== station.lastVideoTime) {
                isAdvancing = true;
                station.lastVideoTime = station.videoEl.currentTime;
              }
            }

            // Sleep Detection: Requires 7 consecutive checks (~10.5 seconds) of 0 bytes & 0 fps & frozen time
            if (currentFps === 0 && !isAdvancing && (currentBytes === station.lastBytes || currentBytes - station.lastBytes < 200)) {
              station.consecutiveZeroFps++;
            } else {
              station.consecutiveZeroFps = 0;
            }

            station.lastBytes = currentBytes;
            station.lastTs = now;

            if (statsEl) {
              if (station.isSleeping) {
                statsEl.textContent = '💤 Standby';
              } else {
                const res = station.videoEl && station.videoEl.videoWidth > 0 ? `${station.videoEl.videoWidth}p` : '';
                statsEl.textContent = `${currentFps} FPS • ${kbps > 1000 ? (kbps / 1000).toFixed(1) + 'M' : kbps + 'k'}${res ? ' • ' + res : ''}`;
              }
            }
          }
        });

        // Trigger standby if verified 0 FPS for ~10.5 seconds
        if (foundVideo && station.consecutiveZeroFps >= 7) {
          if (!station.isSleeping) {
            markStationSleeping(station, true);
          }
        } else if (foundVideo && (currentFps > 0 || station.consecutiveZeroFps === 0)) {
          if (station.isSleeping) {
            markStationSleeping(station, false);
          }
        }

      } catch (_) {}

      // Query battery safely via HTTP/HTTPS
      pollDeviceInfo(station, battEl);
    }, 1500);
  }

  function markStationSleeping(station, sleeping) {
    if (station.isSleeping === sleeping) return;
    station.isSleeping = sleeping;
    station.isStreaming = !sleeping;

    console.log(`[QuestCast ${station.ip}] Headset standby state: sleeping=${sleeping}`);

    if (sleeping) {
      setCardState(station, 'standby');
    } else {
      setCardState(station, 'streaming');
    }

    updateGridVisibility();
  }

  async function pollDeviceInfo(station, battEl) {
    if (!battEl && !station.cardEl) return;
    const safeId = station.id ? station.id.replace(/[^a-zA-Z0-9_-]/g, '_') : station.ip.replace(/\./g, '_');
    const ctrlBattEl = station.cardEl ? station.cardEl.querySelector(`#ctrlBatt_${safeId}`) : document.getElementById(`ctrlBatt_${safeId}`);

    try {
      const resp = await fetch(`${HTTP_PROTO}//${station.ip}:${HTTP_PORT}/api/device-info`, {
        cache: 'no-store',
        mode: 'cors'
      });
      if (resp.ok) {
        const data = await resp.json();
        if (battEl && data.battery !== undefined && data.battery >= 0) {
          const isChg = data.isCharging ? '⚡ ' : '🔋 ';
          battEl.textContent = `${isChg}${data.battery}%`;
          battEl.title = `Headset Charge: ${data.battery}%${data.isCharging ? ' (Charging)' : ''}`;
        }

        if (ctrlBattEl) {
          const hasL = data.controllerL !== undefined && data.controllerL !== null && data.controllerL >= 0;
          const hasR = data.controllerR !== undefined && data.controllerR !== null && data.controllerR >= 0;

          if (hasL && hasR) {
            ctrlBattEl.textContent = `🎮 L:${data.controllerL}% R:${data.controllerR}%`;
            ctrlBattEl.title = `Touch Controllers: Left ${data.controllerL}%, Right ${data.controllerR}%`;
            ctrlBattEl.style.opacity = '1';
          } else if (hasL) {
            ctrlBattEl.textContent = `🎮 L:${data.controllerL}% R:--`;
            ctrlBattEl.title = `Left Controller: ${data.controllerL}% (Right Standby/Offline)`;
            ctrlBattEl.style.opacity = '1';
          } else if (hasR) {
            ctrlBattEl.textContent = `🎮 L:-- R:${data.controllerR}%`;
            ctrlBattEl.title = `Right Controller: ${data.controllerR}% (Left Standby/Offline)`;
            ctrlBattEl.style.opacity = '1';
          } else {
            ctrlBattEl.textContent = `🎮 L:-- R:--`;
            ctrlBattEl.title = `Touch Controllers Standby / Awaiting Input`;
            ctrlBattEl.style.opacity = '0.75';
          }
          ctrlBattEl.style.display = 'inline-flex';
        }
      }
    } catch (_) {}
  }

  // --- Dynamic Grid Visibility Manager ---
  function updateGridVisibility() {
    const totalCount = activeStations.size;
    let streamingCount = 0;
    let sleepingCount = 0;
    const sleepingStations = [];

    activeStations.forEach((s) => {
      if (s.isStreaming && !s.isSleeping) {
        streamingCount++;
      } else if (s.isSleeping) {
        sleepingCount++;
        sleepingStations.push(s);
      }
    });

    activePill.textContent = `${streamingCount} Live • ${totalCount} Connected`;
    statusBadge.textContent = streamingCount > 0 ? 'Live' : 'Hub';

    if (streamingCount > 0) {
      statusBadge.classList.add('live');
    } else {
      statusBadge.classList.remove('live');
    }

    if (totalCount === 0) {
      // 0 headsets connected or monitored: Show Standby Hero
      standbyHero.style.display = 'flex';
      stationsGrid.style.display = 'none';
      stationsGrid.classList.remove('single-station');
      broadcastBar.classList.add('hidden');
      sleepingDevicesContainer?.classList.add('hidden');
    } else {
      // 1 or more headsets connected: Always show grid!
      standbyHero.style.display = 'none';
      stationsGrid.style.display = 'grid';

      if (totalCount === 1) {
        stationsGrid.classList.add('single-station');
        stationsGrid.style.gridTemplateColumns = '';
        broadcastBar.classList.add('hidden');
      } else {
        stationsGrid.classList.remove('single-station');
        stationsGrid.style.gridTemplateColumns = 'repeat(auto-fit, minmax(360px, 1fr))';
        if (streamingCount >= 2) {
          broadcastBar.classList.remove('hidden');
        } else {
          broadcastBar.classList.add('hidden');
        }
      }

      // Also render sleeping pill container if on standby
      if (sleepingCount > 0) {
        renderSleepingList(sleepingStations);
      }
    }
  }

  function renderSleepingList(stations) {
    if (!sleepingList) return;
    sleepingList.innerHTML = '';
    stations.forEach((s) => {
      const authUrl = getAuthUrl(s.ip);
      const pill = document.createElement('a');
      pill.href = authUrl;
      pill.target = '_blank';
      pill.className = 'sleeping-pill';
      pill.title = `Open ${s.ip} in new tab`;
      pill.innerHTML = `<span>💤 ${escapeHtml(s.name)} (${escapeHtml(s.ip)})</span><span class="pill-arrow">↗</span>`;
      sleepingList.appendChild(pill);
    });
  }

  // --- Subnet Auto-Scanner Engine ---
  async function detectSubnetsToScan() {
    const subnets = new Set();

    // 1. Subnets from existing saved IPs
    savedIps.forEach((ip) => {
      const parts = ip.split('.');
      if (parts.length === 4) {
        subnets.add(`${parts[0]}.${parts[1]}.${parts[2]}.`);
      }
    });

    // 2. WebRTC local IP leak candidate detection
    try {
      const pc = new RTCPeerConnection({ iceServers: [] });
      pc.createDataChannel('');
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);

      await new Promise((resolve) => {
        const timeout = setTimeout(resolve, 600);
        pc.onicecandidate = (e) => {
          if (!e || !e.candidate) return;
          const match = e.candidate.candidate.match(/\b(192\.168\.\d+|10\.\d+\.\d+|172\.(?:1[6-9]|2\d|3[01])\.\d+)\.\d+\b/);
          if (match) {
            subnets.add(match[1] + '.');
            clearTimeout(timeout);
            resolve();
          }
        };
      });
      pc.close();
    } catch (_) {}

    // 3. Fallbacks
    if (subnets.size === 0) {
      subnets.add('192.168.0.');
      subnets.add('192.168.1.');
    }

    return Array.from(subnets);
  }

  async function triggerSubnetScan() {
    if (isScanning) return;
    isScanning = true;
    dismissedIps.clear(); // Explicit user scan resets dismissed filter

    setScanUi(true, 'Discovering local network subnets...');

    let foundCount = 0;

    // 1. Peer discovery via existing connected stations
    for (const [ip, station] of activeStations.entries()) {
      if (station.ws && station.ws.readyState === WebSocket.OPEN) {
        try {
          const resp = await fetch(`${HTTP_PROTO}//${ip}:${HTTP_PORT}/api/stations`, {
            mode: 'cors',
            signal: AbortSignal.timeout(1200)
          });
          if (resp.ok) {
            const peers = await resp.json();
            if (Array.isArray(peers)) {
              peers.forEach((p) => {
                if (p.ip && !savedIps.includes(p.ip)) {
                  addIp(p.ip, p.name);
                  foundCount++;
                }
              });
            }
          }
        } catch (_) {}
      }
    }

    const subnetsToScan = await detectSubnetsToScan();

    for (const baseSubnet of subnetsToScan) {
      setScanUi(true, `Scanning subnet ${baseSubnet}1 - 254...`);

      const candidates = [];
      for (let i = 1; i <= 254; i++) {
        const ip = `${baseSubnet}${i}`;
        if (!savedIps.includes(ip)) {
          candidates.push(ip);
        }
      }

      const BATCH_SIZE = 20;
      for (let i = 0; i < candidates.length; i += BATCH_SIZE) {
        const batch = candidates.slice(i, i + BATCH_SIZE);
        setScanUi(true, `Probing ${batch[0]} - ${batch[batch.length - 1]} (Found: ${foundCount})...`);

        const probePromises = batch.map((ip) => probeQuestIp(ip));
        const results = await Promise.all(probePromises);

        for (const res of results) {
          if (res && res.ip) {
            foundCount++;
            addIp(res.ip, res.name);
            queryPeerStations(res.ip);
          }
        }
      }
    }

    isScanning = false;
    setScanUi(false);

    if (foundCount > 0) {
      showToast(`Scan complete: Found ${foundCount} Quest Headset(s)!`);
    } else {
      showToast(`Scan complete: No new Quest headsets found on local network.`);
    }
  }

  function probeQuestIp(ip) {
    return new Promise((resolve) => {
      let settled = false;
      const timeoutMs = 1000;

      const timer = setTimeout(() => {
        if (!settled) {
          settled = true;
          resolve(null);
        }
      }, timeoutMs);

      // 1. Try HTTP /api/device-info
      fetch(`${HTTP_PROTO}//${ip}:${HTTP_PORT}/api/device-info`, {
        method: 'GET',
        mode: 'cors',
        cache: 'no-store',
        signal: AbortSignal.timeout(800)
      })
        .then(async (resp) => {
          if (resp.ok) {
            try {
              const data = await resp.json();
              if (!settled) {
                settled = true;
                clearTimeout(timer);
                resolve({ ip, name: data.device || `Quest 2 (${ip.split('.').pop()})` });
                return;
              }
            } catch (_) {}
            if (!settled) {
              settled = true;
              clearTimeout(timer);
              resolve({ ip });
            }
          }
        })
        .catch(() => {});

      // 2. Try WebSocket
      try {
        const ws = new WebSocket(`${WS_PROTO}//${ip}:${WS_PORT}/`);
        ws.onopen = () => {
          if (!settled) {
            settled = true;
            clearTimeout(timer);
            try { ws.close(); } catch (_) {}
            resolve({ ip });
          }
        };
        ws.onerror = () => {
          // Never resolve on error
        };
      } catch (_) {}
    });
  }

  function setScanUi(scanning, text) {
    if (scanning) {
      scanProgressBanner?.classList.remove('hidden');
      if (scanStatusText) scanStatusText.textContent = text || 'Scanning Wi-Fi...';
      if (btnScanHero) btnScanHero.disabled = true;
      if (btnScanNetwork) btnScanNetwork.disabled = true;
    } else {
      scanProgressBanner?.classList.add('hidden');
      if (btnScanHero) btnScanHero.disabled = false;
      if (btnScanNetwork) btnScanNetwork.disabled = false;
    }
  }

  // --- Standby IP List & SSL Trust Modal ---
  function renderStandbyIps() {
    if (!savedIpsList) return;
    savedIpsList.innerHTML = '';
    if (sslLinks) sslLinks.innerHTML = '';

    savedIps.forEach((ip) => {
      const authUrl = getAuthUrl(ip);
      const div = document.createElement('div');
      div.className = 'standby-ip-row';
      div.id = `row_${ip.replace(/\./g, '_')}`;

      div.innerHTML = `
        <div class="ip-info">
          <span class="ip-dot"></span>
          <span class="ip-text">${escapeHtml(ip)}</span>
          <span class="ip-status-badge" id="status_${ip.replace(/\./g, '_')}">Searching...</span>
        </div>
        <div class="row-actions">
          <a href="${authUrl}" target="_blank" class="btn-open-row" title="Open ${ip} in new tab" onclick="event.stopPropagation()">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
            <span>Open ↗</span>
          </a>
          <button class="btn-delete-ip" title="Remove IP">✕</button>
        </div>
      `;

      div.querySelector('.btn-delete-ip').addEventListener('click', (e) => {
        e.stopPropagation();
        removeIp(ip);
      });

      div.addEventListener('click', () => {
        connectToHeadset(ip);
      });

      savedIpsList.appendChild(div);

      // Add 1-Tap SSL Trust Link in modal
      if (sslLinks) {
        const a = document.createElement('a');
        a.href = authUrl;
        a.target = '_blank';
        a.className = 'btn-trust-pill';
        a.innerHTML = `🔒 Open & Trust ${ip} ↗`;
        sslLinks.appendChild(a);
      }
    });
  }

  function updateStandbyIpStatus(ip, statusText) {
    const el = document.getElementById(`status_${ip.replace(/\./g, '_')}`);
    const row = document.getElementById(`row_${ip.replace(/\./g, '_')}`);
    if (el) el.textContent = statusText;
    if (row) {
      if (statusText === 'Offline') {
        row.classList.remove('online');
      } else if (statusText.includes('ready') || statusText.includes('video') || statusText.includes('Signaling')) {
        row.classList.add('online');
      }
    }
  }

  // --- Push-to-Talk (PTT) Audio Engine ---
  function bindPttTouchEvents(button, target) {
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
      alert('Microphone access requires a Secure Context (HTTPS or localhost).\nPlease access via HTTPS.');
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

  // --- Toast Notification Helper ---
  function showToast(message) {
    if (!toastContainer) return;
    const toast = document.createElement('div');
    toast.className = 'toast';
    toast.textContent = message;
    toastContainer.appendChild(toast);
    setTimeout(() => {
      try { toast.remove(); } catch (_) {}
    }, 3000);
  }

  // --- UI Event Handlers ---
  function setupGlobalEvents() {
    btnWakeLock?.addEventListener('click', toggleWakeLock);

    // Auto-Scan Wi-Fi buttons
    btnScanNetwork?.addEventListener('click', triggerSubnetScan);
    btnScanHero?.addEventListener('click', triggerSubnetScan);

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
      if (inputStationIp) inputStationIp.value = '';
      if (inputStationName) inputStationName.value = '';
      renderStandbyIps();
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
