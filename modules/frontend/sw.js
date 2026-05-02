const CACHE_VERSION = 'v1';
const CACHE_NAME = `twitch-tracker-${CACHE_VERSION}`;

const PRECACHE_URLS = [
  '/',
  '/dist/output.css',
  '/manifest.json',
  '/icons/icon.svg'
];

// Install: pre-cache the app shell
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => cache.addAll(PRECACHE_URLS))
      .then(() => self.skipWaiting())
  );
});

// Activate: clean up old caches
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(
        keys
          .filter((key) => key.startsWith('twitch-tracker-') && key !== CACHE_NAME)
          .map((key) => caches.delete(key))
      ))
      .then(() => self.clients.claim())
  );
});

// Push: handle FCM push notifications (web PWA fallback)
self.addEventListener('push', (event) => {
  if (!event.data) return;

  let payload;
  try {
    payload = event.data.json();
  } catch (e) {
    payload = { notification: { title: 'Twitch Category Tracker', body: event.data.text() } };
  }

  const data = payload.data || payload.notification || {};

  const title = data.title || 'Stream is live!';
  const options = {
    body: data.body || '',
    icon: '/icons/icon.svg',
    badge: '/icons/icon.svg',
    data: data,
    tag: data.streamerId || 'twitch-notification',
    renotify: true
  };

  event.waitUntil(self.registration.showNotification(title, options));
});

// Notification click: open the stream in the Twitch app via deep link
self.addEventListener('notificationclick', (event) => {
  event.notification.close();

  const data = event.notification.data || {};
  const streamer = data.streamerLogin;

  if (!streamer) {
    event.waitUntil(clients.openWindow('/'));
    return;
  }

  // Use Twitch deep link to open the native app directly
  const deepLink = `twitch://stream/${streamer}`;
  const fallbackUrl = `https://twitch.tv/${streamer}`;

  event.waitUntil(
    clients.openWindow(deepLink).catch(() => clients.openWindow(fallbackUrl))
  );
});

// Fetch: route requests to cache or network
self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  if (event.request.method !== 'GET') {
    return;
  }

  // Private routes must never enter CacheStorage.
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/auth/')) {
    event.respondWith(fetch(event.request));
    return;
  }

  // Cache-first for static assets (CSS, JS, icons, HTML)
  event.respondWith(
    caches.match(event.request)
      .then((cached) => {
        if (cached) {
          return cached;
        }
        return fetch(event.request).then((response) => {
          if (response.ok) {
            const clone = response.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(event.request, clone));
          }
          return response;
        });
      })
  );
});
