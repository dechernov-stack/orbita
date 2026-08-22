// Снимки приёмки Шага 16 (§4): шесть экранов на ПУСТОЙ базе, наполненной
// целиком через интерфейс. Отчёт без снимков не принимается — именно на этом
// месте предыдущие шаги отчитывались зелёным о неработающем экране.
// Запуск: node step16-shots.mjs (стенд на :8080 уже наполнен приёмкой)
import { existsSync, mkdirSync } from 'node:fs'
import { chromium } from 'playwright'

const CONTAINER_CHROME = '/opt/pw-browsers/chromium-1194/chrome-linux/chrome'
const CHROME = process.env.ORBITA_CHROME ?? (existsSync(CONTAINER_CHROME) ? CONTAINER_CHROME : null)
const BASE = process.env.ORBITA_WEB_URL ?? 'http://127.0.0.1:8080/'
const OUT = '../docs/tz/step16-shots'
mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch(CHROME ? { executablePath: CHROME } : {})
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.goto(BASE)
await page.fill('input[placeholder="представьтесь"]', 'Чернов Д.')

const step = async (n) => {
  await page.click(`.wizard button:has-text("Ш${n}")`)
  await page.waitForTimeout(400)
}
const tab = async (title) => {
  await page.click(`button:has-text("${title}")`)
  await page.waitForTimeout(600)
}
const shot = async (name) => {
  await page.screenshot({ path: `${OUT}/${name}.png` })
  console.log(name)
}

// 1. Дерево требований: подчинённость, включая связь, добавленную правкой
await step(3)
await page.waitForTimeout(600)
await shot('1-дерево-требований')

// 2. Матрица верификации: разрывы и непокрытые — отдельными списками
await tab('Верификация')
await shot('2-матрица-верификации')

// 3. Состав системы: спецификация введённого компонента
await step(4)
await tab('Состав системы')
await shot('3-состав-системы')

// 4. Карта покрытия: ячейки, среднее и провалы
await step(5)
await tab('Карта покрытия')
await page.waitForTimeout(12000) // Orekit считает сутки
await shot('4-карта-покрытия')

// 5. Глобус: трассы своей группировки, станции, зоны; расписание синхронно
await tab('Баллистика')
await page.waitForTimeout(12000)
await shot('5-глобус-и-расписание')

// 6. Готовность к точке: отчёт зрелости перечисляет реальные пробелы
await step(7)
await page.waitForTimeout(1000)
await shot('6-зрелость-к-точке')

await browser.close()
