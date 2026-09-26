// Снимки экранов по плану (шип 5 §0): каждый раздел рейки × роль × ширина.
// Запускается из tools/snap_screens.py; план приходит JSON-файлом.
//
// Браузер — установленный Google Chrome (`channel: 'chrome'`): кэша браузеров
// Playwright на машине нет, а Chrome есть. Вход — учёткой стенда владельца
// системы и «выступить от имени роли»: плотность — умолчание роли, как у людей.
import { chromium } from '../../web/node_modules/playwright/index.mjs'
import { readFile, mkdir } from 'node:fs/promises'

const план = JSON.parse(await readFile(process.argv[2], 'utf-8'))
await mkdir(план.out, { recursive: true })

const браузер = await chromium.launch({ channel: план.channel ?? 'chrome' })
const итог = []

async function войти(стр, роль) {
  await стр.goto(`${план.base}${план.entry ?? '/v2.html'}`)
  await стр.evaluate(async ({ login, role, project }) => {
    await fetch('/api/auth/stand-login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ login }) })
    await fetch('/api/auth/act-as', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ role }) })
    localStorage.setItem('orbita.v2.project', project)
  }, { login: план.login, role: роль, project: план.project })
  await стр.goto(`${план.base}${план.entry ?? '/v2.html'}`)
  await стр.waitForSelector('nav.v2-rail', { timeout: 30000 })
  await стр.waitForTimeout(1200)
}

async function эксперт(стр, вкл) {
  await стр.click('button.v2-avatar')
  const флажок = стр.locator('.v2-menu label:has-text("эксперт-режим") input[type=checkbox]')
  if ((await флажок.isChecked()) !== вкл) await флажок.click()
  await стр.click('button.v2-avatar')
  await стр.waitForTimeout(300)
}

async function открыть(стр, заголовок) {
  const пункт = стр.locator('nav.v2-rail button.v2-rail__item', { hasText: заголовок }).first()
  if (!(await пункт.count())) return false
  if (await пункт.isDisabled()) return false
  await пункт.click()
  // Экран дочитывает данные: ждём тишины сети, но не дольше предела — у
  // некоторых экранов фоновый опрос, и «тишины» может не быть вовсе.
  await стр.waitForLoadState('networkidle', { timeout: план.settleMs ?? 8000 }).catch(() => {})
  await стр.waitForTimeout(план.pauseMs ?? 900)
  return true
}

for (const ширина of план.widths) {
  const высота = ширина >= 1440 ? 900 : 800
  for (const роль of план.roles) {
    const контекст = await браузер.newContext({ viewport: { width: ширина, height: высота }, locale: 'ru-RU' })
    const стр = await контекст.newPage()
    await войти(стр, роль.code)
    const разделы = роль.expert ? план.expertSections : план.sections
    if (роль.expert) await эксперт(стр, true)
    for (const раздел of разделы) {
      const имя = `${раздел.file}-${роль.file}-${ширина}.png`
      const есть = await открыть(стр, раздел.title)
      if (!есть) { итог.push({ file: имя, section: раздел.title, role: роль.file, width: ширина, skipped: 'раздела нет в рейке у этой роли' }); continue }
      await стр.screenshot({ path: `${план.out}/${имя}`, fullPage: false })
      итог.push({ file: имя, section: раздел.title, role: роль.file, width: ширина })
      console.log(`  ${имя}`)
    }
    await контекст.close()
  }
}
await браузер.close()
console.log(JSON.stringify({ shots: итог }))
