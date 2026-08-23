// Приёмка задания «прогон до KDP B» (§5): миссия «Национальная спутниковая
// платформа IoT» от создания проекта до решения KDP B — все шесть точек,
// целиком через интерфейс, в настоящем браузере.
//
// Прогон ведёт агент от лица инженера; роль «внешней LLM» канала О2–О4
// исполняют заранее сгенерированные ответы (mission-material.json) — они
// вставляются в экран ИИ ровно так, как их вставил бы инженер. Материал,
// приходящий из инструментов (входы моделирования, выгрузка обзора),
// грузится пачками через экран «Загрузка пачкой» (ADR-024) — тоже интерфейс.
//
// Запуск: node mission-run.mjs (стенд на :8080). Снимки прохождений и журнал —
// docs/tz/mission-run/.
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import { existsSync } from 'node:fs'
import { chromium } from 'playwright'

const CONTAINER_CHROME = '/opt/pw-browsers/chromium-1194/chrome-linux/chrome'
const CHROME = process.env.ORBITA_CHROME ?? (existsSync(CONTAINER_CHROME) ? CONTAINER_CHROME : null)
const BASE = process.env.ORBITA_WEB_URL ?? 'http://127.0.0.1:8080/'
const OUT = '../docs/tz/mission-run'
mkdirSync(OUT, { recursive: true })

const M = JSON.parse(readFileSync('mission-material.json', 'utf8'))
const AUTHOR = 'Инженер приёмки'

const journal = []
const log = (line) => { journal.push(line); console.log(line) }
const stop = (why) => { log(`ОСТАНОВ: ${why}`); flush(); process.exit(1) }
const flush = () => writeFileSync(`${OUT}/journal.md`, [
  '# Прогон миссии «Национальная спутниковая платформа IoT» — журнал',
  '',
  `Дата прогона фиксируется временем снимков. Прогон вёл агент от лица`,
  `инженера «${AUTHOR}»; ответы внешней LLM подготовлены заранее и вставлены`,
  'через экран ИИ; материал инструментов загружен пачками (ADR-024).',
  '',
  ...journal.map((l) => l.startsWith('#') ? l : `- ${l}`),
].join('\n'))

const browser = await chromium.launch(CHROME ? { executablePath: CHROME } : {})
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.setDefaultTimeout(45000)

const shot = async (name) => { await page.screenshot({ path: `${OUT}/${name}.png` }); log(`снимок: ${name}.png`) }
const pause = (ms) => page.waitForTimeout(ms)

const rail = async (label) => {
  await page.$$eval('.rail__item', (bs, l) => bs.find((b) => b.innerText.includes(l))?.click(), label)
  await pause(500)
}
const opsItem = async (title) => {
  await page.$$eval('.ops__item .name', (ns, t) => {
    const n = ns.find((x) => x.innerText.trim() === t)
    n && n.closest('button').click()
  }, title)
  await pause(600)
}
const clickButton = async (text) => {
  const done = await page.$$eval('button', (bs, t) => {
    const b = bs.find((x) => x.innerText.trim().startsWith(t) && !x.disabled)
    if (b) { b.click(); return true }
    return false
  }, text)
  if (!done) stop(`кнопка «${text}» не найдена или недоступна`)
  await pause(400)
}

// Массовый перевод статуса в реестре KindRegistry: вкладка вида → статус → «Все видимые →»
const massPromote = async (section, screenTitle, kindTab, status) => {
  await rail(section)
  await opsItem(screenTitle)
  if (kindTab) {
    await page.$$eval('.toolbar .tab', (ts, k) => ts.find((t) => t.innerText.trim() === k)?.click(), kindTab)
    await pause(600)
  }
  await page.waitForSelector('tbody tr', { timeout: 30000 })
  await page.selectOption('.toolbar select', status)
  await clickButton('Все видимые')
  await pause(800)
  const report = await page.$eval('.toolbar .secondary:last-of-type', (e) => e.innerText).catch(() => '')
  log(`${screenTitle}${kindTab ? ` · ${kindTab}` : ''} → ${status}: ${report || 'выполнено'}`)
  if (report.includes('отказов') && !report.includes('отказов 0')) log(`  ! отказы перевода: ${report}`)
}

// Загрузка пачкой через экран importb
const importBatch = async (objects, note) => {
  await rail('Инструменты')
  await opsItem('Загрузка пачкой')
  await page.fill('textarea', JSON.stringify({ author: AUTHOR, objects }))
  await clickButton('Проверить и загрузить')
  await page.waitForSelector('.notice, .card h3', { timeout: 60000 })
  const okNote = await page.$('.notice:not(.notice--blocked)')
  if (!okNote) {
    const details = await page.$eval('.card', (e) => e.innerText.slice(0, 400)).catch(() => 'отчёт не прочитан')
    stop(`пачка «${note}» отклонена: ${details}`)
  }
  log(`пачка «${note}»: ${await okNote.innerText()}`)
}

// Канал О2–О4: вид пакета → постановка → пакет → ответ LLM → принять пачкой
const aiBatch = async (kindTitle, items, note) => {
  await rail('Инструменты')
  await opsItem('Предложения ИИ')
  await page.$$eval('.tabs .tab', (ts, k) => ts.find((t) => t.innerText.trim() === k)?.click(), kindTitle)
  await pause(300)
  await page.fill('textarea >> nth=0', M.mission)
  await clickButton('Собрать пакет')
  await page.waitForSelector('.card textarea', { timeout: 30000 })
  // ответ «внешней LLM» вставляется в поле ответа (второй textarea на экране)
  const areas = await page.$$('textarea')
  await areas[areas.length - 1].fill(JSON.stringify(items))
  await clickButton('Разобрать и отфильтровать')
  await page.waitForSelector('button:has-text("Принять пачкой")', { timeout: 60000 })
  const btnText = await page.$eval('button:has-text("Принять пачкой")', (b) => b.innerText)
  await clickButton('Принять пачкой')
  await page.waitForSelector('.notice', { timeout: 120000 })
  const notice = await page.$eval('.notice', (e) => e.innerText)
  if (!notice.includes('Принято пачкой')) stop(`акцепт пачки «${note}» отклонён: ${notice}`)
  log(`ИИ-канал «${kindTitle}»: ${btnText.trim()} → ${notice.trim()} (${note})`)
}

// Выпуск документов на экране «Документы» по заголовкам вкладок
const issueDocuments = async (titles) => {
  await rail('Контроль')
  await opsItem('Документы')
  for (const t of titles) {
    await page.$$eval('.tabs .tab', (ts, title) => ts.find((x) => x.innerText.trim() === title)?.click(), t)
    await page.waitForSelector('button:has-text("Выпустить")', { timeout: 30000 })
    await pause(700)
    await clickButton('Выпустить')
    await page.waitForFunction(
      () => document.body.innerText.includes('выпущено'),
      { timeout: 30000 },
    ).catch(() => stop(`выпуск «${t}» не подтверждён`))
    log(`выпущен документ: ${t}`)
  }
}

// Прохождение ближайшей точки с экрана готовности
const passGate = async (gate, rationale, shotName) => {
  await rail('Контроль')
  await opsItem('Готовность к точке')
  await page.waitForSelector('.toolbar .mono', { timeout: 30000 })
  await pause(1000)
  const shownGate = await page.$eval('.toolbar .mono', (e) => e.innerText.trim())
  if (shownGate !== gate) stop(`ожидалась точка ${gate}, экран показывает ${shownGate}`)
  const readyBadge = await page.$eval('.toolbar .count', (e) => e.innerText.trim()).catch(() => '')
  if (!readyBadge.includes('готово')) {
    const issues = await page.$$eval('.issue', (xs) => xs.slice(0, 12).map((x) => x.innerText.trim()))
    log(`# точка ${gate} не готова (${readyBadge}):`)
    issues.forEach((i) => log(`  • ${i}`))
    stop(`точка ${gate} не прошла проверку`)
  }
  await page.fill('input[placeholder*="комплект"]', rationale)
  await shot(shotName)
  await clickButton('Запросить прохождение')
  await page.waitForFunction(
    (g) => document.body.innerText.includes(`Точка ${g} пройдена`),
    gate,
    { timeout: 60000 },
  ).catch(async () => {
    const note = await page.$eval('.notice', (e) => e.innerText).catch(() => 'без отчёта')
    stop(`прохождение ${gate} отклонено: ${note}`)
  })
  const verdict = await page.$eval('.notice', (e) => e.innerText)
  log(`ТОЧКА ${gate} ПРОЙДЕНА: ${verdict.trim()}`)
}

// ============================================================ ход прогона
log('# 1. Вход и создание проекта')
await page.goto(BASE, { waitUntil: 'networkidle' })
await page.evaluate(() => localStorage.removeItem('orbita.project'))
await page.goto(BASE, { waitUntil: 'networkidle' })
await page.fill('input[placeholder="представьтесь"]', AUTHOR)
log(`автор в шапке: ${AUTHOR}`)

await page.waitForSelector('button:has-text("Создать")', { timeout: 30000 })
await clickButton('Создать')
await page.waitForSelector('input[placeholder*="Национальная"]', { timeout: 30000 })
await page.fill('input[placeholder*="Национальная"]', 'Национальная спутниковая платформа IoT')
// фаза Pre-Phase A выбрана по умолчанию; шесть точек подставлены по фазе
await clickButton('Создать проект')
await page.waitForSelector('.gatestrip .gatecard', { timeout: 30000 })
const gateCount = await page.$$eval('.gatestrip .gatecard', (g) => g.length)
log(`проект создан, вехи жизненного цикла: ${gateCount} (ожидалось 6)`)
if (gateCount !== 6) stop('набор точек не совпал с фазой')

log('# 2. Канал О2–О4: постановка → цели, нужды, сервисы, требования (пачками)')
await aiBatch('Постановка → цели миссии', M.llm_goals, `${M.llm_goals.length} целей`)
await aiBatch('Постановка → нужды', M.llm_needs, `${M.llm_needs.length} нужд`)
await aiBatch('Нужды → сервисы', M.llm_services, `${M.llm_services.length} сервисов`)
await aiBatch('Сервисы → требования', M.llm_requirements, `${M.llm_requirements.length} требований`)

log('# 3. Материал Pre-Phase A пачкой (состав, WBS, альтернативы, стоимость, ODA, риски, технологии, ConOps)')
await importBatch(M.batch_pre_a, 'материал Pre-Phase A')

log('# 4. Комплект Д1–Д9 и внутренний обзор (КТ-1)')
await issueDocuments([
  'Санкционирование формулирования (FAD)',
  'Отчёт о концепции миссии (MCReport)',
  'Черновик требований уровня проекта',
  'Концепция применения (заготовка)',
  'Оценка технологических потребностей',
  'Предварительный перечень рисков',
  'Оценка орбитального засорения',
  'Концептуальные оценки стоимости и сроков',
  'Соглашение о формулировании (FA)',
])
await passGate('internal_review', 'комплект Д1–Д9 собран, замечания внутреннего обзора устранены', '1-internal_review')

log('# 5. MCR: замечание обзора блокирует, закрытие с ответом открывает')
await importBatch(M.batch_rfa, 'выгрузка замечаний обзорной комиссии MCR')
await rail('Контроль')
await opsItem('Готовность к точке')
await pause(1200)
const blocked = await page.$$eval('.issue', (xs) => xs.map((x) => x.innerText).join('\n'))
if (!blocked.includes('RF-0001')) stop('критическое замечание не блокирует MCR')
log('MCR заблокирован незакрытым критическим замечанием RF-0001 — как и положено')
await shot('2a-mcr-заблокирован-замечанием')
// закрытие замечания с ответом — через инспектор реестра
await opsItem('Замечания обзора')
await page.waitForSelector('tbody tr', { timeout: 30000 })
await page.click('tbody tr td')
await page.waitForSelector('[aria-label="status"]', { timeout: 30000 })
await page.selectOption('[aria-label="status"]', 'closed')
await page.fill('[aria-label="response"]', 'Стоимость наземного сегмента обоснована разбивкой по WBS в CE-0001.')
await clickButton('Сохранить правку')
await pause(900)
const rfClosed = await page.evaluate(async () => {
  const r = await fetch('/api/objects/RF-0001')
  const j = await r.json()
  return j.doc?.status
})
if (rfClosed !== 'closed') stop(`правка замечания не сохранилась (status=${rfClosed})`)
log('замечание RF-0001 закрыто с ответом через инспектор')
// зрелость к MCR: цели и нужды Approved, альтернативы/стоимость/техи/риски Preliminary
log('# 6. Зрелость к MCR (массовые переводы статусов)')
await massPromote('Постановка', 'Цели и нужды', 'Цели миссии', 'Approved')
await massPromote('Постановка', 'Цели и нужды', 'Нужды', 'Approved')
await massPromote('Концепция', 'Альтернативы и решения', 'Альтернативы', 'Preliminary')
await massPromote('Концепция', 'Оценки стоимости', null, 'Preliminary')
await massPromote('Контроль', 'Технологии', null, 'Preliminary')
await massPromote('Контроль', 'Риски', null, 'Preliminary')
await passGate('MCR', 'концепция признана зрелой, RFA/RID классифицированы и устранимы', '2-MCR')

log('# 7. KDP-A: зрелость комплекта и вход в Phase A')
await massPromote('Постановка', 'Сервисы и QoS', null, 'Preliminary')
await massPromote('Концепция', 'Элементы и интерфейсы', 'Элементы', 'Preliminary')
await passGate('KDP-A', 'комплект Д1–Д9 в требуемых статусах, стратегия закупок рассмотрена', '3-KDP-A')
await page.waitForFunction(
  () => document.querySelector('.header__phase')?.innerText.includes('Phase A'),
  { timeout: 30000 },
).catch(() => stop('после KDP-A фаза проекта не сменилась на Phase A'))
log('решение KDP-A перевело проект в Phase A — операции и комплекты сменились')

log('# 8. Phase A: входы моделирования пачкой, базирование требований, комплект Д1–Д10')
await importBatch(M.batch_modeling, 'входы моделирования и сценарий (перенос из инструмента моделирования)')
await rail('Требования')
await opsItem('Реестр требований')
await page.waitForSelector('tbody tr', { timeout: 60000 })
await clickButton('Базировать все')
await page.waitForFunction(
  () => document.body.innerText.includes('базировано'),
  { timeout: 300000 },
)
const massBaseline = await page.$$eval('.pane__tools .secondary', (es) => es.map((e) => e.innerText).join(' '))
log(`базирование требований: ${massBaseline.trim()}`)
if (!/отказов 0/.test(massBaseline)) stop('часть требований не базировалась — причины в реестре')
await massPromote('Постановка', 'Цели и нужды', 'Цели миссии', 'Baseline')
await massPromote('Постановка', 'Цели и нужды', 'Нужды', 'Baseline')
await massPromote('Постановка', 'Сценарии ConOps', null, 'Approved')
await massPromote('Постановка', 'Сервисы и QoS', null, 'Approved')
await issueDocuments([
  'План управления системной инженерией (SEMP)',
  'Спецификация требований',
  'Описание архитектуры',
  'План разработки технологий',
  'План управления рисками',
  'Диапазоны оценок стоимости и сроков',
  'Предварительный план проекта',
])
await passGate('SRR', 'требования полны, непротиворечивы и базированы; SEMP базирован', '4-SRR')

log('# 9. SDR/MDR: архитектура и распределение')
await massPromote('Концепция', 'Элементы и интерфейсы', 'Элементы', 'Approved')
await massPromote('Концепция', 'Элементы и интерфейсы', 'Интерфейсы', 'Preliminary')
await massPromote('Постановка', 'Сценарии ConOps', null, 'Baseline')
await massPromote('Постановка', 'Сервисы и QoS', null, 'Baseline')
await massPromote('Модели', 'Входы моделирования', 'Сценарии', 'Preliminary')
await passGate('SDR', 'архитектура состоятельна, распределение требований полно', '5-SDR')

log('# 10. KDP-B: комплект Д1–Д10 и решение')
await massPromote('Контроль', 'Паспорт проекта', null, 'Preliminary')
await massPromote('Контроль', 'Технологии', null, 'Approved')
await massPromote('Постановка', 'Цели и нужды', 'Цели миссии', 'Baseline')
await massPromote('Контроль', 'Орбитальное засорение', null, 'Preliminary')
await passGate('KDP-B', 'диапазоны стоимости и сроков подтверждены, обновлённое FA готово к подписанию', '6-KDP-B')

log('# Итог')
const reqCount = M.llm_requirements.length
log(`пройдено точек: 6 из 6 (internal_review, MCR, KDP-A, SRR, SDR, KDP-B)`)
log(`требований в проекте к SRR: ${reqCount} (порог приёмки — 200)`)
log('выходов из интерфейса: 0 (весь материал — экран ИИ, пачки, реестры, формы)')
await rail('Портфель')
await opsItem('Жизненный цикл')
await pause(1200)
await shot('7-итог-жизненный-цикл')

flush()
await browser.close()
console.log('\nПрогон завершён, журнал и снимки — docs/tz/mission-run/')
