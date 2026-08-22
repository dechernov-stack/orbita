// Снимки приёмки Шага 17 (блок C) на пустой базе, наполненной через интерфейс.
// Запуск: node step17-shots.mjs (стенд на :8080 уже наполнен приёмкой)
import { existsSync, mkdirSync } from 'node:fs'
import { chromium } from 'playwright'

const CONTAINER_CHROME = '/opt/pw-browsers/chromium-1194/chrome-linux/chrome'
const CHROME = process.env.ORBITA_CHROME ?? (existsSync(CONTAINER_CHROME) ? CONTAINER_CHROME : null)
const BASE = process.env.ORBITA_WEB_URL ?? 'http://127.0.0.1:8080/'
const OUT = '../docs/tz/step17-shots'
mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch(CHROME ? { executablePath: CHROME } : {})
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.goto(BASE)
await page.fill('input[placeholder="представьтесь"]', 'Чернов Д.')

const step = async (n) => { await page.click(`.wizard button:has-text("Ш${n}")`); await page.waitForTimeout(400) }
const tab = async (t) => { await page.click(`button:has-text("${t}")`); await page.waitForTimeout(700) }
const shot = async (name) => { await page.screenshot({ path: `${OUT}/${name}.png` }); console.log(name) }

// 1. ConOps: сценарий, растущий из нужды
await step(1)
await tab('ConOps')
await page.click('tbody tr td')
await page.waitForTimeout(500)
await shot('1-conops-сценарий')

// 2. Технологии: TRL ниже требуемого
await step(4)
await tab('Технологии и TRL')
await page.waitForTimeout(400)
await shot('2-технологии-trl')

// 3. Решение по альтернативам
await step(5)
await tab('Решения')
await page.click('tbody tr td')
await page.waitForTimeout(500)
await shot('3-решение')

// 4. Готовность: точки из проекта, TRL блокирует зрелость
await step(7)
await page.waitForTimeout(900)
await shot('4-готовность-точки-проекта')

// 5. Документы: выпуск устарел — модель ушла вперёд
await tab('Документы')
await tab('Концепция применения')
await page.waitForTimeout(800)
await shot('5-документ-выпуск-устарел')

await browser.close()
