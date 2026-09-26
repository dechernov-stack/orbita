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

/** Строка для RegExp как есть: «Моя работа» не должна совпасть с «Работой». */
const буквально = (т) => т.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

async function открыть(стр, заголовок) {
  // Точное совпадение слова пункта: подстрока «Работа» есть и в «Моя работа»,
  // и снимки «до» 26.09 сняли под именем «работа» чужой экран.
  const пункт = стр.locator('nav.v2-rail button.v2-rail__item').filter({ hasText: new RegExp(`^\\s*${буквально(заголовок)}\\s*$`) }).first()
  if (!(await пункт.count())) return false
  if (await пункт.isDisabled()) return false
  await пункт.click()
  // Экран дочитывает данные: ждём тишины сети, но не дольше предела — у
  // некоторых экранов фоновый опрос, и «тишины» может не быть вовсе.
  await стр.waitForLoadState('networkidle', { timeout: план.settleMs ?? 8000 }).catch(() => {})
  await стр.waitForTimeout(план.pauseMs ?? 900)
  return true
}

/** Имя вкладки для файла: слово вкладки строчными, пробелы — дефисом. */
const словоФайла = (т) => (т ?? '').trim().toLowerCase().replace(/\s+/g, '-')

/**
 * Вкладки раздела (шип 5): каждая, кроме текущей, — своим снимком
 * «<раздел>~<вкладка>-<роль>-<ширина>.png»; потом возврат к первой, чтобы
 * выбор, который помнит браузер, не сбил следующий прогон.
 */
async function вкладки(стр, раздел, роль, ширина) {
  const кнопки = стр.locator('main .v2-tabs[role=tablist]').first().locator('button[role=tab]:not([disabled])')
  const n = await кнопки.count()
  for (let i = 1; i < n; i += 1) {
    const к = кнопки.nth(i)
    const имя = `${раздел.file}~${словоФайла(await к.locator('b').first().textContent())}-${роль.file}-${ширина}.png`
    await к.click()
    await стр.waitForLoadState('networkidle', { timeout: 4000 }).catch(() => {})
    await стр.waitForTimeout(план.pauseMs ?? 900)
    await стр.screenshot({ path: `${план.out}/${имя}` })
    итог.push({ file: имя, section: раздел.title, role: роль.file, width: ширина })
    console.log(`  ${имя}`)
  }
  if (n > 1) { await кнопки.nth(0).click(); await стр.waitForTimeout(500) }
}

/**
 * Сцены работы (шип 5 §2, §6): тело сцены N — своим снимком
 * «работа~сцена-N-<роль>-<ширина>.png»; сцену выбирает лента фазы.
 */
async function сцены(стр, раздел, роль, ширина) {
  for (const н of план.scenes ?? []) {
    const кнопка = стр.locator(`main button.v2-phaseband__scene:has(svg[aria-label^="сцена: ${н} ·"]), main button.v2-band__scene:has(svg[aria-label^="сцена: ${н} ·"])`).first()
    if (!(await кнопка.count())) continue
    await кнопка.click()
    await стр.waitForLoadState('networkidle', { timeout: 6000 }).catch(() => {})
    await стр.waitForTimeout(план.pauseMs ?? 900)
    const имя = `${раздел.file}~сцена-${н}-${роль.file}-${ширина}.png`
    await стр.screenshot({ path: `${план.out}/${имя}` })
    итог.push({ file: имя, section: раздел.title, role: роль.file, width: ширина })
    console.log(`  ${имя}`)
  }
}

/**
 * Открыть что-то кнопкой раздела (шип 5 §5: документ из комплекта) и снять
 * «<раздел>~открыто-…»: кнопка ищется по её имени для чтения экрана.
 */
async function открытьКнопкой(стр, раздел, роль, ширина, имя) {
  // Имя для чтения экрана точно; нет такого — видимый текст кнопки (строка точки).
  let кнопка = стр.locator(`main button[aria-label="${имя}"]`).first()
  if (!(await кнопка.count())) кнопка = стр.locator('main').getByRole('button', { name: имя }).first()
  if (!(await кнопка.count())) return
  await кнопка.click()
  await стр.waitForLoadState('networkidle', { timeout: 6000 }).catch(() => {})
  await стр.waitForTimeout(план.pauseMs ?? 900)
  // К месту снимка: заданный селектор (замечания точки) либо открытая карточка объекта.
  const куда = (план.scroll ?? {})[раздел.title]
  if (куда) {
    await стр.locator(`main ${куда}`).first().evaluate((у) => у.scrollIntoView({ block: 'start' })).catch(() => {})
  } else if (await стр.locator('main .v2-object').count()) {
    await стр.locator('main .v2-object').first().evaluate((у) => (у.closest('tr')?.previousElementSibling ?? у).scrollIntoView({ block: 'start' })).catch(() => {})
  }
  await стр.waitForTimeout(300)
  const файл = `${раздел.file}~открыто-${роль.file}-${ширина}.png`
  await стр.screenshot({ path: `${план.out}/${файл}` })
  итог.push({ file: файл, section: раздел.title, role: роль.file, width: ширина })
  console.log(`  ${файл}`)
}

/** Первая свёрнутая полоса группы (шип 5 §1.2) — раскрытой, у верхнего края. */
async function полоса(стр, раздел, роль, ширина) {
  const кнопка = стр.locator('main .v2-band button[aria-expanded="false"]').first()
  if (!(await кнопка.count())) return
  await кнопка.click()
  await стр.waitForTimeout(800)
  await стр.locator('main .v2-bandgroup:has(.v2-band:not(.v2-band--closed))').first().evaluate((у) => у.scrollIntoView({ block: 'start' }))
  await стр.waitForTimeout(300)
  const имя = `${раздел.file}~полоса-${роль.file}-${ширина}.png`
  await стр.screenshot({ path: `${план.out}/${имя}` })
  итог.push({ file: имя, section: раздел.title, role: роль.file, width: ширина })
  console.log(`  ${имя}`)
  await стр.locator('main .v2-band button[aria-expanded="true"]').first().click().catch(() => {})
  await стр.evaluate(() => window.scrollTo(0, 0))
}

/** Карточка объекта первой строки реестра — раскрытой вниз, строка у верхнего края. */
async function карточка(стр, раздел, роль, ширина) {
  const кнопка = стр.locator('main button[aria-label="карточка"]').first()
  if (!(await кнопка.count())) return
  await кнопка.click()
  await стр.waitForTimeout(1200)
  // Кнопка после клика зовётся «свернуть карточку», и прежний локатор уже
  // находит следующую строку: к месту ведём по самой карточке.
  await стр.locator('main .v2-object').first().evaluate((у) => у.closest('tr')?.previousElementSibling?.scrollIntoView({ block: 'start' }))
  await стр.waitForTimeout(300)
  const имя = `${раздел.file}~карточка-${роль.file}-${ширина}.png`
  await стр.screenshot({ path: `${план.out}/${имя}` })
  итог.push({ file: имя, section: раздел.title, role: роль.file, width: ширина })
  console.log(`  ${имя}`)
  await стр.locator('main button[aria-label="свернуть карточку"]').first().click().catch(() => {})
  await стр.evaluate(() => window.scrollTo(0, 0))
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
      // Вкладка раздела перед полосой и карточкой (Поле знаний → Факты): у раздела с вкладками полосы живут не на первой.
      const вкладка = (план.tab ?? {})[раздел.title]
      if (вкладка) {
        await стр.locator(`main [role=tab]:has-text("${вкладка}")`).first().click().catch(() => {})
        await стр.waitForTimeout(план.pauseMs ?? 900)
      }
      if ((план.cardSections ?? []).includes(раздел.title)) await карточка(стр, раздел, роль, ширина)
      if ((план.bandSections ?? []).includes(раздел.title)) await полоса(стр, раздел, роль, ширина)
      if ((план.open ?? {})[раздел.title]) await открытьКнопкой(стр, раздел, роль, ширина, план.open[раздел.title])
      if ((план.tabSections ?? []).includes(раздел.title)) await вкладки(стр, раздел, роль, ширина)
      if (раздел.title === 'Работа' && (план.scenes ?? []).length > 0) await сцены(стр, раздел, роль, ширина)
    }
    await контекст.close()
  }
}
await браузер.close()
console.log(JSON.stringify({ shots: итог }))
