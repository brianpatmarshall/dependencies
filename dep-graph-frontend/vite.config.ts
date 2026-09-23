import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Every API call is same-origin '/api', so there is no CORS configuration anywhere in
// this project: vite proxies in dev, nginx proxies in Docker.
//
// The 9094 fallback is load-bearing. It has to match BACKEND_PORT in docker-compose.yml
// and SPRINGDOC_SERVER_URL, or `npm run dev` stops reaching the stack while everything
// else keeps working. Set VITE_API_TARGET to differ deliberately.
const target = process.env.VITE_API_TARGET ?? 'http://localhost:9094'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3005,
    proxy: {
      '/api': {
        target,
        changeOrigin: true,
        // A scan streams for minutes. Without this the dev proxy gives up partway
        // through and the progress log just stops with no error.
        timeout: 30 * 60 * 1000,
      },
    },
  },
})
