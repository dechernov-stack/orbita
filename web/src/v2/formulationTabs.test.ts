// Постановка вкладками (шип 5 §2): семь вкладок по понятиям в порядке
// онтологии, реестр одного вида на вкладку, «Без нужд» — чип, «Покрытие k из
// n» — число вкладки «Нужды». Реестры общие со сценами 3–6: второй копии
// таблицы в scenes.tsx нет.
//
// Сюда же перенесено то, что сторожили прежние тесты экрана постановки
// (coverageEditable, formulation, needsEditable): сила кликом по баллу,
// перенос на сетке, следующий клик у непокрытой нужды, нужда правится там,
// где показана.
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { CoverageMatrix, EntityRow, FactRow, KindSpec, Phase } from './api'
import экран from './formulation.tsx?raw'
import сцены from './scenes.tsx?raw'
import оболочка from './shell.tsx?raw'
import реестрСторон from './registry/stakeholders.tsx?raw'
import реестрНужд from './registry/needs.tsx?raw'
import { ВКЛАДКИ_ПОСТАНОВКИ, строкаКонтекста } from './formulation'
import {
  классНазначен, носителиНужды, нуждыСтороны, силаСтороны, строкиДопущений, сводкаВкладок, сцена4Держит,
  type ДанныеПостановки,
} from './registry/data'
import { Реестр } from './registry/registry'
import { Сила, СеткаВлияния, основанияСтороны, РеестрСторон } from './registry/stakeholders'
import { кудаИдти, РеестрНужд } from './registry/needs'
import { отобрать, type ЧипОтбора } from './ui/chips'
import { ПлотностьКонтекст } from './ui/density'

const запись = (code: string, doc: Record<string, unknown>, extra: Partial<EntityRow> = {}): EntityRow =>
  ({ id: code.toLowerCase(), code, status: 'draft', doc, ...extra })

const стороны = [
  запись('SK-0001', { name: 'Минтранс России', role: 'regulator', influence: 'decides', power: 4 }),
  запись('SK-0007', { name: 'Центр мониторинга', role: 'operator', influence: 'influences' }, { power_proposed: 2 } as Partial<EntityRow>),
  запись('SK-0012', { name: 'Агро-потребители', role: 'consumer', influence: 'informed' }),
]
const нужды = [
  запись('ND-0001', { statement: 'Непрерывный мониторинг', qos_class: 'B′' }, { owned_by: ['sk-0001'], covered_by: ['mg-0001'] }),
  запись('ND-0002', { statement: 'Суверенитет данных', qos_class: { owner: 'Иванов', gate: 'MCR' } }, { owned_by: ['sk-0001'] }),
]
const цели = [запись('MG-0001', { statement: 'Отслеживаемость', year: 2030, measure: { value: 100, unit: 'pct' } })]
const факт = (id: string, mark: 'И' | 'В' | 'П', disposition: string): FactRow =>
  ({ id, code: id, subject: 'проект', predicate: 'принимается на веру', value: `значение ${id}`, unit: null, anchor: 's1', mark, material: 'SD-0001', disposition })
const покрытие: CoverageMatrix = {
  total: 2, covered: 1, summary: '', stakeholders_without_needs: ['SK-0007', 'SK-0012'], other_coverage: {},
  needs: [
    { code: 'ND-0001', statement: 'Непрерывный мониторинг', owner: 'SK-0001', covered: true, gap: null, goals: ['MG-0001'], services: [], expected: 'service', note: null },
    { code: 'ND-0002', statement: 'Суверенитет данных', owner: 'SK-0001', covered: false, gap: 'нет цели и сервиса', goals: [], services: [], expected: 'service', note: null },
  ],
}
const видСтороны = {
  code: 'stakeholder', title: 'стейкхолдер', status_model: '', fields: ['name', 'role', 'influence', 'power'], required: ['name', 'role'], measures: [],
  labels: { name: 'Наименование', role: 'Роль', influence: 'Влияние', power: 'Сила' }, required_at: {}, notes: {}, measure_ops: {},
  enums: { role: ['customer', 'regulator', 'operator', 'consumer'], influence: ['decides', 'influences', 'informed'], attitude: ['supports', 'neutral', 'resists'] },
  enum_labels: {
    role: { customer: 'заказчик', regulator: 'регулятор', operator: 'оператор', consumer: 'потребитель' },
    influence: { decides: 'решает', influences: 'влияет', informed: 'информируется' },
  },
} as unknown as KindSpec
const данные: ДанныеПостановки = {
  стороны, нужды, цели, сервисы: [], ограничения: [],
  факты: [факт('F-1', 'П', 'assumed'), факт('F-2', 'П', 'free'), факт('F-3', 'И', 'adopted'), факт('F-4', 'И', 'assumed')],
  покрытие, применимость: { project: 'PJ-1', note: '', rows: [], by_verdict: { needs_work: 1 }, external_targets: [], charter_boundaries: [] },
  виды: { stakeholder: видСтороны }, читаю: false,
}
const вРежиме = (режим: 'мероприятие' | 'сцена' | 'фаза', узел: ReturnType<typeof createElement>) =>
  renderToStaticMarkup(createElement(ПлотностьКонтекст.Provider, { value: { режим, просторно: false, эксперт: false, кто: 'Иванов И.' } }, узел))

describe('Постановка — семь вкладок по понятиям', () => {
  it('порядок онтологии; «Без нужд» и «Покрытие» — не вкладки', () => {
    expect(ВКЛАДКИ_ПОСТАНОВКИ).toEqual(['стороны', 'нужды', 'цели', 'сервисы', 'ограничения', 'допущения', 'применимость'])
    expect(экран).not.toContain("'без-нужд'")
    expect(экран).not.toContain("'покрытие'")
    expect(экран).toContain('<Вкладки label="постановка"')
    expect(экран).toContain("useВкладка<КлючВкладки>('formulation', 'стороны', ВКЛАДКИ_ПОСТАНОВКИ)")
    expect(оболочка).toContain('<Formulation project={project} фаза={phase}')
  })

  it('числа и маркеры вкладок: долг, красный при держащей сцене 4, подсказки не длиннее 160 знаков', () => {
    const с = сводкаВкладок(данные, false)
    expect(с.стороны).toMatchObject({ count: 3, health: 'debt' })
    expect(с.стороны.hint).toContain('без нужд 2')
    expect(с.нужды).toMatchObject({ count: '1 из 2', health: 'debt' })
    expect(сводкаВкладок(данные, true).нужды.health).toBe('block')
    expect(с.цели.health).toBe('ok')
    expect(с.допущения).toMatchObject({ count: 3, health: 'debt' })
    expect(с.применимость.health).toBe('debt')
    Object.values(с).forEach((в) => expect(в.hint.length).toBeLessThanOrEqual(160))
  })

  it('сцена 4 держит, когда не прожита и её блокирующий выход не выполнен', () => {
    const фаза = (state: 'open' | 'done', passed: boolean) => ({
      project: 'PJ-1', standard: 'NASA-7120', phase: 'Pre-Phase A', current_scene: '4', gates: [], lanes: [],
      scenes: [{ key: '4', title: 'Цели', state, exit: [{ title: 'у нужд есть цели', check: 'x', passed, why: null, blocking: true }] }],
    }) as unknown as Phase
    expect(сцена4Держит(фаза('open', false))).toBe(true)
    expect(сцена4Держит(фаза('open', true))).toBe(false)
    expect(сцена4Держит(фаза('done', false))).toBe(false)
    expect(строкаКонтекста(фаза('open', false))).toBe('PJ-1 · Pre-Phase A · сцена 4 «Цели» идёт')
  })
})

describe('данные постановки считаются по связям, а не по тексту', () => {
  it('носители — связями owns, нужды стороны — по носителям', () => {
    expect(носителиНужды(нужды[0], стороны).map((с) => с.code)).toEqual(['SK-0001'])
    expect(нуждыСтороны(стороны[0], нужды, стороны)).toHaveLength(2)
    expect(нуждыСтороны(стороны[2], нужды, стороны)).toHaveLength(0)
  })
  it('сила: своя оценка, иначе предложение по роли; TBR класса — не назначен', () => {
    expect(силаСтороны(стороны[0])).toEqual({ балл: 4, предложена: false })
    expect(силаСтороны(стороны[1])).toEqual({ балл: 2, предложена: true })
    expect(силаСтороны(стороны[2])).toEqual({ балл: null, предложена: false })
    expect(классНазначен(нужды[0].doc.qos_class)).toBe(true)
    expect(классНазначен(нужды[1].doc.qos_class)).toBe(false)
  })
  it('реестр допущений — по диспозиции: «допущение» при любой помете и всё «П»', () => {
    expect(строкиДопущений(данные.факты).map((ф) => ф.id)).toEqual(['F-1', 'F-2', 'F-4'])
  })
})

describe('чипы отбора: «или» внутри группы, «и» между группами', () => {
  const чипы: ЧипОтбора<EntityRow>[] = [
    { key: 'рег', word: 'регулятор', group: 'роль', test: (с) => с.doc.role === 'regulator' },
    { key: 'опер', word: 'оператор', group: 'роль', test: (с) => с.doc.role === 'operator' },
    { key: 'без', word: 'без нужд', group: 'нужды', test: (с) => нуждыСтороны(с, нужды, стороны).length === 0 },
  ]
  it('складывает', () => {
    expect(отобрать(стороны, чипы, new Set(['рег', 'опер'])).map((с) => с.code)).toEqual(['SK-0001', 'SK-0007'])
    expect(отобрать(стороны, чипы, new Set(['рег', 'опер', 'без'])).map((с) => с.code)).toEqual(['SK-0007'])
    expect(отобрать(стороны, чипы, new Set())).toHaveLength(3)
  })
})

describe('реестр одного вида', () => {
  it('чипы с числом, колонка отметок, действие строки пиктограммой со словом, пустое — словами', () => {
    const html = renderToStaticMarkup(createElement(Реестр<EntityRow>, {
      label: 'реестр сторон', строки: стороны, ключ: (с: EntityRow) => с.code,
      колонки: [{ key: 'код', title: 'Код', cell: (с: EntityRow) => с.code }],
      чипы: [{ key: 'рег', word: 'регулятор', group: 'роль', test: (с: EntityRow) => с.doc.role === 'regulator' },
        { key: 'пусто', word: 'поставщик', group: 'роль', test: (с: EntityRow) => с.doc.role === 'supplier' }],
      карточка: () => null, массово: [{ key: 'a', икон: 'принять', слово: 'подтвердить силу', клавиша: 'A', run: () => undefined }],
      пусто: 'Сторон пока нет.',
    }))
    expect(html).toContain('регулятор <span class="v2-chip__n">1</span>')
    expect(html).not.toContain('поставщик')
    expect(html).toContain('aria-label="выбрать все видимые"')
    expect(html).toContain('aria-label="выбрать SK-0012"')
    expect(html).toContain('title="карточка" aria-label="карточка"')
    expect(html).toContain('выбрано 0')
    const пустой = renderToStaticMarkup(createElement(Реестр<EntityRow>, {
      label: 'реестр целей', строки: [], ключ: (с: EntityRow) => с.code, колонки: [], пусто: 'Целей пока нет.',
    }))
    expect(пустой).toContain('Целей пока нет.')
  })
})

describe('стороны: сила кликом, сетка переносом, карточка вниз', () => {
  it('сила — пять клеток кликом; предложенная — штрихом и «?»', () => {
    const своя = renderToStaticMarkup(createElement(Сила, { с: стороны[0], занята: false, onSet: () => undefined }))
    expect(своя.match(/aria-label="сила \d из 5"/g)).toHaveLength(5)
    expect(своя.match(/v2-power__c--on/g)).toHaveLength(4)
    const предложена = renderToStaticMarkup(createElement(Сила, { с: стороны[1], занята: false, onSet: () => undefined }))
    expect(предложена).toContain('v2-power__c--guess')
    expect(предложена).toContain('предложено по роли: 2 из 5 — нажмите, чтобы подтвердить')
    expect(реестрСторон).toContain("поправить([с.code], () => ({ power: балл }), 'сила стороны')")
  })

  it('сетка: чипы с инициалами, предложенная сила с «?», вне сетки названы', () => {
    const html = renderToStaticMarkup(createElement(СеткаВлияния, { стороны, вид: видСтороны, onMove: () => undefined }))
    expect(html).toContain('>МР</button>')
    expect(html).toContain('v2-stake-chip--proposed')
    expect(html).toContain('ЦМ ?')
    expect(html).toContain('вне сетки: Агро-потребители')
    expect(html).toContain('сила предложена по роли — подтвердите баллом в таблице или перенесите чип кликом')
    expect(реестрСторон).toContain("({ power: балл, influence: влияние }), 'перенос стороны на сетке влияния')")
  })

  it('сетка — не у инженера; «добавить сторону» — только вне сцены 3', () => {
    const у = (режим: 'мероприятие' | 'сцена', onGoScene?: () => void) =>
      вРежиме(режим, createElement(РеестрСторон, { project: 'PJ-1', данные, onChanged: () => undefined, onGoScene }))
    expect(у('сцена', () => undefined)).toContain('aria-label="сетка"')
    expect(у('мероприятие', () => undefined)).not.toContain('aria-label="сетка"')
    expect(у('сцена', () => undefined)).toContain('aria-label="добавить сторону"')
    expect(у('сцена')).not.toContain('aria-label="добавить сторону"')
    expect(у('сцена')).toContain('без нужд <span class="v2-chip__n">2</span>')
    expect(у('сцена')).toContain('title="нужд нет — сцена 3 этим держится">нет</span>')
  })

  it('основания карточки — факты о стороне по предмету; нужда правится там, где показана', () => {
    const ф: FactRow = { ...факт('F-9', 'И', 'adopted'), subject: 'Минтранс России' }
    expect(основанияСтороны(стороны[0], [ф, факт('F-8', 'И', 'free')]).факты.map((х) => х.id)).toEqual(['F-9'])
    expect(реестрСторон).toContain('фактов об этой стороне в поле знаний нет')
    expect(реестрСторон).toContain('aria-label={`формулировка нужды ${н.code}`}')
    expect(реестрСторон).toContain('{ statement: формулировка }')
  })
})

describe('нужды: состояние покрытия и следующий клик', () => {
  it('непокрытая нужда называет, где закрывается разрыв', () => {
    const строка = покрытие.needs[1]
    expect(кудаИдти(строка)).toContain('сценах 4 и 6')
    expect(кудаИдти({ ...строка, owner: null })).toContain('сцене 3')
    expect(кудаИдти({ ...строка, goals: ['MG-1'] })).toContain('сцене 6')
    expect(кудаИдти({ ...строка, services: ['SV-1'] })).toContain('сцене 4')
    expect(кудаИдти(строка)).toContain('Раздать нужды по целям и сервисам')
    const html = вРежиме('сцена', createElement(РеестрНужд, { project: 'PJ-1', данные, onChanged: () => undefined }))
    expect(html).toContain('data-why="следующий-клик"')
    expect(html).toContain('<span class="v2-ok">покрыта</span>')
    expect(html).toContain('TBR — за Иванов')
    expect(реестрНужд).toContain("api.needOwner(project, к, сторона, { author: автор })")
  })
})

describe('сцены 3–6 показывают те же реестры', () => {
  it('таблиц сторон, нужд, целей, сервисов и ограничений в scenes.tsx нет', () => {
    for (const имя of ['РеестрСторон', 'РеестрНужд', 'РеестрЦелей', 'РеестрОграничений', 'РеестрСервисов']) {
      expect(сцены).toContain(`<${имя} project={project} данные={данные}`)
    }
    // Шип 5 §6: и критерии оценки миссии (сцена 4, З-08) — тем же реестром: таблиц нет вовсе.
    expect(сцены.match(/<table/g)).toBeNull()
    expect(сцены).toContain('<Реестр label="критерии оценки миссии"')
    expect(сцены).not.toContain('ПравкаСтроки')
    expect(сцены).toContain('отбор="tbr"')
  })
})
