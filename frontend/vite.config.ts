import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// Dev server proxies backend calls so the browser talks to one origin (no CORS setup needed locally).
// Override with GCARE_BACKEND_URL when the backend runs on another port (SERVER_PORT).
const backend = process.env.GCARE_BACKEND_URL ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': backend,
      '/actuator': backend,
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
})
