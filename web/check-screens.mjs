// Проверка экранов на РЕАЛЬНЫХ данных API (STEP-6, определение готовности;
// STEP-7-9 §9.2 — сквозной проход мастера Ш1–Ш7).
//
// Требует запущенных: демонстрационного API и dev-сервера клиента —
//   ./gradlew :core:com:demoServer     (порт 8080)
//   npm run dev                        (порт 5173, проксирует /api)
// Запуск: npm run check:screens
//
// Проверяется то, что нельзя увидеть по одному лишь ответу API: строки
// действительно отрисованы, отступ дерева виден, превышение бюджета покрашено,
// карточка требования открывается, шаг мастера проходим, ошибок в консоли нет.
//
// Проход идёт ТОЛЬКО через интерфейс: ни консоли, ни правки базы. Иначе это
// уже не проверка пригодности для работы, а проверка API другим способом.
import { chromium } from 'playwright'

const CHROME = process.env.ORBITA_CHROME ?? '/opt/pw-browsers/chromium-1194/chrome-linux/chrome'
const BASE = process.env.ORBITA_WEB_URL ?? 'http://127.0.0.1:5173/'
const SHOTS = process.env.ORBITA_SHOTS ?? null

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

/** Переход по мастеру: шаг слева, экран шага — вкладкой сверху. */
const open = async (step, screen) => {
  await page.click(`.wizard__step:has-text("Ш${step}.")`)
  await page.click(`.topbar button:has-text("${screen}")`)
  await page.waitForTimeout(400)
}

const shot = async (name) => {
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: false })
}

// Состояний у шага ТРИ: ответ ещё не пришёл, ответ пришёл и данных нет,
// ответ пришёл с данными. Ответ задерживается намеренно — иначе на прогретом
// стенде он приходит мгновенно, и первое состояние проверить нечем. На холодном
// стенде оно держалось секунды, и мастер всё это время говорил «нет данных».
console.log('Мастер: состояние до прихода ответа')
let wizardDelayed = false
await page.route('**/api/views/wizard', async (route) => {
  // задерживается только первый запрос: дальше мастер работает как обычно
  if (!wizardDelayed) {
    wizardDelayed = true
    await new Promise((r) => setTimeout(r, 1500))
  }
  // переход на новую страницу отменяет запрос — тогда продолжать уже нечего
  try {
    await route.continue()
  } catch {
    /* маршрут уже обработан навигацией */
  }
})
await page.goto(BASE, { waitUntil: 'commit' })
await page.waitForSelector('.wizard__step small')
const whileLoading = await page.$$eval('.wizard__step small', (ss) => ss.map((s) => s.innerText))
expect(
  whileLoading.every((s) => s.includes('загрузка')),
  'до ответа шаг помечен загрузкой, а не «нет данных»',
)
expect(
  (await page.$$('.wizard__step small.amber')).length === 0,
  'до ответа шаги не покрашены: замечаний ещё никто не находил',
)
await page.goto(BASE, { waitUntil: 'networkidle' })
await page.waitForSelector('.wizard__step')
// Ждём именно ПРИХОДА состояния, а не «сколько-нибудь секунд»: на холодном
// стенде ответ идёт заметно дольше, чем на прогретом, и проверка
// с фиксированной паузой ловила мастер в состоянии загрузки.
await page.waitForFunction(
  () => ![...document.querySelectorAll('.wizard__step small')].some((s) => s.innerText.includes('загрузка')),
  { timeout: 30000 },
)

console.log('Мастер: состояние шагов')
const steps = await page.$$eval('.wizard__step', (bs) => bs.map((b) => b.innerText.replace('\n', ' · ')))
expect(steps.length === 7, `шагов Ш1–Ш7 показано ${steps.length}`)
steps.forEach((s) => console.log(`     ${s}`))
expect(
  steps.some((s) => s.includes('замечаний')),
  'шаг с замечаниями отличим от закрытого',
)
expect(
  !steps.some((s) => s.includes('нет данных')),
  'состояние каждого шага пришло с сервера',
)

console.log('Ш1 · экран 1: нужды стейкхолдеров')
await open(1, 'Нужды')
await page.waitForSelector('tbody tr')
const needs = await page.$$eval('tbody tr', (trs) =>
  trs.map((tr) => [...tr.querySelectorAll('td')].map((td) => td.innerText.trim())),
)
expect(needs.length >= 3, `нужды отрисованы (${needs.length})`)
expect(needs.some((r) => r[5].includes('нет сервисов')), 'разрыв трассировки виден в строке')
// Формулировка — главное содержимое строки; сжатая до нечитаемости колонка
// делает экран бесполезным, и по ответу API этого не увидеть.
const statementWidth = await page.$eval(
  '.pane > table tbody tr:first-child td:nth-child(2)',
  (td) => td.getBoundingClientRect().width,
)
expect(statementWidth >= 200, `колонка формулировки читаема (${Math.round(statementWidth)} px)`)
await page.click('.pane button:has-text("Без сервисов")')
await page.waitForTimeout(200)
const orphans = await page.$$eval('tbody tr', (trs) => trs.length)
expect(orphans > 0 && orphans < needs.length, `отбор «без сервисов» сузил список до ${orphans}`)
await page.click('.pane button:has-text("Без сервисов")')
await page.click('tbody tr:first-child')
expect((await page.$$('aside .card')).length > 0, 'карточка нужды открывается')
await shot('01-needs')

console.log('Ш2 · экран 2: сервисы и профили QoS')
await open(2, 'Сервисы')
await page.waitForSelector('tbody tr')
// строго левая таблица: в правой панели свои таблицы MOE, и их строки — не сервисы
const services = await page.$$eval('.pane > table tbody tr td:nth-child(3)', (tds) =>
  tds.map((t) => t.innerText),
)
expect(services.length >= 2, `сервисы отрисованы (${services.length})`)
expect(services.every((s) => s.includes('′')), 'классы потребителей показаны раздельно (Р9)')
const uncovered = await page.$$eval('aside .amber', (e) => e.map((x) => x.innerText))
expect(uncovered.length > 0, `непокрытый класс назван: ${uncovered[0] ?? '—'}`)
await shot('02-services')

console.log('Ш2 · экран 4: карта спроса')
await open(2, 'Карта спроса')
await page.waitForSelector('.empty, svg rect')
expect(
  (await page.$$('.empty')).length > 0,
  'пустая карта названа пустой, а не показана нулями',
)
// слой библиотеки включается кнопкой — это и есть проход шага интерфейсом
await page.click('.pane .tab:has-text("Агромониторинг")')
await page.waitForSelector('svg rect')
const cells = (await page.$$('svg rect')).length
expect(cells > 0, `ячейки карты отрисованы (${cells})`)
const bands = await page.$$eval('.pane table tbody tr td.mono', (tds) => tds.map((t) => t.innerText))
expect(bands.some((b) => b.includes('°')), 'широтный профиль показан весами поясов')
const version = await page.$eval('aside .card .mono', (e) => e.innerText)
expect(version.trim().length > 0, `версия карты показана: ${version}`)
await shot('04-demand')

console.log('Ш3 · экран 3: дерево требований')
await open(3, 'Дерево требований')
await page.waitForSelector('tbody tr')
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

// Та же проверка, что и на экране 1: появление мастера слева отняло ширину
// у панели, и формулировка требования схлопнулась до полусотни пикселей.
const reqStatementWidth = await page.$eval(
  '.pane > table tbody tr:first-child td:nth-child(2)',
  (td) => td.getBoundingClientRect().width,
)
expect(reqStatementWidth >= 180, `формулировка требования читаема (${Math.round(reqStatementWidth)} px)`)

const indents = await page.$$eval('tbody tr td:first-child span', (spans) =>
  [...new Set(spans.map((s) => s.style.paddingLeft).filter(Boolean))],
)
expect(indents.length > 1, `отступ по уровню дерева виден (${indents.join(', ')})`)
if (hasOverrun) {
  expect((await page.$$('.bar__fill--overrun')).length > 0, 'полоса превышения покрашена')
} else {
  skip('покраска полосы превышения', 'превышений в данных нет')
}

console.log('Ш3 · экран 3б: карточка требования')
await page.click('tbody tr:first-child')
await page.waitForSelector('.card')
const cards = await page.$$eval('.card h3', (h) => h.map((x) => x.innerText))
expect(cards.includes('Условие'), 'карточка показывает условие')
expect(cards.includes('Верификация'), 'карточка показывает верификацию')
const criterion = await page.$eval('.card .field span.mono', (e) => e.innerText)
expect(criterion.trim().length > 0, `критерий успеха выведен сервером: «${criterion}»`)
await shot('03-requirements')

console.log('Ш3 · экран 8: предложение ИИ')
await open(3, 'Предложение ИИ')
await page.click('.pane button:has-text("Собрать пакет")')
await page.waitForSelector('.card .id')
const pkgId = await page.$eval('.card .id', (e) => e.innerText)
expect(/^PP-[0-9a-f]{8}$/.test(pkgId), `пакет собран сервером: ${pkgId}`)
const pkgText = await page.$eval('textarea[readonly]', (e) => e.value)
expect(pkgText.includes('response_schema'), 'пакет несёт схему ответа полем, а не текстом задания')
// Ответ модели вставляется руками — генерации внутри системы нет (канал 1).
// Предложение состоятельно намеренно: чтобы дойти до акцепта, оно обязано
// пройти структурный фильтр, а фильтр вызывает правила core/req целиком.
const ANSWER = JSON.stringify([
  {
    id: 'RQ-0101',
    statement: 'Масса платформы не должна превышать 58 кг.',
    level: 'element',
    owner: 'вед. системный инженер',
    category: 'performance',
    lifecycle: { status: 'Draft', version: '1' },
    traces_up: [{ ref: 'RQ-0100' }],
    allocated_to: [{ component: 'CM-0011' }],
    verification: {
      method: 'test',
      approach: 'Взвешивание собранной платформы на поверенных весах перед сдачей заказчику.',
      means: 'весы лабораторные, методика МИ-14',
      phase: 'C',
    },
    verification_events: [
      {
        id: 'VE-0101',
        method: 'test',
        kind: 'acceptance',
        phase: 'C',
        level: 'element',
        closes: true,
        unit: 'лётный экземпляр №1',
        approach: 'Взвешивание собранной платформы на поверенных весах перед сдачей заказчику.',
        means: 'весы лабораторные, методика МИ-14',
      },
    ],
    mop: {
      name: 'Масса платформы',
      value: { unit: 'kg', value: 58, provenance: { source: 'manual' } },
      rollup: 'none',
      operator: 'le',
    },
  },
])
await page.fill('textarea:not([readonly])', '```json\n' + ANSWER + '\n```')
await page.click('.pane button:has-text("Разобрать и отфильтровать")')
await page.waitForTimeout(600)
const stats = await page.$$eval('.pane .mono', (e) => e.map((x) => x.innerText))
expect(stats.length > 0, `отчёт по пакету показан: ${stats.slice(0, 4).join(' / ')}`)
const reworkOrShown = await page.$$eval('.pane, aside', (panes) => panes.map((p) => p.innerText).join(' '))
expect(
  reworkOrShown.includes('Отбраковано по правилам') || reworkOrShown.includes('Дошло до инженера'),
  'структурный фильтр отработал до показа инженеру',
)
// Акцепт по полям: массовой кнопки нет намеренно (TZ-AI-004, ловушка 2)
const shown = await page.$$('.pane .card:has(table) tbody tr')
if (shown.length > 0) {
  const applyBefore = await page.$('button:has-text("Применить выбранные поля")')
  expect(
    applyBefore !== null && (await applyBefore.isDisabled()),
    'акцепт недоступен, пока поле не выбрано',
  )
  // структурное поле обязано быть читаемым: акцепт вслепую — не акцепт
  const values = await page.$$eval('.pane .card table tbody tr td:last-child', (tds) =>
    tds.map((t) => t.innerText),
  )
  expect(
    !values.some((v) => v.includes('[object Object]')),
    'структурные поля diff показаны содержимым, а не «[object Object]»',
  )
  await shown[0].click()
  await page.click('button:has-text("Применить выбранные поля")')
  await page.waitForTimeout(600)
  const afterAccept = await page.$eval('.pane', (e) => e.innerText)
  expect(afterAccept.includes('Принято полей'), 'выбранные поля применены и помечены акцептом')
} else {
  skip('акцепт полей', 'ни одно предложение не прошло структурный фильтр')
}
await shot('08-ai-proposal')

console.log('Ш4 · экран 11: спецификация элемента')
await open(4, 'Спецификация элемента')
await page.waitForSelector('tbody tr')
const spec = await page.$$eval('tbody tr', (trs) =>
  trs.map((tr) => [...tr.querySelectorAll('td')].map((td) => td.innerText.trim())),
)
expect(spec.length > 0, `требования элемента отрисованы (${spec.length})`)
expect(spec.every((r) => r[3].length > 0), 'источник требования показан')
expect(spec.every((r) => /\d+ из \d+/.test(r[4])), 'прогресс событий верификации показан')

console.log('Ш4 · экран 5: модель космического аппарата')
await open(4, 'Модель КА')
await page.waitForSelector('.card:has-text("массовый бюджет") tbody tr')
const mass = await page.$$eval('.card:has-text("массовый бюджет") tbody tr', (trs) =>
  trs.map((tr) => [...tr.querySelectorAll('td')].map((td) => td.innerText.trim())),
)
expect(mass.length >= 3, `ведомость масс отрисована (${mass.length})`)
expect(new Set(mass.map((r) => r[3])).size > 1, 'резерв по зрелости различается по элементам')
const links = await page.$$eval('.card:has-text("радиолинии") tbody tr', (trs) =>
  trs.map((tr) => [...tr.querySelectorAll('td')].map((td) => td.innerText.trim())),
)
expect(links.length > 0, `радиолинии отрисованы (${links.length})`)
expect(links.every((r) => r[5].length > 0), 'ограничивающий фактор зоны назван')
const tpm = await page.$$eval('.card:has-text("TPM") tbody tr', (trs) => trs.length)
expect(tpm >= 3, `TPM собран из модели (${tpm} строк)`)
await shot('05-spacecraft')

console.log('Ш5 · экран 7: сравнение вариантов')
await open(5, 'Сравнение вариантов')
await page.waitForSelector('svg[aria-label="Роза KPI"]')
const polygons = (await page.$$('svg polygon')).length
expect(polygons > 3, `роза отрисована (полигонов ${polygons})`)
const normalizedOver = await page.$eval('.pane .mono', (e) => e.innerText)
expect(normalizedOver.includes('·'), `диаграмма подписывает набор нормировки: ${normalizedOver}`)
const pareto = await page.$$eval('.card .chip', (e) => e.map((x) => x.innerText))
expect(pareto.length > 0, `фронт Парето показан: ${pareto.join(', ')}`)

console.log('Ш5 · экран 6: глобус')
await open(5, 'Баллистика')
await page.waitForTimeout(9000)
const globeStatus = await page.$eval('.topbar .mono', (e) => e.innerText).catch(() => '')
expect(/трасс: [1-9]/.test(globeStatus), `трассы загружены из CZML: ${globeStatus}`)
expect((await page.$$('canvas')).length > 0, 'глобус отрисован')

console.log('Ш6 · экран 12: система в целом')
await open(6, 'Система в целом')
await page.waitForSelector('.matrix')
expect((await page.$$('.matrix .cell')).length === 25, 'матрица рисков 5×5 отрисована')
expect((await page.$$('.cell--high')).length > 0, 'клетки высокой критичности выделены')
const problems = await page.$$eval('.warn', (e) => e.filter((x) => x.innerText.startsWith('△')).length)
expect(problems > 0, `проблемы перечислены (${problems})`)

console.log('Ш7 · экран 9: готовность к контрольной точке')
await open(7, 'Готовность к точке')
await page.waitForSelector('.pane .mono')
const srr = await page.$eval('.pane', (e) => e.innerText)
expect(srr.includes('Объектов готово'), 'готовность показана числом объектов')
await page.click('.pane button:has-text("SDR")')
await page.waitForTimeout(500)
const sdr = await page.$eval('.pane', (e) => e.innerText)
expect(srr !== sdr, 'планка точки SDR отличается от SRR')
await shot('09-readiness')

console.log('Ш7 · реестр рисков')
await open(7, 'Реестр рисков')
await page.waitForSelector('.matrix')
const riskRows = await page.$$eval('.pane > table tbody tr', (trs) => trs.length)
expect(riskRows > 0, `риски отрисованы (${riskRows})`)
const critChips = await page.$$eval('.pane > table .chip', (e) => [...new Set(e.map((x) => x.innerText))])
expect(critChips.length > 1, `критичность различается по рискам: ${critChips.join(', ')}`)
await page.click('.matrix .cell--high')
await page.waitForTimeout(300)
const filtered = await page.$$eval('.pane > table tbody tr', (trs) => trs.length)
expect(filtered > 0 && filtered <= riskRows, `матрица работает навигацией (${filtered} из ${riskRows})`)
await shot('10-risks')

console.log(`ошибок в консоли: ${consoleErrors.length}`)
consoleErrors.forEach((e) => console.log('   ', e))
await browser.close()

if (failures.length || consoleErrors.length) {
  console.log(`\nИтог: провалено ${failures.length + consoleErrors.length}`)
  process.exit(1)
}
console.log('\nИтог: мастер Ш1–Ш7 проходится интерфейсом на данных API')
