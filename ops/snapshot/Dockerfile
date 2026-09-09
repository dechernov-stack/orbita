# Служба снимка и печати: Playwright (Apache-2.0) со своим Chromium.
# Один образ на снимок ссылки (/content) и печать HTML → PDF (/pdf) — как в
# DocPilot. Версия Playwright закреплена: образ и npm-пакет одной версии.
FROM mcr.microsoft.com/playwright:v1.63.0-noble
WORKDIR /srv
COPY package.json server.js ./
RUN npm install --omit=dev --no-audit --no-fund
ENV PORT=3000
EXPOSE 3000
USER pwuser
CMD ["node", "server.js"]
