import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const { VITE_BACKEND_URL } = loadEnv(mode, '.', 'VITE_')
  const backendTarget = VITE_BACKEND_URL || 'http://127.0.0.1:8081'

  return {
    plugins: [react()],
    server: {
      proxy: {
        '/api': {
          target: backendTarget,
          changeOrigin: true,
        },
      },
    },
  }
})
