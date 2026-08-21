// КРИТЕРИЙ ПРИЁМКИ ШАГА 15: пустой проект, Ш1–Ш7 через интерфейс, пакет
// передачи на выходе. Понадобилась консоль, база или seedDemo — не сделано.
//
// Проверка идёт ТОЛЬКО через интерфейс настоящим браузером: ни одного запроса
// к API отсюда не делается, объекты создаются заполнением форм и нажатием
// кнопок. Иначе это была бы проверка API другим способом, а проверяется
// пригодность к работе руками.
//
// Требует ПУСТОГО сервера и клиента:
//   ./gradlew :core:com:emptyServer   (порт 8080; база очищается при старте)
//   npm run dev                       (порт 5173)
// Запуск: node check-empty-project.mjs
import { existsSync } from 'node:fs'
import { chromium } from 'playwright'

const CONTAINER_CHROME = '/opt/pw-browsers/chromium-1194/chrome-linux/chrome'
const CHROME = process.env.ORBITA_CHROME ?? (existsSync(CONTAINER_CHROME) ? CONTAINER_CHROME : null)
const BASE = process.env.ORBITA_WEB_URL ?? 'http://127.0.0.1:5173/'

const failures = []
const expect = (condition, message, detail = '') => {
  if (condition) console.log(`  + ${message}`)
  else {
    failures.push(message)
    console.log(`  - ${message}${detail ? ` — получено: ${detail}` : ''}`)
  }
}

const browser = await chromium.launch(CHROME ? { executablePath: CHROME } : {})
const page = await browser.newPage({ viewport: { width: 1600, height: 900 } })
const consoleErrors = []
page.on('pageerror', (e) => consoleErrors.push(String(e)))
page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text()))

const open = async (step, screen) => {
  await page.click(`.wizard__step:has-text("Ш${step}.")`)
  await page.click(`.topbar button:has-text("${screen}")`)
  await page.waitForTimeout(250)
}

/** Значение поля формы по подписи: форма строится по схеме, поля адресуются ею. */
const fill = async (label, value) => {
  const field = page.locator(`.editor [aria-label="${label}"]`).first()
  await field.waitFor({ state: 'visible', timeout: 5000 })
  const tag = await field.evaluate((el) => el.tagName.toLowerCase())
  if (tag === 'select') await field.selectOption(value)
  else await field.fill(String(value))
}

const save = async (button = 'Создать') => {
  await page.click(`.editor button:has-text("${button}")`)
  await page.waitForTimeout(500)
}

try {
  await page.goto(BASE, { waitUntil: 'networkidle' })

  console.log('Представиться: без автора правка не принимается')
  await page.fill('.author input', 'инженер приёмки')
  expect(await page.locator('.author input').inputValue() === 'инженер приёмки', 'автор задан')

  console.log('\nШ1 · нужды: проект пуст, нужда заводится руками')
  await open(1, 'Нужды стейкхолдеров')
  const emptyNotice = await page.locator('.empty').first().textContent()
  expect(/Нужд пока нет/.test(emptyNotice ?? ''), 'пустой проект честно объявлен пустым', emptyNotice ?? '')

  await page.click('button:has-text("Добавить нужду")')
  await fill('statement', 'Оператор должен получать телеметрию терминалов не реже раза в сутки.')
  await fill('name', 'Оператор сети')
  await fill('role', 'operator')
  await fill('priority', '2')
  await save()
  const needId = await page.locator('td .id').first().textContent()
  expect(needId === 'ND-0001', 'нужда создана через интерфейс и получила ND-0001', needId ?? '')

  console.log('\nШ1 · дефекты прежнего экрана (§2)')
  const roleCell = await page.locator('tbody tr td:nth-child(4)').first().textContent()
  expect(roleCell === 'оператор', 'роль выведена подписью, а не кодом operator', roleCell ?? '')
  const statementCell = page.locator('tbody tr td:nth-child(2)').first()
  const cut = await statementCell.evaluate((el) => el.scrollWidth > el.clientWidth + 1)
  expect(!cut, 'формулировка не обрезана при свободном месте справа')
  const overlap = await page.evaluate(() => {
    const table = document.querySelector('.pane table')
    const side = document.querySelector('.pane--side')
    if (!table || !side) return 'нет элементов'
    const t = table.getBoundingClientRect()
    const s = side.getBoundingClientRect()
    return t.right <= s.left + 1 ? null : `таблица до ${t.right}, панель с ${s.left}`
  })
  expect(overlap === null, 'правая панель не перекрывает колонку таблицы', overlap ?? '')

  console.log('\nШ1 · правка, конфликт версий и отмена действия')
  await page.click('tbody tr')
  await page.waitForTimeout(400)
  await fill('statement', 'Оператор должен получать телеметрию терминалов не реже двух раз в сутки.')
  await save('Сохранить правку')
  const version = await page.locator('.editor__title .secondary').first().textContent()
  expect(/в\. 2/.test(version ?? ''), 'правка сохранена новой версией', version ?? '')

  await page.click('.editor button:has-text("Показать историю")')
  await page.waitForTimeout(300)
  const historyRows = await page.locator('.editor .card table tbody tr').count()
  expect(historyRows === 2, 'история версий показывает обе версии с авторами', String(historyRows))

  await page.click('.editor button:has-text("Отменить действие")')
  await page.waitForTimeout(600)
  const restored = await page.locator('.editor [aria-label="statement"]').inputValue()
  expect(
    restored === 'Оператор должен получать телеметрию терминалов не реже раза в сутки.',
    'отмена действия вернула содержание предыдущей версии',
    restored,
  )

  console.log('\nШ2 · сервис: нужда получает реализацию')
  await open(2, 'Сервисы и профили QoS')
  await page.click('button:has-text("Добавить сервис")')
  await fill('name', 'Сбор телеметрии терминалов')
  await fill('traces_up', 'ND-0001')
  // Профиль качества обязателен ПО КАЖДОМУ классу потребителей (Р9):
  // единый показатель на сервис схема не принимает — потому и подформа.
  await page.click('.editor button:has-text("+ qos_profiles")')
  await page.waitForTimeout(200)
  await page.locator('.editor .subform [aria-label="consumer_class"]').first().selectOption('A_prime')
  await page.click('.editor .subform button:has-text("+ moe")')
  await page.waitForTimeout(200)
  await page.locator('.editor .subform .subform [aria-label="id"]').first().fill('MOE-0001')
  await page.locator('.editor .subform .subform [aria-label="name"]').first().selectOption('delivery_probability_daily')
  await page.locator('.editor .subform .subform [aria-label="target: значение"]').first().fill('0.9')
  await page.locator('.editor .subform .subform [aria-label="target: единица"]').first().fill('1')
  await save()
  const serviceError = await page.locator('.editor .warn').first().textContent().catch(() => null)
  expect(!serviceError, 'сервис принят без замечаний схемы', serviceError ?? '')
  const serviceId = await page.locator('.editor__title .id').first().textContent()
  expect((serviceId ?? '').startsWith('SV-'), 'сервис создан через интерфейс', serviceId ?? '')

  console.log('\nШ3 · требование с показателем и планом верификации')
  await open(3, 'Дерево требований')
  await page.click('button:has-text("Добавить требование")')
  await fill('statement', 'Вероятность доставки сообщения за сутки должна быть не менее 0,9.')
  await fill('level', 'system')
  await fill('category', 'performance')
  await fill('owner', 'инженер приёмки')
  // Требование не бывает сиротой (TZ-COM-002): трассировка вверх — не строка,
  // а запись {ref, consumer_class}, и форма даёт её подформой, как в схеме.
  await page.click('.editor button:has-text("+ traces_up")')
  await page.waitForTimeout(200)
  await page.locator('.editor .subform [aria-label="ref"]').first().fill('ND-0001')
  await save()
  const reqError = await page.locator('.editor .warn').first().textContent().catch(() => null)
  expect(!reqError, 'требование принято без замечаний схемы', reqError ?? '')
  const reqId = await page.locator('.editor__title .id').first().textContent()
  expect((reqId ?? '').startsWith('RQ-'), 'требование создано через интерфейс', reqId ?? '')
  const blockers = await page.locator('.editor .card:has-text("Что мешает базированию") .amber').count()
  expect(blockers > 0, 'форма называет, что мешает базированию, поимённо', String(blockers))

  console.log('\nШ7 · пакет передачи собирается на введённой руками модели')
  await open(7, 'Готовность к точке')
  await page.click('button:has-text("Собрать пакет передачи")')
  await page.waitForTimeout(1500)
  const pkgError = await page.locator('.pane--side .warn').first().textContent().catch(() => null)
  expect(!pkgError, 'пакет собран без отказа', pkgError ?? '')
  const partRows = await page.locator('.pane--side .card:has-text("Пакет передачи") tbody tr').count()
  expect(partRows > 0, 'части пакета перечислены на экране', String(partRows))
  const downloadable = await page.locator('a:has-text("Скачать пакет")').count()
  expect(downloadable === 1, 'пакет можно забрать файлом')

  console.log('\nОшибок консоли: ' + consoleErrors.length)
  expect(consoleErrors.length === 0, 'ошибок в консоли нет', consoleErrors.slice(0, 3).join(' | '))
} finally {
  await browser.close()
}

console.log(
  failures.length === 0
    ? '\nИтог: пустой проект наполняется через интерфейс, пакет получен'
    : `\nИтог: не пройдено ${failures.length}`,
)
process.exit(failures.length === 0 ? 0 : 1)
