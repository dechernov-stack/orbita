// Сквозной проход на ПОЛНОМ масштабе (шаг 13.2): экраны не деградируют
// на реальных объёмах — дерево на сотнях требований, карта спроса на тысячах
// ячеек, роза на десятке вариантов.
//
// Требует НАГРУЗОЧНОГО сервера и dev-сервера клиента:
//   ./gradlew :core:com:loadServer      (порт 8080; демо + 200 требований + 10 вариантов)
//   npm run dev                         (порт 5173)
// Запуск: node check-scale.mjs
//
// Замеряется и ВРЕМЯ: экран, который строится десять секунд, формально работает,
// а практически не пригоден. Пороги грубые — ловится деградация на порядок,
// а не дрожание миллисекунд.
import { chromium } from 'playwright'

const CHROME = process.env.ORBITA_CHROME ?? '/opt/pw-browsers/chromium-1194/chrome-linux/chrome'
const BASE = process.env.ORBITA_WEB_URL ?? 'http://127.0.0.1:5173/'
const RENDER_BUDGET_MS = 10_000

const failures = []
const expect = (condition, message, detail = '') => {
  if (condition) console.log(`  + ${message}`)
  else {
    failures.push(message)
    console.log(`  - ${message}${detail ? ` — получено: ${detail}` : ''}`)
  }
}

const browser = await chromium.launch({ executablePath: CHROME })
const page = await browser.newPage({ viewport: { width: 1440, height: 800 } })
const consoleErrors = []
page.on('pageerror', (e) => consoleErrors.push(String(e)))
page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text()))

const open = async (step, screen) => {
  const t0 = Date.now()
  await page.click(`.wizard__step:has-text("Ш${step}.")`)
  await page.click(`.topbar button:has-text("${screen}")`)
  return t0
}

/** Ожидание, пока на экране не появится [selector], и замер времени от клика. */
const renderedIn = async (t0, selector, minCount = 1) => {
  await page.waitForFunction(
    ([sel, n]) => document.querySelectorAll(sel).length >= n,
    [selector, minCount],
    { timeout: RENDER_BUDGET_MS },
  )
  return Date.now() - t0
}

await page.goto(BASE, { waitUntil: 'networkidle' })
await page.waitForSelector('.wizard__step')
await page.waitForFunction(
  () => ![...document.querySelectorAll('.wizard__step small')].some((s) => s.innerText.includes('загрузка')),
  { timeout: 30000 },
)

console.log('Мастер на нагрузочном проекте')
const steps = await page.$$eval('.wizard__step', (bs) => bs.map((b) => b.innerText.replace('\n', ' · ')))
steps.forEach((s) => console.log(`     ${s}`))
expect(steps.length === 7, 'шагов Ш1–Ш7 показано 7')
expect(
  steps.some((s) => s.includes('209')),
  'счётчик Ш3 показывает все 209 требований',
  steps.find((s) => s.includes('Требования')),
)

console.log('Ш3 · дерево на сотнях требований')
{
  const t0 = await open(3, 'Дерево требований')
  const ms = await renderedIn(t0, 'tr, [class*="tree"] [class*="row"]', 100)
  const rows = await page.$$eval('tr', (r) => r.length)
  expect(rows >= 200, `строк дерева отрисовано ${rows} (≥200)`, String(rows))
  expect(ms < RENDER_BUDGET_MS, `дерево построено за ${ms} мс (бюджет ${RENDER_BUDGET_MS})`)
  // раскрытие карточки не деградирует: клик по нагрузочному требованию
  await page.click('text=RQ-9100')
  await page.waitForSelector('text=Нагрузочное требование', { timeout: RENDER_BUDGET_MS })
  console.log('  + карточка нагрузочного требования открывается')
}

console.log('Ш2 · карта спроса на тысячах ячеек')
{
  const t0 = await open(2, 'Карта спроса')
  const ms = await renderedIn(t0, 'svg circle, svg rect, canvas', 100)
  expect(ms < RENDER_BUDGET_MS, `карта построена за ${ms} мс (бюджет ${RENDER_BUDGET_MS})`)
  const cellsText = await page.textContent('body')
  expect(/1[0-9]{3}|[2-9][0-9]{3}/.test(cellsText), 'на экране счёт ячеек в тысячах')
}

console.log('Ш5 · роза на десятке вариантов')
{
  const t0 = await open(5, 'Сравнение вариантов')
  const ms = await renderedIn(t0, 'svg polygon', 5)
  const polygons = await page.$$eval('svg polygon', (p) => p.length)
  expect(polygons >= 13, `полигонов розы ${polygons} (13 вариантов + сетка)`, String(polygons))
  expect(ms < RENDER_BUDGET_MS, `роза построена за ${ms} мс (бюджет ${RENDER_BUDGET_MS})`)
  const body = await page.textContent('body')
  expect(body.includes('Вариант Н-10'), 'нагрузочные варианты дошли до экрана')
}

console.log('Ш6 · матрица верификации на сотнях событий')
{
  const t0 = await open(6, 'Система в целом')
  const ms = await renderedIn(t0, 'table, [class*="matrix"]', 1)
  expect(ms < RENDER_BUDGET_MS, `экран построен за ${ms} мс (бюджет ${RENDER_BUDGET_MS})`)
}

expect(consoleErrors.length === 0, `ошибок в консоли: ${consoleErrors.length}`, consoleErrors[0])

await browser.close()
if (failures.length) {
  console.log(`\nПроход на полном масштабе НЕ пройден: ${failures.length}`)
  process.exit(1)
}
console.log('\nИтог: экраны выдерживают полный масштаб')
