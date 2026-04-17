import { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.twitchnotify.app',
  appName: 'Twitch Category Tracker',
  webDir: 'www',
  server: {
    // On native, load the app from the production server so relative
    // API paths (/api/user, /api/config, etc.) resolve correctly.
    // Capacitor native plugins (push notifications) still work because
    // they run in the native layer, not the WebView.
    url: 'https://twitch-app-grn6.onrender.com',
    cleartext: true,
    // Keep Twitch OAuth flow inside the WebView instead of opening Chrome
    allowNavigation: ['id.twitch.tv']
  }
};

export default config;
