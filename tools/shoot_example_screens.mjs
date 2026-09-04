// Снимки экранов проекта-примера под учёткой РП (задание «проект-пример»,
// выход для обзора владельца п. 2).
//
// Ходит по рейке разделов и панели экранов ТАК ЖЕ, как человек: адресов у
// экранов нет (состояние клиента), поэтому обход — кликами. Каждый снимок
// именуется шагом, под которым он снят, и ложится в docs/tz/пример/снимки/.
//
//   node tools/shoot_example_screens.mjs [http://localhost:8080] [PJ-NNNN]
//
// Браузер — chromium из web/node_modules (`npx playwright install chromium`).
import { chromium } from '../web/node_modules/playwright/index.mjs'
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const КОРЕНЬ = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const БАЗА = process.argv[2] || 'http://localhost:8080'
const ПРОЕКТ = process.argv[3] || null
const КУДА = resolve(КОРЕНЬ, 'docs/tz/пример/снимки')
const ИМЯ_ПРИМЕРА = 'Пример · Национальная IoT-платформа (полный)'

// Раздел рейки → экраны панели, которые снимаем. Пустой список — снять сам
// раздел, как он открывается (у раздела с одним экраном панели нет).
const ОБХОД = [
  ['Портфель', []],
  ['Работа', ['Работа фазы', 'Мои задания', 'Готовность к точке']],
  ['Постановка', ['Цели и нужды', 'Стейкхолдеры', 'Нужды и их сервисы',
                  'Сервисы и QoS', 'Сценарии ConOps']],
  ['Требования', ['Реестр требований', 'Матрицы', 'Трассировка и impact']],
  ['Концепция', ['Архитектура', 'Функции и цепочки', 'Элементы и интерфейсы',
                 'Внешняя модель', 'Дерево состава', 'Структура работ (WBS)',
                 'Оценки стоимости']],
  ['Модели', ['Модели системы', 'Карта спроса', 'Наземный сегмент',
              'Сравнение построений']],
  ['Документы', ['Комплект фазы и выпуски']],
  ['Библиотека', ['Материалы проекта', 'Разбор документов', 'Полки',
                  'Справочники', 'Результаты']],
  ['Контроль', ['Жизненный цикл', 'Паспорт проекта', 'Риски',
                'Орбитальное засорение', 'Верификация и валидация']],
  ['Инструменты', ['Служба ИИ']],
]

const пауза = (мс) => new Promise((r) => setTimeout(r, мс))

async function снять(page, имя, счёт) {
  // Ждём КОНЦА загрузки, а не «сколько-нибудь»: тяжёлые экраны (модели,
  // дерево состава) не успевали за паузой, и в артефакты обзора уходило
  // «Загрузка…» вместо содержимого
  await page.waitForFunction(
    () => !/Загрузка/.test(document.querySelector('main')?.innerText || ''),
    null, { timeout: 30000 },
  ).catch(() => console.log('    (экран так и не догрузился — снимаю как есть)'))
  await пауза(600)                       // дать отрисоваться после данных
  const файл = `${String(счёт).padStart(2, '0')}-${имя}.png`
    .replace(/[^\wа-яА-ЯёЁ.\-]+/g, '-')
  await page.screenshot({ path: resolve(КУДА, файл), fullPage: true })
  console.log('  снимок:', файл)
}

async function main() {
  mkdirSync(КУДА, { recursive: true })
  const browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1680, height: 1050 } })

  // Вход учёткой стенда — тем же каналом, что и клиент
  await page.goto(БАЗА)
  await page.evaluate(async () => {
    await fetch('/api/auth/stand-login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ login: 'chernov' }),
    })
  })
  await page.reload()
  await page.waitForSelector('.rail__item', { timeout: 20000 })

  // Проект примера открывается ВСЕГДА: снимки — его, а не «того, что
  // осталось открытым». Без выбора клиент шлёт запрос без ?project, и при
  // нескольких проектах в портфеле каждый экран отвечает отказом —
  // ровно это и попало в первую партию снимков.
  {
    const искать = ПРОЕКТ || ИМЯ_ПРИМЕРА
    await page.click('button[title="раздел «Портфель»"]')
    await пауза(700)
    // Портфель грузится не мгновенно: дождаться списка, а не «Загрузка…»
    await page.waitForFunction(
      () => !document.querySelector('main')?.innerText.includes('Загрузка портфеля'),
      null, { timeout: 30000 })
    const карточка = page.locator('main').getByText(искать, { exact: false }).first()
    if (!(await карточка.count())) {
      throw new Error(`проект «${искать}» в портфеле не найден: снимать нечего. `
        + 'Соберите пример: python3 tools/build_example_project.py')
    }
    await карточка.click()
    // Шапка называет открытый проект: по ней и проверяем, что открыт нужный
    await page.waitForFunction(
      (имя) => document.querySelector('header')?.innerText.includes(имя),
      ИМЯ_ПРИМЕРА, { timeout: 20000 })
    console.log(`  проект примера открыт: ${ИМЯ_ПРИМЕРА}`)
  }

  let счёт = 0
  for (const [раздел, экраны] of ОБХОД) {
    const кнопка = page.locator(`button[title="раздел «${раздел}»"]`)
    if (!(await кнопка.count())) {
      console.log(`раздел «${раздел}» в рейке не найден — пропуск`)
      continue
    }
    await кнопка.click()
    await пауза(900)
    if (экраны.length === 0) {
      await снять(page, раздел, ++счёт)
      continue
    }
    let снятых = 0
    for (const экран of экраны) {
      const пункт = page.locator('aside.ops button.ops__item', { hasText: экран }).first()
      if (!(await пункт.count())) continue
      await пункт.click()
      await снять(page, `${раздел}-${экран}`, ++счёт)
      снятых += 1
    }
    if (снятых === 0) await снять(page, раздел, ++счёт)
  }

  await browser.close()
  console.log(`снимков: ${счёт} → ${КУДА}`)
}

main().catch((e) => { console.error(e); process.exit(1) })
