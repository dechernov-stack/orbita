// Проверка экранов на РЕАЛЬНЫХ данных API (STEP-6, определение готовности).
//
// Требует запущенных: демонстрационного API и dev-сервера клиента —
//   ./gradlew :core:com:demoServer     (порт 8080)
//   npm run dev                        (порт 5173, проксирует /api)
// Запуск: npm run check:screens
//
// Проверяется то, что нельзя увидеть по одному лишь ответу API: строки
// действительно отрисованы, отступ дерева виден, превышение бюджета покрашено,
// карточка требования открывается, ошибок в консоли нет.
import { chromium } from 'playwright'

const CHROME = process.env.ORBITA_CHROME ?? '/opt/pw-browsers/chromium-1194/chrome-linux/chrome'
const BASE = process.env.ORBITA_WEB_URL ?? 'http://127.0.0.1:5173/'

const failures = []
const expect = (condition, message) => {
  if (condition) console.log(`  + ${message}`)
  else {
    failures.push(message)
    console.log(`  - ${message}`)
  }
}

const browser = await chromium.launch({ executablePath: CHROME })
const page = await browser.newPage({ viewport: { width: 1440, height: 800 } })
const consoleErrors = []
page.on('pageerror', (e) => consoleErrors.push(String(e)))
page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text()))

await page.goto(BASE, { waitUntil: 'networkidle' })
await page.waitForSelector('tbody tr')

console.log('Экран 3: дерево требований')
const rows = await page.$$eval('tbody tr', (trs) =>
  trs.map((tr) => [...tr.querySelectorAll('td')].map((td) => td.innerText.trim())),
)
expect(rows.length >= 4, `строки отрисованы (${rows.length})`)
expect(rows.some((r) => r[2].includes('кг')), 'единица подписана локализованно')
expect(rows.some((r) => r[3].includes('остаток')), 'свёртка показывает остаток')
expect(rows.some((r) => r[3].includes('превышение')), 'превышение показано величиной, а не обрезано')

const indents = await page.$$eval('tbody tr td:first-child span', (spans) =>
  [...new Set(spans.map((s) => s.style.paddingLeft).filter(Boolean))],
)
expect(indents.length > 1, `отступ по уровню дерева виден (${indents.join(', ')})`)
expect((await page.$$('.bar__fill--overrun')).length > 0, 'полоса превышения покрашена')

console.log('Экран 3б: карточка требования')
await page.click('tbody tr:first-child')
await page.waitForSelector('.card')
const cards = await page.$$eval('.card h3', (h) => h.map((x) => x.innerText))
expect(cards.includes('Условие'), 'карточка показывает условие')
expect(cards.includes('Верификация'), 'карточка показывает верификацию')
const criterion = await page.$eval('.card .field span.mono', (e) => e.innerText)
expect(criterion.trim().length > 0, `критерий успеха выведен сервером: «${criterion}»`)

console.log('Экран 11: спецификация элемента')
await page.click('button:has-text("Спецификация элемента")')
await page.waitForSelector('tbody tr')
const spec = await page.$$eval('tbody tr', (trs) =>
  trs.map((tr) => [...tr.querySelectorAll('td')].map((td) => td.innerText.trim())),
)
expect(spec.length > 0, `требования элемента отрисованы (${spec.length})`)
expect(spec.every((r) => r[3].length > 0), 'источник требования показан')
expect(spec.every((r) => /\d+ из \d+/.test(r[4])), 'прогресс событий верификации показан')

console.log(`ошибок в консоли: ${consoleErrors.length}`)
consoleErrors.forEach((e) => console.log('   ', e))
await browser.close()

if (failures.length || consoleErrors.length) {
  console.log(`\nИтог: провалено ${failures.length + consoleErrors.length}`)
  process.exit(1)
}
console.log('\nИтог: экраны 3, 3б и 11 работают на данных API')
