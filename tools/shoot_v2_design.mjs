// Снимки приёмки дизайна v2 (ДИЗАЙН-ПРИНЦИПЫ-V2 §«Приёмка дизайна»).
//
// Три учётки — три плотности: инженер видит одно мероприятие, ведущий СИ
// добавляет схему сцены сверху, руководитель начинает с карты фазы. Снимки
// делаются на СТЕНДЕ и одинаковыми шагами, чтобы обход можно было повторить,
// а не пересказать.
//
//   node tools/shoot_v2_design.mjs [http://localhost:8080] [PJ-V2-01]
import { chromium } from '../web/node_modules/playwright/index.mjs'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const БАЗА = process.argv[2] ?? 'http://localhost:8080'
const ПРОЕКТ = process.argv[3] ?? 'PJ-V2-01'
const КУДА = new URL('../docs/tz/v2/снимки-дизайн/', import.meta.url)

/** Сцена приёмки — та, что в эталоне: 3 · стейкхолдеры, мероприятие 0.1. */
const СЦЕНА = 'Стейкхолдеры и их нужды'

const УЧЁТКИ = [
  { login: 'petrova', имя: 'инженер', файл: '1-инженер-мероприятие' },
  { login: 'ivanov', имя: 'ведущий СИ', файл: '2-ведущий-СИ-сцена' },
  { login: 'chernov', имя: 'руководитель', файл: '3-руководитель-карта-фазы' },
]

await mkdir(fileURLToPath(КУДА), { recursive: true })
const браузер = await chromium.launch()
const контекст = await браузер.newContext({ viewport: { width: 1440, height: 900 } })
const стр = await контекст.newPage()

for (const у of УЧЁТКИ) {
  await стр.goto(`${БАЗА}/v2.html`)
  await стр.evaluate(async (login) => {
    await fetch('/api/auth/stand-login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ login }),
    })
  }, у.login)
  await стр.evaluate((p) => localStorage.setItem('orbita.v2.project', p), ПРОЕКТ)
  await стр.goto(`${БАЗА}/v2.html`)
  await стр.waitForSelector('.v2-top', { timeout: 15000 })
  await стр.waitForTimeout(900)

  // Руководителю карта фазы и есть стартовый экран — на неё ничего не жмут.
  // Остальным нужна сцена приёмки: она выбирается тем же путём, что у
  // человека, — картой фазы, а не подкруткой состояния.
  if (у.login !== 'chernov') {
    const вид = стр.locator('.v2-density')
    await вид.selectOption('фаза')
    await стр.waitForTimeout(600)
    await стр.getByText(СЦЕНА, { exact: true }).first().click()
    await стр.waitForTimeout(600)
    if (у.login === 'petrova') {
      await вид.selectOption('мероприятие')
      await стр.waitForTimeout(500)
    }
  }
  const путь = fileURLToPath(new URL(`${у.файл}.png`, КУДА))
  await стр.screenshot({ path: путь, fullPage: false })
  console.log(`  снимок: ${у.файл}.png — ${у.имя}`)
}

await браузер.close()
console.log(`снимков: ${УЧЁТКИ.length} → docs/tz/v2/снимки-дизайн`)
