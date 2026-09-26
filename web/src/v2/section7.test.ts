// Шип 5 §7 — остальное тем же языком: точки, риски, концепция, паспорт.
//
// Точки: строки готовности «маркер · критерий · почему · к месту», держащие —
// первыми; замечания обзора — таблицей в полосах по вопросам экспертизы.
// Риски: полосы по владельцу, чипы значениями из данных, карточка §1.3,
// закрытие решением. Концепция: вкладки «Состав · Варианты · Базовая ·
// Модели», узел — карточкой с гранями анкеты. Паспорт — карточка проекта.
// На новые поверхности — «один label — один контрол» и плотность.
import { describe, expect, it } from 'vitest'
import { createElement, type ReactElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import type { ComponentCard, Condition, EntityRow, Finding, KindSpec, Passport, RiskRow } from './api'
import { ЗамечанияПолосами, поПорядку } from './points'
import { ГраниРиска, владелецРиска, порядокРисков, чипыРисков } from './risks'
import { ВКЛАДКИ_КОНЦЕПЦИИ, вкладкиКонцепции } from './concept'
import { КарточкаУзла, разрывыПоТочкам } from './composition'
import type { СоставЭкрана } from './composition.api'
import { ПОЛЯ_ПАСПОРТА, записьПаспорта } from './passport'
import { Карточка } from './ui/objectcard'
import { Реестр } from './registry/registry'
import { ПлотностьКонтекст, type Плотность } from './ui/density'
import { нарушенияПодписей } from './test-support/oneControlPerLabel'

const в = (плотность: Плотность, узел: ReactElement) =>
  renderToStaticMarkup(createElement(ПлотностьКонтекст.Provider, { value: плотность }, узел))
const СЦЕНА: Плотность = { режим: 'сцена', просторно: false, эксперт: false, кто: 'Чернов Д.' }

describe('точки: готовность строками, замечания полосами', () => {
  const у = (check: string, passed: boolean, blocking?: boolean): Condition => ({ check, title: `условие ${check}`, passed, blocking, why: passed ? null : 'почему' })

  it('держащие точку — первыми, пометы — за ними, выполненные — в конце', () => {
    const порядок = поПорядку([у('выполнено', true), у('помета', false, false), у('держит', false), у('держит-2', false, true)])
    expect(порядок.map((х) => х.check)).toEqual(['держит', 'держит-2', 'помета', 'выполнено'])
  })

  const з = (code: string, question: string | null, status: string): Finding => ({
    code, text: `замечание ${code}`, scene: '7', gate: 'internal_review', status, author: 'Чернов Д.', kind: 'finding', question,
    closed_by: status === 'closed' ? 'Чернов Д.' : null,
  })

  it('замечания — таблица в полосах по вопросам экспертизы; без вопроса — своя полоса', () => {
    const html = renderToStaticMarkup(createElement(ЗамечанияПолосами, {
      замечания: [з('F-1', 'Назван ли базовый вариант?', 'open'), з('F-2', null, 'closed'), з('F-3', 'Назван ли базовый вариант?', 'closed')],
      занято: false, onClose: () => undefined,
    }))
    expect(html).toContain('Назван ли базовый вариант?')
    expect(html).toContain('без вопроса экспертизы')
    expect(html).toContain('замечаний 2 · открытых 1')
    expect(html).toContain('<th>Возврат в сцену</th>')
    // Группа — полосой над таблицей, не строкой с colSpan внутри tbody.
    expect(html).not.toContain('colspan')
    // Закрыть — пиктограммой со словом только у открытого.
    expect(html.match(/aria-label="закрыть замечание"/g)?.length).toBe(1)
  })

  it('замечаний нет — сказано словами', () => {
    expect(renderToStaticMarkup(createElement(ЗамечанияПолосами, { замечания: [], занято: false, onClose: () => undefined }))).toContain('замечаний нет')
  })
})

const риск = (code: string, owner: string, extra: Partial<RiskRow> = {}): RiskRow => ({
  code, statement: `риск ${code}`, category: 'technical', probability: 3, impact: 2, level: 6, strategy: 'mitigate', owner,
  due_point: 'MCR', due_date: '2026-09-20', status: 'open', measures: '', condition: '', event: '', consequence: '', refs: [],
  resolution: '', closed_by: '', closed_at: '', reopen_reason: '', version: 1, holds: [], ...extra,
})

const видРиска: KindSpec = {
  code: 'risk', title: 'риск', status_model: 'open|closed',
  fields: ['statement', 'cec', 'category', 'probability', 'impact', 'strategy', 'measures', 'owner', 'due_point', 'refs'],
  required: [], measures: [],
  labels: { statement: 'Формулировка', cec: 'Условие · событие · последствие', category: 'Категория', probability: 'Вероятность', impact: 'Последствия', strategy: 'Стратегия', measures: 'Меры', owner: 'Владелец', due_point: 'Срок-точка', refs: 'Ссылки' },
  required_at: {},
  enums: { category: ['technical', 'cost'], strategy: ['mitigate', 'accept'] },
  enum_labels: { category: { technical: 'техническая', cost: 'стоимость' }, strategy: { mitigate: 'снизить', accept: 'принять' } },
  notes: {}, measure_ops: {},
  widgets: { statement: 'str', cec: 'object', category: 'enum', probability: 'number', impact: 'number', strategy: 'enum', measures: 'text', owner: 'ref', due_point: 'ref', refs: 'refs' },
  ref_kinds: { owner: ['account'], due_point: ['gate'], refs: ['component', 'scene'] },
}

describe('риски: полосы по владельцу, чипы из данных, карточка §1.3', () => {
  it('полоса — владелец; без владельца — своя полоса словами', () => {
    expect(владелецРиска(риск('RI-1', 'Иванов И.'))).toBe('Иванов И.')
    expect(владелецРиска(риск('RI-2', '—'))).toBe('владелец не назначен')
    expect(владелецРиска(риск('RI-3', ''))).toBe('владелец не назначен')
  })

  it('порядок: по критичности (уровень считает сервер) или по сроку-точке', () => {
    const риски = [риск('RI-1', 'А', { level: 6, due_date: '2026-10-02' }), риск('RI-2', 'А', { level: 16, due_date: '2026-11-01' })]
    expect(порядокРисков(риски, 'level').map((р) => р.code)).toEqual(['RI-2', 'RI-1'])
    expect(порядокРисков(риски, 'due').map((р) => р.code)).toEqual(['RI-1', 'RI-2'])
  })

  it('чипы — значениями из данных: состояния, точки, которые риски держат, категории словами истины', () => {
    const чипы = чипыРисков([риск('RI-1', 'А', { holds: ['MCR'] }), риск('RI-2', 'Б', { category: 'cost', status: 'closed' })],
      (п, з) => видРиска.enum_labels[п]?.[з] ?? з)
    const слова = чипы.map((ч) => ч.word)
    expect(слова).toEqual(['открытые', 'закрытые', 'держат MCR', 'без срока-точки', 'ключевые ≥ 12', 'стоимость', 'техническая'])
    expect(чипы.find((ч) => ч.key === 'точка:MCR')?.test(риск('RI-9', 'А', { holds: ['MCR'] }))).toBe(true)
  })

  it('реестр полосами: одна группа раскрыта сразу, несколько — свёрнуты с «раскрыть все»', () => {
    const реестр = (строки: RiskRow[]) => renderToStaticMarkup(createElement(Реестр<RiskRow>, {
      label: 'реестр рисков', строки, ключ: (р) => р.code, колонки: [{ key: 'code', title: 'Код', cell: (р) => р.code }],
      пусто: 'рисков нет', полосы: { предмет: владелецРиска, счёт: (rr) => `рисков ${rr.length}` },
    }))
    const одна = реестр([риск('RI-1', '—'), риск('RI-2', '—')])
    expect(одна).toContain('владелец не назначен')
    expect(одна).toContain('рисков 2')
    expect(одна).toContain('<td>RI-1</td>')
    const две = реестр([риск('RI-1', 'Иванов И.'), риск('RI-2', 'Петров П.')])
    expect(две).toContain('раскрыть все (2)')
    expect(две).not.toContain('<td>RI-1</td>')
    expect(две).not.toContain('colspan')
  })

  const карточкаРиска = (плотность: Плотность, р: RiskRow) => в(плотность, createElement(Карточка, {
    project: 'PJ', row: { id: 'r1', code: р.code, status: 'draft', version: 1, doc: { statement: р.statement, category: р.category, strategy: р.strategy } } as EntityRow,
    spec: видРиска, заголовок: р.statement, скрыть: ['cec', 'probability', 'impact', 'owner', 'refs'],
    extra: createElement(ГраниРиска, {
      р, spec: видРиска, правитьРиск: () => undefined, узлы: [], сцены: [{ key: '11', title: 'Риски' }], учётки: [],
      project: 'PJ', занято: false, onЗакрыть: () => undefined, onВернуть: () => undefined,
    }),
  }))

  it('карточка: грани истины и грани риска — шкала кликом, три строки CEC, закрыть решением', () => {
    const html = карточкаРиска(СЦЕНА, риск('RI-1', 'Иванов И.'))
    expect(html).toContain('aria-label="вероятность риска RI-1 кликом"')
    expect(html).toContain('aria-label="влияние риска RI-1 кликом"')
    expect(html).toContain('aria-label="условие риска RI-1"')
    expect(html).toContain('aria-label="закрыть решением"')
    // Стратегия — селектом русскими метками истины.
    expect(html).toContain('<option value="mitigate" selected="">снизить</option>')
    expect(нарушенияПодписей(html)).toEqual([])
  })

  it('закрытый риск: оценка заперта, решение показано, вернуть — с причиной', () => {
    const html = карточкаРиска(СЦЕНА, риск('RI-2', 'Иванов И.', { status: 'closed', resolution: 'ECC принят', closed_by: 'Чернов Д.' }))
    expect(html).toContain('решение: ECC принят')
    expect(html).toContain('aria-label="вернуть в открытые с причиной"')
    expect(html).not.toContain('aria-label="закрыть решением"')
  })

  it('плотность: одна колонка граней у инженера, две у СИ и РП, три «просторно»', () => {
    const р = риск('RI-1', 'Иванов И.')
    expect(карточкаРиска({ ...СЦЕНА, режим: 'мероприятие' }, р)).toContain('v2-object__facets--1')
    expect(карточкаРиска(СЦЕНА, р)).toContain('v2-object__facets--2')
    expect(карточкаРиска({ ...СЦЕНА, режим: 'фаза', просторно: true }, р)).toContain('v2-object__facets--3')
  })
})

describe('концепция: вкладки и карточка узла с анкетой', () => {
  it('вкладки по порядку задания; маркер и слово долга — из прочитанного', () => {
    expect(ВКЛАДКИ_КОНЦЕПЦИИ).toEqual(['состав', 'варианты', 'базовая', 'модели'])
    const мало = вкладкиКонцепции({ узлов: 49, вариантов: 1, базовая: false, моделей: 0 })
    expect(мало.map((т) => т.word)).toEqual(['Состав', 'Варианты', 'Базовая', 'Модели'])
    expect(мало.find((т) => т.key === 'варианты')?.health).toBe('debt')
    expect(мало.find((т) => т.key === 'базовая')?.hint).toContain('сцена 7 держится')
    const полно = вкладкиКонцепции({ узлов: 49, вариантов: 3, базовая: true, моделей: 2 })
    expect(полно.find((т) => т.key === 'варианты')?.health).toBe('ok')
    expect(полно.find((т) => т.key === 'базовая')?.health).toBe('ok')
    // Подсказки вкладок — не простыни (сторож 8(e): не длиннее 160 знаков).
    expect([...мало, ...полно].every((т) => т.hint.length <= 160)).toBe(true)
  })

  const видУзла: KindSpec = {
    code: 'component', title: 'узел состава', status_model: 'draft',
    fields: ['code', 'name', 'parent', 'level', 'nature', 'kind', 'applicability'],
    required: [], measures: [],
    labels: { code: 'Код', name: 'Наименование', parent: 'Родитель', level: 'Уровень', nature: 'Род', kind: 'Вид', applicability: 'Применимость' },
    required_at: {}, enums: { nature: ['node', 'behaviour'], kind: ['system', 'element'], applicability: ['applied', 'not_applicable'] },
    enum_labels: { nature: { node: 'узел (железо)', behaviour: 'поведение (ПО)' } },
    notes: {}, measure_ops: {},
    widgets: { code: 'str', name: 'str', parent: 'ref', level: 'number', nature: 'enum', kind: 'enum', applicability: 'enum' },
    ref_kinds: { parent: ['component'] },
  }
  const видВеличины: KindSpec = { ...видУзла, code: 'parameter', title: 'значение параметра узла', measure_ops: { '<=': '≤', '=': '=' } }
  const состав = {
    корни: [], всего: 1, вариант: '', точка: { key: 'MCR', title: 'MCR' },
    величины: [{ code: 'SC.mass', узел: 'SC', key: 'mass', measure: { op: '<=', value: 80, unit: 'kg' }, required_to: 'MCR' }],
    анкета: { SC: [{ key: 'mass', name: 'масса сухая', unit: 'kg', required_to: 'MCR' }, { key: 'power', name: 'мощность', unit: 'W', required_to: 'SRR' }] },
    кратность: {}, словоВида: { system: 'система', element: 'элемент' }, метки: {}, значения: {}, перечни: {}, кратностьПоле: null,
    знаки: {}, единицы: { kg: 'кг', W: 'Вт' }, каркас: null,
    записи: { SC: { id: 'c1', code: 'SC', status: 'draft', version: 1, doc: { name: 'Космический аппарат', kind: 'element', nature: 'node' } } },
    вид: видУзла, видВеличины,
  } as СоставЭкрана
  const узел = { code: 'SC', name: 'Космический аппарат', level: 2, nature: 'node', kind: 'element', parent: null, external: false, template: '', applicability: '', deviation: '', дети: [] }

  it('карточка узла: грани истины, слова вида — с полки, грани анкеты с точкой и заполненностью', () => {
    const html = в(СЦЕНА, createElement(КарточкаУзла, { project: 'PJ', узел, состав, onSaved: () => undefined, onClose: () => undefined }))
    expect(html).toContain('aria-label="карточка SC"')
    expect(html).toContain('<option value="element" selected="">элемент</option>')
    expect(html).toContain('Анкета узла · заполнено 1 из 2')
    expect(html).toContain('масса сухая, кг')
    expect(html).toContain('величина анкеты · нужна к MCR')
    expect(html).toContain('не заполнено · нужна к SRR')
    // Структуру правит дерево: кода, родителя, уровня и применимости в гранях нет.
    expect(html).not.toContain('>Родитель</label>')
    expect(html).not.toContain('>Применимость</label>')
    expect(нарушенияПодписей(html)).toEqual([])
  })
})

describe('ступень узла: разрывы по точкам', () => {
  it('пустые грани — по точкам в порядке лестницы сервера, именами граней', () => {
    const ступень: ComponentCard = {
      code: 'SC', name: 'КА', nature: 'node', level: 2, gate: 'internal_review',
      facets: [
        { key: 'identity', title: 'Идентичность', required_to: 'MCR', expected: null, lines: [{ what: 'КА', ref: null }] },
        { key: 'functions', title: 'Функции', required_to: 'SRR', expected: null, lines: [] },
        { key: 'models', title: 'Модели', required_to: 'MCR', expected: null, lines: [] },
        { key: 'budgets', title: 'Бюджеты', required_to: 'SDR', expected: null, lines: [] },
      ],
      gaps: [
        { facet: 'functions', gate: 'SRR', what: 'грань «Функции» пуста' },
        { facet: 'budgets', gate: 'SDR', what: 'грань «Бюджеты» пуста' },
        { facet: 'models', gate: 'MCR', what: 'грань «Модели» пуста' },
      ],
    }
    expect(разрывыПоТочкам(ступень)).toEqual([['MCR', ['Модели']], ['SRR', ['Функции']], ['SDR', ['Бюджеты']]])
  })
})

describe('паспорт — карточка проекта §1.3', () => {
  const видПроекта: KindSpec = {
    code: 'project', title: 'проект', status_model: 'draft',
    fields: ['name', 'mission_class', 'manager', 'phase_current', 'group', 'example', 'knowledge_v2', 'standard', 'phase_template'],
    required: [], measures: [],
    labels: { name: 'Наименование', mission_class: 'Класс миссии', manager: 'Руководитель', phase_current: 'Текущая фаза', group: 'Группа', example: 'Пример', knowledge_v2: 'Знания v2', standard: 'Стандарт', phase_template: 'Шаблон фазы' },
    required_at: {}, enums: { standard: ['NASA-7120'], group: ['work', 'example'] },
    enum_labels: { standard: { 'NASA-7120': 'NASA (NPR 7120.5)' } },
    notes: { name: 'правится в паспорте проекта (карточка), не только при создании' }, measure_ops: {},
    widgets: { name: 'str', mission_class: 'ref', manager: 'ref', phase_current: 'ref', group: 'enum', example: 'bool', knowledge_v2: 'bool', standard: 'enum', phase_template: 'ref' },
    ref_kinds: { mission_class: ['mission_class'], manager: ['account'] },
  }
  const паспорт = {
    code: 'PJ', version: 3, updated_at: '2026-09-21', updated_by: 'Чернов Д.', name: 'ПМИ-7', mission_class: 'MC-9001',
    manager: 'Чернов Д.', standard: 'NASA-7120', phase_current: 'pre-phase-a', phase_template: '', labels: {}, standards: [], mission_classes: [], gates: [],
  } as unknown as Passport
  const запись: EntityRow = { id: 'p1', code: 'PJ', status: 'draft', version: 3, doc: { name: 'ПМИ-7', lead: 'Чернов Д.', knowledge_v2: true } }

  it('запись карточки: значения паспорта — как их нормализует сервер, прочие поля записи — как есть', () => {
    const р = записьПаспорта(запись, паспорт)
    expect(р.doc.manager).toBe('Чернов Д.')
    expect(р.doc.phase_current).toBe('pre-phase-a')
    expect(р.doc.knowledge_v2).toBe(true)
    expect(ПОЛЯ_ПАСПОРТА).toEqual(['name', 'mission_class', 'manager', 'standard'])
  })

  it('все грани сразу, свои контролы у класса миссии и руководителя, прочее — только чтение', () => {
    const толькоЧтение = видПроекта.fields.filter((п) => !(ПОЛЯ_ПАСПОРТА as readonly string[]).includes(п))
    const html = в(СЦЕНА, createElement(Карточка, {
      project: 'PJ', row: записьПаспорта(запись, паспорт), spec: видПроекта, всеСразу: true, толькоЧтение,
      сохранитьПоле: () => Promise.resolve({ version: 4, changed: 1 }),
      свои: {
        mission_class: ({ id }) => createElement('input', { id, 'data-свой': 'класс' }),
        manager: ({ id }) => createElement('input', { id, 'data-свой': 'руководитель' }),
      },
    }))
    expect(html).not.toContain('ещё ')
    expect(html).toContain('data-свой="класс"')
    expect(html).toContain('data-свой="руководитель"')
    expect(html).toContain('<div class="v2-facet__ro" id="v2-f-knowledge_v2">да</div>')
    expect(html).toContain('<option value="NASA-7120" selected="">NASA (NPR 7120.5)</option>')
    expect(нарушенияПодписей(html)).toEqual([])
  })
})

describe('«один label — один контрол»: сторож ловит нарушение', () => {
  it('две кнопки под одной подписью — отказ; подпись с for и контролом внутри — отказ', () => {
    expect(нарушенияПодписей('<label>шкала<button>1</button><button>2</button></label>')).toHaveLength(1)
    expect(нарушенияПодписей('<label for="x">поле<input id="x"/></label>')).toHaveLength(1)
    expect(нарушенияПодписей('<label>поле<input/></label><label for="y">другое</label><input id="y"/>')).toEqual([])
  })
})
