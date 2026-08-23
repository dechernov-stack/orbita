// Приёмка оболочки блока D в настоящем браузере: портфель → выбор проекта →
// жизненный цикл → готовность к точке → реестр требований с замером плотности
// (§3.6: не менее 30 строк на 1440×900 при 100% масштабе).
//
// Запуск: node check-shell.mjs (стенд на :8080, затравка data/pilot загружена;
// прежние check-screens/check-empty-project проверяли мастер Ш1–Ш7 и сняты
// вместе с ним — их предмет закрывает прогон миссии по §5 задания).
import { existsSync, mkdirSync } from 'node:fs'
import { chromium } from 'playwright'

const CONTAINER_CHROME = '/opt/pw-browsers/chromium-1194/chrome-linux/chrome'
const CHROME = process.env.ORBITA_CHROME ?? (existsSync(CONTAINER_CHROME) ? CONTAINER_CHROME : null)
const BASE = process.env.ORBITA_WEB_URL ?? 'http://127.0.0.1:8080/'
const SHOTS = process.env.ORBITA_SHOTS ?? null
if (SHOTS) mkdirSync(SHOTS, { recursive: true })

let ok = 0
let fail = 0
const expect = (cond, message, detail = '') => {
  if (cond) {
    ok += 1
    console.log(`  + ${message}`)
  } else {
    fail += 1
    console.log(`  - ${message}${detail ? ` — ${detail}` : ''}`)
  }
}

const browser = await chromium.launch(CHROME ? { executablePath: CHROME } : {})
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const shot = async (name) => {
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/${name}.png` })
}

console.log('Портфель')
await page.goto(BASE, { waitUntil: 'networkidle' })
await page.evaluate(() => localStorage.removeItem('orbita.project'))
await page.goto(BASE, { waitUntil: 'networkidle' })
await page.waitForSelector('.pcard, .toolbar', { timeout: 30000 })
const cards = await page.$$('.pcard')
expect(cards.length >= 1, `карточки проектов показаны (${cards.length})`)
const cardText = await page.$$eval('.pcard', (cs) => cs.map((c) => c.innerText).join('\n'))
expect(
  cardText.includes('Ближайшая точка') || cardText.includes('Все точки пройдены'),
  'карточка несёт ближайшую точку либо «Все точки пройдены»',
)
await shot('1-портфель')

console.log('Выбор проекта → жизненный цикл')
// затравка «Ротор-Л», если она загружена, иначе первый проект портфеля:
// плотность мерится на любом реестре от 30 строк
const pilot = await page.$$('.pcard')
let opened = false
for (const c of pilot) {
  if ((await c.innerText()).includes('Ротор-Л')) {
    await c.click()
    opened = true
    break
  }
}
if (!opened && pilot.length > 0) { await pilot[0].click(); opened = true }
expect(opened, 'проект выбран на портфеле')
await page.waitForSelector('.gatestrip .gatecard', { timeout: 30000 })
const gates = await page.$$eval('.gatestrip .gatecard', (gs) => gs.map((g) => g.innerText))
expect(gates.length >= 3, `лента точек проекта (${gates.length})`)
const opsRows = await page.$$('.opsmap__row')
expect(opsRows.length >= 15, `карта операций фазы: ${opsRows.length}`)
// шапка наполняется отдельным запросом; у завершённого проекта точки нет
const headerGate = await page.waitForSelector('.header__gate', { timeout: 15000 }).catch(() => null)
const allHeld = await page.$$eval('.gatestrip .gatecard--held', (g) => g.length) === 6
expect(headerGate != null || allHeld, 'в шапке — ближайшая точка (или все вехи пройдены)')
await shot('2-жизненный-цикл')

console.log('Готовность к точке')
const gateBtn = await page.$('.header__gate')
if (gateBtn) {
  await gateBtn.click()
  await page.waitForSelector('.issue, .card, .empty', { timeout: 30000 })
  const issues = await page.$$('.issue')
  const bodyText = await page.evaluate(() => document.body.innerText)
  expect(
    issues.length > 0 || bodyText.includes('готова к прохождению') || bodyText.includes('пройдены'),
    issues.length > 0
      ? `перечень незакрытого поимённо (${issues.length})`
      : 'готовность отвечает состоянием, а не молчит',
  )
  if (issues.length > 0) {
    const issueText = await page.$$eval('.issue', (xs) => xs.map((x) => x.innerText).join('\n'))
    expect(/О\d/.test(issueText), 'причины ведут на операции регламента')
  }
} else {
  await page.$$eval('.rail__item', (bs) => bs.find((b) => b.innerText.includes('Контроль'))?.click())
  await page.waitForSelector('.toolbar', { timeout: 30000 })
  expect(true, 'все вехи пройдены — шапка без ближайшей точки, готовность из раздела «Контроль»')
}
await shot('3-готовность')

console.log('Реестр требований: плотность §3.6')
await page.$$eval('.rail__item', (bs) => bs.find((b) => b.innerText.includes('Требования'))?.click())
await page.waitForSelector('tbody tr', { timeout: 30000 })
const density = await page.evaluate(() => {
  const rows = [...document.querySelectorAll('tbody tr')]
  const visible = rows.filter((r) => {
    const b = r.getBoundingClientRect()
    return b.top >= 0 && b.bottom <= window.innerHeight && b.height > 0
  })
  return {
    total: rows.length,
    visible: visible.length,
    rowH: rows[0] ? rows[0].getBoundingClientRect().height : null,
  }
})
expect(density.total >= 30, `материала в реестре хватает на замер (${density.total} строк)`)
expect(density.rowH === 26, `строка реестра 26 px (${density.rowH})`)
expect(
  density.visible >= 30,
  `видимых строк на 1440×900 — ${density.visible} (§3.6: не менее 30)`,
)
const overflowX = await page.evaluate(
  () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
)
expect(!overflowX, 'горизонтальной прокрутки страницы нет')
const statusText = await page.$$eval('tbody tr td:last-child', (ts) => ts.slice(0, 3).map((t) => t.innerText))
expect(
  statusText.every((s) => !/^(Draft|Preliminary|Approved|Baseline)$/.test(s.trim())),
  'статусы подписаны словами, а не кодами',
  statusText.join(' · '),
)
await shot('4-реестр-требований')

console.log('Реестр вида блока C: пустое состояние — приглашение')
await page.$$eval('.rail__item', (bs) => bs.find((b) => b.innerText.includes('Постановка'))?.click())
await page.waitForSelector('.toolbar', { timeout: 30000 })
await page.waitForTimeout(600)
const emptyText = (await page.$('.empty')) ? await page.$eval('.empty', (e) => e.innerText) : ''
expect(
  emptyText.includes('Создайте') || (await page.$$('tbody tr')).length > 0,
  'пустое состояние приглашает к действию, а не показывает прочерк',
  emptyText,
)
await shot('5-реестр-целей')

await browser.close()
console.log(`\nИтог: пройдено ${ok}, провалено ${fail}`)
process.exit(fail ? 1 : 0)
