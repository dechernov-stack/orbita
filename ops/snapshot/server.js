// Служба снимка и печати (шип E п. 4, ответ владельца 09.09): Playwright
// со своим Chromium. Два входа, оба JSON:
//   POST /content {url}                 → {html, title, date} после исполнения JS
//   POST /pdf     {html} | {url}        → application/pdf
//   GET  /health                        → {ok, playwright}
// Страница, у которой после рендера нет текста, отдаётся как есть: решение
// «пусто — отказать и просить PDF» принимает api, а не служба.
'use strict';
const http = require('http');
const { chromium } = require('playwright');

const PORT = Number(process.env.PORT || 3000);
const TIMEOUT = Number(process.env.TIMEOUT || 60000);
let browser = null;

async function getBrowser() {
  if (!browser || !browser.isConnected()) browser = await chromium.launch({ args: ['--no-sandbox'] });
  return browser;
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', (c) => { body += c; if (body.length > 5_000_000) reject(new Error('body too large')); });
    req.on('end', () => { try { resolve(body ? JSON.parse(body) : {}); } catch (e) { reject(e); } });
    req.on('error', reject);
  });
}

async function withPage(fn) {
  const b = await getBrowser();
  const ctx = await b.newContext({ userAgent: 'Orbita/2 snapshot (Playwright)', locale: 'ru-RU' });
  const page = await ctx.newPage();
  page.setDefaultTimeout(TIMEOUT);
  try { return await fn(page); } finally { await ctx.close(); }
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/health') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ ok: true, playwright: require('playwright/package.json').version }));
    }
    if (req.method === 'POST' && req.url === '/content') {
      const { url } = await readJson(req);
      if (!url || !/^https?:\/\//.test(url)) throw new Error('нужен url http(s)');
      const out = await withPage(async (page) => {
        await page.goto(url, { waitUntil: 'networkidle' });
        return { html: await page.content(), title: await page.title(), date: new Date().toISOString().slice(0, 10) };
      });
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify(out));
    }
    if (req.method === 'POST' && req.url === '/pdf') {
      const { html, url } = await readJson(req);
      if (!html && !url) throw new Error('нужен html либо url');
      const pdf = await withPage(async (page) => {
        if (url) await page.goto(url, { waitUntil: 'networkidle' }); else await page.setContent(html, { waitUntil: 'load' });
        return page.pdf({ format: 'A4', printBackground: true, margin: { top: '20mm', bottom: '20mm', left: '20mm', right: '15mm' } });
      });
      res.writeHead(200, { 'Content-Type': 'application/pdf' });
      return res.end(pdf);
    }
    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'нет такого входа: POST /content, POST /pdf, GET /health' }));
  } catch (e) {
    res.writeHead(500, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: String(e && e.message || e) }));
  }
});

server.listen(PORT, () => console.log(`orbita-snapshot: playwright chromium on :${PORT}`));
