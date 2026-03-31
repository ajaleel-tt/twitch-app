/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    "./modules/frontend/src/**/*.scala",
    "./modules/frontend/index.html"
  ],
  theme: {
    extend: {
      colors: {
        'twitch-purple': '#9146ff',
        'twitch-purple-dark': '#772ce8',
        'twitch-purple-light': '#f0e6ff',
        'twitch-dark': '#0e0e10',
        'twitch-dark-card': '#1f1f23',
        'twitch-dark-hover': '#2d2d35',
        'twitch-danger': '#ff4646',
        'twitch-live': '#eb0400',
      },
    },
  },
  plugins: [],
}
