import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import cesium from 'vite-plugin-cesium'
import { fileURLToPath } from 'node:url'

// Клиент ходит только в API ядра (core/com): собственных расчётов нет,
// поэтому и собственных источников данных тоже нет. CesiumJS отвечает
// за отображение глобуса; траектории приходят CZML-потоком с сервера.
export default defineConfig({
  plugins: [react(), cesium()],
  resolve: {
    alias: {
      // Стили Ганта лежат в пакете под условием "style", и обычным импортом
      // карта exports их не отдаёт. Псевдоним — строка сборки, а не патч
      // библиотеки: файл берётся из пакета как есть (форк запрещён заданием).
      'frappe-gantt/css': fileURLToPath(
        new URL('./node_modules/frappe-gantt/dist/frappe-gantt.css', import.meta.url),
      ),
    },
  },
  build: {
    // Две точки входа: старый интерфейс по /, новый v2 — по /v2.html.
    // Strangler: новое рядом со старым, пока волны не заменят его целиком.
    rollupOptions: {
      input: {
        main: fileURLToPath(new URL('./index.html', import.meta.url)),
        v2: fileURLToPath(new URL('./v2.html', import.meta.url)),
      },
    },
  },
  server: {
    port: 5173,
    proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true } },
  },
})
