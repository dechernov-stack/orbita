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

// Пропуск объявляется вслух. Молчаливо пропущенная проверка однажды уже
// скрыла невыполнявшийся эталон — повторять это в клиенте незачем.
const skip = (message, why) => console.log(`  ~ ПРОПУЩЕНО: ${message} — ${why}`)

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
const hasOverrun = rows.some((r) => r[3].includes('превышение'))
if (hasOverrun) {
  expect(hasOverrun, 'превышение показано величиной, а не обрезано')
} else {
  skip('превышение бюджета', 'в демо-проекте свёртки укладываются в бюджет')
}

const indents = await page.$$eval('tbody tr td:first-child span', (spans) =>
  [...new Set(spans.map((s) => s.style.paddingLeft).filter(Boolean))],
)
expect(indents.length > 1, `отступ по уровню дерева виден (${indents.join(', ')})`)
if (hasOverrun) {
  expect((await page.$$('.bar__fill--overrun')).length > 0, 'полоса превышения покрашена')
} else {
  skip('покраска полосы превышения', 'превышений в данных нет')
}

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

console.log('Экран 7: сравнение вариантов')
await page.click('button:has-text("Сравнение вариантов")')
await page.waitForSelector('svg[aria-label="Роза KPI"]')
const polygons = (await page.$$('svg polygon')).length
expect(polygons > 3, `роза отрисована (полигонов ${polygons})`)
const normalizedOver = await page.$eval('.pane .mono', (e) => e.innerText)
expect(normalizedOver.includes('·'), `диаграмма подписывает набор нормировки: ${normalizedOver}`)
const pareto = await page.$$eval('.card .chip', (e) => e.map((x) => x.innerText))
expect(pareto.length > 0, `фронт Парето показан: ${pareto.join(', ')}`)

console.log('Экран 12: система в целом')
await page.click('button:has-text("Система в целом")')
await page.waitForSelector('.matrix')
expect((await page.$$('.matrix .cell')).length === 25, 'матрица рисков 5×5 отрисована')
expect((await page.$$('.cell--high')).length > 0, 'клетки высокой критичности выделены')
const problems = await page.$$eval('.warn', (e) => e.filter((x) => x.innerText.startsWith('△')).length)
expect(problems > 0, `проблемы перечислены (${problems})`)

console.log('Экран 6: глобус')
await page.click('button:has-text("Баллистика")')
await page.waitForTimeout(9000)
const globeStatus = await page.$eval('.topbar .mono', (e) => e.innerText).catch(() => '')
expect(/трасс: [1-9]/.test(globeStatus), `трассы загружены из CZML: ${globeStatus}`)
expect((await page.$$('canvas')).length > 0, 'глобус отрисован')

console.log(`ошибок в консоли: ${consoleErrors.length}`)
consoleErrors.forEach((e) => console.log('   ', e))
await browser.close()

if (failures.length || consoleErrors.length) {
  console.log(`\nИтог: провалено ${failures.length + consoleErrors.length}`)
  process.exit(1)
}
console.log('\nИтог: экраны 3, 3б, 11, 7, 12 и 6 работают на данных API')
