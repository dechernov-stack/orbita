// Т-1, приёмка: дубль ID невозможен (кейс круга 3 — ребёнок и родитель в
// одной группе давал строку дважды); конфигуратор равен факту таблицы по
// построению; счётчики разрывов сходятся с данными; пред./след. ходят по
// текущей выборке.
import { describe, expect, it } from 'vitest'
import type { RequirementRow } from '../api/types'
import {
  buildItems,
  defaultColumns,
  flatRows,
  gapCounters,
  hasGap,
  UNASSIGNED,
  visibleColumns,
  type ViewOptions,
} from './requirementsView'

function row(partial: Partial<RequirementRow> & { id: string }): RequirementRow {
  return {
    depth: 0,
    hasChildren: false,
    statement: `Изделие должно выполнять условие ${partial.id}.`,
    category: 'performance',
    level: 'system',
    status: 'Baseline',
    condition: null,
    budget: null,
    budgetOverrun: false,
    verificationState: 'запланирована',
    method: 'test',
    approach: null,
    planIssues: [],
    sources: ['SV-0001'],
    allocatedTo: [],
    kind: 'text',
    rationale: null,
    version: '1',
    owner: null,
    origin: 'manual',
    parentId: null,
    carrierName: null,
    recalcAfterBaseline: false,
    changedAfterApproval: false,
    ...partial,
  }
}

const opts = (over: Partial<ViewOptions> = {}): ViewOptions => ({
  form: 'tree',
  grouping: 'carrier',
  collapsed: new Set(),
  gap: null,
  search: '',
  sort: null,
  ...over,
})

// Кейс круга 3: родитель и ребёнок на ОДНОМ носителе — ребёнок обязан выйти
// одной строкой с отступом, не двумя (в группе и в ветке).
const fixture: RequirementRow[] = [
  row({ id: 'RQ-0101' }),
  row({ id: 'RQ-0102', recalcAfterBaseline: true }),
  row({ id: 'RQ-0111', allocatedTo: ['CM-0103'], carrierName: 'Полезная нагрузка', hasChildren: true }),
  row({ id: 'RQ-0112', allocatedTo: ['CM-0103'], carrierName: 'Полезная нагрузка', parentId: 'RQ-0111' }),
  row({ id: 'RQ-0113', allocatedTo: ['CM-0106'], carrierName: 'Станция', parentId: 'RQ-0111', method: null }),
  row({ id: 'RQ-0114', allocatedTo: ['IF-0101'], carrierName: null, changedAfterApproval: true }),
]

describe('дубль ID невозможен', () => {
  const combos: Array<Partial<ViewOptions>> = [
    {},
    { form: 'flat' },
    { grouping: null },
    { form: 'flat', grouping: null },
    { grouping: 'level' },
    { grouping: 'status' },
    { grouping: 'owner' },
    { sort: { key: 'mop', dir: 'desc' } },
  ]
  combos.forEach((over, i) => {
    it(`вариант ${i + 1}: каждая строка ровно один раз`, () => {
      const ids = flatRows(buildItems(fixture, opts(over))).map((r) => r.id)
      expect(ids.length).toBe(fixture.length)
      expect(new Set(ids).size).toBe(ids.length)
    })
  })

  it('ребёнок на носителе родителя — одной строкой с отступом', () => {
    const items = buildItems(fixture, opts())
    const inCarrier = items.filter(
      (i) => i.type === 'row' && i.row.allocatedTo[0] === 'CM-0103',
    ) as Array<Extract<(typeof items)[number], { type: 'row' }>>
    expect(inCarrier.map((i) => i.row.id)).toEqual(['RQ-0111', 'RQ-0112'])
    expect(inCarrier[1].depth).toBe(1)
  })

  it('родитель в другой группе — строка идёт корнем группы, не пропадает', () => {
    const ids = flatRows(buildItems(fixture, opts())).map((r) => r.id)
    expect(ids).toContain('RQ-0113')
  })
})

describe('группировка по носителю', () => {
  it('«Не распределено» — первая группа', () => {
    const items = buildItems(fixture, opts())
    const first = items[0]
    expect(first.type).toBe('group')
    if (first.type === 'group') expect(first.key).toBe(UNASSIGNED)
  })

  it('схлопнутая группа прячет строки, счётчик остаётся', () => {
    const items = buildItems(fixture, opts({ collapsed: new Set(['CM-0103']) }))
    const grp = items.find((i) => i.type === 'group' && i.key === 'CM-0103')
    expect(grp && grp.type === 'group' && grp.count).toBe(2)
    expect(flatRows(items).some((r) => r.id === 'RQ-0112')).toBe(false)
  })
})

describe('конфигуратор равен факту таблицы', () => {
  it('оба читают один массив: порядок сохранён, выключенные изъяты', () => {
    const columns = defaultColumns()
    columns.find((c) => c.key === 'carrier')!.on = false
    const visible = visibleColumns(columns)
    expect(visible.every((c) => c.on)).toBe(true)
    expect(visible.map((c) => c.key)).toEqual(
      columns.filter((c) => c.on).map((c) => c.key),
    )
    // порядок видимых — подпоследовательность порядка конфигуратора
    const order = columns.map((c) => c.key)
    const indexes = visible.map((c) => order.indexOf(c.key))
    expect([...indexes].sort((a, b) => a - b)).toEqual(indexes)
  })

  it('состав по умолчанию — эталонный: семь колонок таблицы', () => {
    expect(visibleColumns(defaultColumns()).map((c) => c.key)).toEqual([
      'id', 'statement', 'kind', 'mop', 'status', 'carrier', 'verification',
    ])
  })
})

describe('разрывы', () => {
  it('счётчики сходятся с данными строк', () => {
    const counters = gapCounters(fixture)
    expect(counters.no_carrier).toBe(fixture.filter((r) => r.allocatedTo.length === 0).length)
    expect(counters.no_verification).toBe(1)
    expect(counters.recalc).toBe(1)
    expect(counters.changed).toBe(1)
  })

  it('фильтр разрыва оставляет только строки с этим разрывом', () => {
    const ids = flatRows(buildItems(fixture, opts({ gap: 'no_carrier' }))).map((r) => r.id)
    expect(ids.every((id) => hasGap(fixture.find((r) => r.id === id)!, 'no_carrier'))).toBe(true)
    expect(ids.length).toBe(2)
  })
})

describe('пред./след. по текущей выборке', () => {
  it('листание ходит по отфильтрованному порядку показа, не по всей базе', () => {
    const items = buildItems(fixture, opts({ search: 'RQ-011' }))
    const seq = flatRows(items).map((r) => r.id)
    // группа CM-0103 (две строки) идёт первой, дальше группы по одной
    expect(seq).toEqual(['RQ-0111', 'RQ-0112', 'RQ-0113', 'RQ-0114'])
    const at = seq.indexOf('RQ-0112')
    expect(seq[at - 1]).toBe('RQ-0111')
    expect(seq[at + 1]).toBe('RQ-0113')
  })
})
