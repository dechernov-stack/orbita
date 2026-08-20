# Образ клиента: сборка Vite в статику, раздача nginx.
#
# Клиент ходит только в API ядра (ADR-010): собственных источников данных
# у него нет, поэтому и настраивать в образе нечего — адрес API задаёт
# проксирование nginx, а не переменная сборки.

FROM node:22-slim AS build
WORKDIR /src

COPY web/package.json web/package-lock.json ./
RUN npm ci

COPY web .
# Сборка включает tsc --noEmit: расхождение типов клиента с формами ответов
# API останавливает сборку образа, а не всплывает в браузере.
RUN npm run build

# --------------------------------------------------------------------------
FROM nginx:1.27-alpine AS web
COPY --from=build /src/dist /usr/share/nginx/html
COPY ops/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80

HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=5 \
  CMD wget -qO- http://127.0.0.1/ > /dev/null || exit 1
