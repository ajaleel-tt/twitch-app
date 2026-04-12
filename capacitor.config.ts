import { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.twitchnotify.app',
  appName: 'Twitch Category Tracker',
  webDir: 'www',
  server: {
    cleartext: true
  }
};

export default config;
