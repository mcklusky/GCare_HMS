import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// Dev server proxies backend calls so the browser talks to one origin, as in production behind one
// host name (no CORS; the session cookie and CSRF cookie are first-party). The Host header is kept
// (no changeOrigin) so the backend builds the Keycloak callback URL on this origin.
// Override with GCARE_BACKEND_URL when the backend runs on another port (SERVER_PORT).
const backend = { target: process.env.GCARE_BACKEND_URL ?? 'http://localhost:8080', changeOrigin: false }

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': backend,
      '/actuator': backend,
      '/oauth2': backend, // sign-in start
      '/login/oauth2': backend, // Keycloak callback
      '/v3/api-docs': backend,
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
})
