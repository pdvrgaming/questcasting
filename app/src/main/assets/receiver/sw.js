/**
 * QuestCast Operator Hub - Offline Service Worker
 * Enables 100% offline PWA caching on iPhone and Android devices.
 */

const CACHE_NAME = 'questcast-hub-v9';
const ASSETS_TO_CACHE = [
  './',
  './index.html',
  './style.css',
  './style.css?v=8.0',
  './app.js',
  './app.js?v=8.0',
  './manifest.json',
  './icons/icon-192.png',
  './icons/icon-512.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      console.log('[QuestCast SW] Pre-caching offline PWA assets (v9)');
      return cache.addAll(ASSETS_TO_CACHE).catch((err) => {
        console.warn('[QuestCast SW] Failed pre-caching some assets:', err);
      });
    }).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))
      );
    }).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const reqUrl = event.request.url;

  // Only handle http and https requests (safely ignore chrome-extension, blob, data, etc.)
  if (!reqUrl.startsWith('http://') && !reqUrl.startsWith('https://')) {
    return;
  }

  const url = new URL(reqUrl);

  // Force HTTPS on GitHub Pages if accessed over HTTP
  if (url.protocol === 'http:' && (url.hostname.endsWith('github.io') || (url.hostname.includes('.') && !/^(127\.|192\.168\.|10\.|172\.)/.test(url.hostname)))) {
    event.respondWith(Response.redirect('https:' + reqUrl.substring(5), 301));
    return;
  }

  // Do not intercept non-GET requests
  if (event.request.method !== 'GET') {
    return;
  }

  // Do not intercept WebSockets, API calls, or local private LAN IPs
  if (
    url.protocol.startsWith('ws') ||
    url.pathname.startsWith('/api/') ||
    url.hostname.startsWith('192.168.') ||
    url.hostname.startsWith('10.') ||
    url.hostname.startsWith('172.')
  ) {
    return;
  }

  // Network-First with Cache Fallback for HTML, CSS, JS when online
  event.respondWith(
    fetch(event.request)
      .then((networkResponse) => {
        if (networkResponse && networkResponse.status === 200 && (networkResponse.type === 'basic' || networkResponse.type === 'cors')) {
          const responseToCache = networkResponse.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(event.request, responseToCache));
        }
        return networkResponse;
      })
      .catch(() => {
        // Fallback to cache if network is offline
        return caches.match(event.request).then((cachedResponse) => {
          if (cachedResponse) return cachedResponse;
          if (event.request.mode === 'navigate') {
            return caches.match('./index.html');
          }
        });
      })
  );
});
