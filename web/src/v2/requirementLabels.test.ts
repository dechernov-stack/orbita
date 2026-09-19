// Реестр требований говорит человеческими словами, а работа сцены 8 делается
// в карточке.
//
// Истина 19.09 (`field_rules.enum_labels`): «русские значения перечислений —
// только из enum_labels; значение без метки на экране — сторож». Журнал ПМИ-7:
// З-10 — в карточке нет пикера носителя; З-11 — значение уровня стояло в
// категории; З-13 — помета линта без текста. Проверяется устройство: ни одного
// русского значения перечисления и ни одного списка кодов в коде экрана.
import { describe, expect, it } from 'vitest'
import экран from './requirements.tsx?raw'
import апи from './api.ts?raw'

describe('экран требований — метки и работа сцены 8', () => {
  it('значения перечислений берутся с сервера, а не из кода экрана', () => {
    expect(экран).toContain("api.kind('requirement')")
    expect(экран).toContain('вид?.enum_labels[поле]?.[значение]')
    expect(апи).toContain('enum_labels: Record<string, Record<string, string>>')
    // Прежние списки русских имён («проектное», «Всегда») — из экрана вон:
    // истина назвала их сама, вторая копия разошлась бы молча.
    expect(экран).not.toContain("title: 'проектное'")
    expect(экран).not.toContain("title: 'Всегда'")
    // Ключи форм EARS — коды истины: прежнее «always» не совпадало с записью.
    expect(экран).toContain('ubiquitous:')
    expect(экран).not.toContain("useState('always')")
  })

  it('статус, уровень, категория и шаблон показываются словами', () => {
    expect(экран).toContain("схема.метка('status', т.status)")
    expect(экран).toContain("схема.метка('level', т.level)")
    expect(экран).toContain("схема.метка('category', т.category)")
    expect(экран).toContain("схема.метка('ears_pattern', т.ears)")
    // Имя поля — тоже из истины: код поля на экране запрещён.
    expect(экран).toContain("схема.имя('category')")
    // Категории может не быть до базирования — это говорится словами.
    expect(экран).toContain('не задана — к базированию')
  })

  it('помета линта называет правило и что не так (З-13)', () => {
    expect(экран).toContain('{т.notes[0].rule} · {т.notes[0].what}')
    expect(экран).toContain('и ещё {т.notes.length - 1}')
  })

  it('карточка даёт пикер носителя и поля своей стадии (З-10)', () => {
    // Поля не списком в коде, а по стадии из истины: baseline и точки.
    const где = экран.indexOf('const поляСтадии')
    expect(где).toBeGreaterThan(0)
    const кусок = экран.slice(где, где + 420)
    expect(кусок).toContain("стадия.startsWith('baseline')")
    expect(кусок).toContain('схема.стадия(поле)')
    // Носитель — выбором из носителей своей природы, а не вводом кода руками.
    expect(экран).toContain('НОСИТЕЛЬ[т.level]?.виды')
    expect(экран).toContain('носители.map((н) => <option')
    // Величина — тройкой оператор · значение · единица.
    expect(экран).toContain('function Величина')
    expect(экран).toContain('measure.unit')
  })

  it('правка уезжает одним PATCH и говорит итог словами', () => {
    expect(экран).toContain('api.patchEntity(project, т.code, { title: заголовок, statement: формулировка, ...правки }')
    expect(апи).toContain('changed: number; note?: string')
    // «Изменено 0» больше не молчит: владелец 19.09 — «показатель не
    // сохраняется», а сервер отвечал 200 и changed: 0.
    expect(экран).toContain('ничего не изменилось — поля те же')
    expect(экран).toContain('сохранено, версия')
  })

  it('единица показателя выбирается из справочника, а половина величины не сохраняется молча', () => {
    // Владелец 19.09: «Единиц измерения нет — блок». Перечня единиц в коде
    // экрана нет и быть не может — их ведёт справочник (полка LIB).
    expect(экран).toContain('api.units()')
    expect(экран).toContain('единицы.map((е) => <option key={е.code} value={е.code}>{е.label}</option>)')
    expect(экран).toContain('вне справочника')
    expect(экран).toContain('справочник единиц пуст')
    // Число без единицы называется словами и держит кнопку.
    expect(экран).toContain('без единицы — выберите единицу справочника')
    expect(экран).toContain('disabled={занято || !формулировка.trim() || неполна !== null}')
    // Оператор — код истины («>=»), знак только на экране.
    expect(экран).toContain('операторы={схема.вид.measure_ops}')
    expect(экран).not.toContain("value={кодОп === '≥'")
  })

  it('примечание истины стоит рядом с полем', () => {
    // «Показатель · baseline|tbr · обязателен для performance» — иначе
    // необязательное поле выглядит долгом (владелец 19.09).
    expect(экран).toContain('const примечание = схема.примечание(поле)')
    expect(апи).toContain('notes: Record<string, string>')
  })
})
