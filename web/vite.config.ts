import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import cesium from 'vite-plugin-cesium'

// Клиент ходит только в API ядра (core/com): собственных расчётов нет,
// поэтому и собственных источников данных тоже нет. CesiumJS отвечает
// за отображение глобуса; траектории приходят CZML-потоком с сервера.
export default defineConfig({
  plugins: [react(), cesium()],
  server: {
    port: 5173,
    proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true } },
  },
})
