// Т-1: модель реестра требований БЕЗ DOM — группировка, дерево, фильтры,
// колонки. Один источник состояния: таблица и конфигуратор читают ОДИН
// массив колонок, поэтому рассинхрон невозможен по построению (и это
// закреплено тестом). Дубль строки невозможен: каждая строка попадает
// ровно в один обход (visited-страж закреплён тестом на кейсе круга 3).
import type { RequirementRow } from '../api/types'

export interface ColumnState {
  key: string
  on: boolean
}

/** Колонки реестра — порядок таблицы эталона; подписи — конфигуратора. */
export const COLUMN_LABELS: Record<string, string> = {
  id: 'ID',
  statement: 'Формулировка',
  kind: 'Вид требования',
  mop: 'Показатель',
  status: 'Статус',
  carrier: 'Носитель (элемент/интерфейс)',
  verification: 'Верификация (В)',
  parent: 'Родитель',
  category: 'Категория',
  level: 'Уровень',
  version: 'Версия',
  owner: 'Владелец',
  origin: 'Происхождение',
}

export function defaultColumns(): ColumnState[] {
  return [
    { key: 'id', on: true },
    { key: 'statement', on: true },
    { key: 'kind', on: true },
    { key: 'mop', on: true },
    { key: 'status', on: true },
    { key: 'carrier', on: true },
    { key: 'verification', on: true },
    { key: 'parent', on: false },
    { key: 'category', on: false },
    { key: 'level', on: false },
    { key: 'version', on: false },
    { key: 'owner', on: false },
    { key: 'origin', on: false },
  ]
}

/** Таблица и конфигуратор зовут ТОЛЬКО эту функцию — единый источник. */
export function visibleColumns(columns: ColumnState[]): ColumnState[] {
  return columns.filter((c) => c.on)
}

// Разрывы стратифицированы по уровням (РЕШЕНИЕ-НОСИТЕЛЬ-УРОВНИ): флаги
// носителя и нужды приходят С СЕРВЕРА — клиент их семантику не вычисляет.
export type GapKey = 'no_carrier' | 'no_need' | 'no_verification' | 'recalc' | 'changed' | 'no_acceptance' | 'conflict'

export const GAP_LABELS: Record<GapKey, string> = {
  no_carrier: 'Без носителя (системные)',
  no_need: 'Без нужды (проектные)',
  no_verification: 'Без верификации',
  recalc: 'Пересчитан после базирования',
  changed: 'Изменено после утверждения',
  no_acceptance: 'Без критерия приёмки',
  conflict: 'Противоречие не разрешено',
}

export function hasGap(row: RequirementRow, gap: GapKey): boolean {
  switch (gap) {
    case 'no_carrier':
      return row.noCarrierGap
    case 'no_need':
      return row.noNeedGap
    case 'no_verification':
      return row.method === null
    case 'recalc':
      return row.recalcAfterBaseline
    case 'changed':
      return row.changedAfterApproval
    case 'no_acceptance':
      return row.noAcceptanceGap === true
    case 'conflict':
      return row.conflictOpen === true
  }
}

export function gapCounters(rows: RequirementRow[]): Record<GapKey, number> {
  const counters: Record<GapKey, number> = {
    no_carrier: 0,
    no_need: 0,
    no_verification: 0,
    recalc: 0,
    changed: 0,
    no_acceptance: 0,
    conflict: 0,
  }
  rows.forEach((r) => {
    (Object.keys(counters) as GapKey[]).forEach((g) => {
      if (hasGap(r, g)) counters[g] += 1
    })
  })
  return counters
}

export type GroupKey = 'carrier' | 'level' | 'status' | 'owner'
/** Группа «Не распределено» умерла (решение): проектные держатся за нужды,
 * системные без носителя — настоящий разрыв со своей группой. */
export const PROJECT_GROUP = '__project__'
export const NO_CARRIER_GROUP = '__no_carrier__'

export interface SortState {
  key: string
  dir: 'asc' | 'desc'
}

export interface ViewOptions {
  form: 'tree' | 'flat'
  grouping: GroupKey | null
  collapsed: ReadonlySet<string>
  gap: GapKey | null
  search: string
  sort: SortState | null
}

export type RegistryItem =
  | { type: 'group'; key: string; label: string; count: number; collapsed: boolean }
  | { type: 'row'; row: RequirementRow; depth: number }

function sortValue(row: RequirementRow, key: string): string | number | null {
  switch (key) {
    case 'mop':
      return row.condition?.value ?? null
    case 'carrier':
      return row.carrierName ?? row.allocatedTo[0] ?? null
    case 'verification':
      return row.verificationState
    case 'parent':
      return row.parentId
    case 'kind':
      return row.kind
    case 'category':
      return row.category
    case 'level':
      return row.level
    case 'version':
      return row.version
    case 'owner':
      return row.owner
    case 'origin':
      return row.origin
    case 'statement':
      return row.statement
    case 'status':
      return row.status
    default:
      return row.id
  }
}

function compare(a: RequirementRow, b: RequirementRow, sort: SortState | null): number {
  if (sort) {
    const va = sortValue(a, sort.key)
    const vb = sortValue(b, sort.key)
    // пустое всегда в хвост, направление его не переворачивает
    if (va === null && vb !== null) return 1
    if (vb === null && va !== null) return -1
    if (va !== null && vb !== null && va !== vb) {
      const base =
        typeof va === 'number' && typeof vb === 'number'
          ? (va < vb ? -1 : 1)
          : String(va).localeCompare(String(vb), 'ru')
      return sort.dir === 'desc' ? -base : base
    }
  }
  return a.id.localeCompare(b.id, 'ru')
}

function groupOf(row: RequirementRow, grouping: GroupKey): { key: string; label: string } {
  switch (grouping) {
    case 'carrier': {
      if (row.level === 'project') return { key: PROJECT_GROUP, label: 'Уровень проекта — на нуждах' }
      const id = row.allocatedTo[0]
      // короткое имя в заголовке: уровень ясен из контекста группировки
      if (!id) return { key: NO_CARRIER_GROUP, label: 'Без носителя' }
      return { key: id, label: row.carrierName ? `${id} ${row.carrierName}` : id }
    }
    case 'level':
      return { key: row.level ?? '—', label: `Уровень: ${row.level ?? 'не задан'}` }
    case 'status':
      return { key: row.status, label: row.status }
    case 'owner':
      return { key: row.owner ?? '—', label: row.owner ?? 'Владелец не задан' }
  }
}

/** Обход дерева в пределах набора строк: ребёнок с отступом строго ПОСЛЕ
 * родителя и только если родитель в наборе; иначе строка идёт корнем. */
function treeWalk(rows: RequirementRow[], sort: SortState | null): Array<{ row: RequirementRow; depth: number }> {
  const inSet = new Map(rows.map((r) => [r.id, r]))
  const children = new Map<string, RequirementRow[]>()
  const roots: RequirementRow[] = []
  rows.forEach((r) => {
    if (r.parentId && inSet.has(r.parentId)) {
      const list = children.get(r.parentId) ?? []
      list.push(r)
      children.set(r.parentId, list)
    } else {
      roots.push(r)
    }
  })
  const out: Array<{ row: RequirementRow; depth: number }> = []
  const visited = new Set<string>()
  const walk = (row: RequirementRow, depth: number) => {
    if (visited.has(row.id)) return
    visited.add(row.id)
    out.push({ row, depth })
    ;(children.get(row.id) ?? []).sort((a, b) => compare(a, b, sort)).forEach((c) => walk(c, depth + 1))
  }
  roots.sort((a, b) => compare(a, b, sort)).forEach((r) => walk(r, 0))
  return out
}

export function buildItems(rows: RequirementRow[], opts: ViewOptions): RegistryItem[] {
  const query = opts.search.trim().toLowerCase()
  let filtered = rows
  if (query) {
    filtered = filtered.filter(
      (r) => r.id.toLowerCase().includes(query) || r.statement.toLowerCase().includes(query),
    )
  }
  if (opts.gap) filtered = filtered.filter((r) => hasGap(r, opts.gap!))

  const ordered = (subset: RequirementRow[]) =>
    opts.form === 'tree'
      ? treeWalk(subset, opts.sort)
      : [...subset].sort((a, b) => compare(a, b, opts.sort)).map((row) => ({ row, depth: 0 }))

  if (!opts.grouping) return ordered(filtered).map(({ row, depth }) => ({ type: 'row', row, depth }))

  const groups = new Map<string, { label: string; rows: RequirementRow[] }>()
  filtered.forEach((r) => {
    const g = groupOf(r, opts.grouping!)
    const bucket = groups.get(g.key) ?? { label: g.label, rows: [] }
    bucket.rows.push(r)
    groups.set(g.key, bucket)
  })
  // проектные — первой группой, системные сироты — сразу за ними (разрыв
  // на виду, не в хвосте); носители — по наполнению
  const rank = (k: string) => (k === PROJECT_GROUP ? 0 : k === NO_CARRIER_GROUP ? 1 : 2)
  const orderedKeys = [...groups.keys()].sort((a, b) => {
    if (rank(a) !== rank(b)) return rank(a) - rank(b)
    const diff = groups.get(b)!.rows.length - groups.get(a)!.rows.length
    return diff !== 0 ? diff : a.localeCompare(b, 'ru')
  })
  const items: RegistryItem[] = []
  orderedKeys.forEach((key) => {
    const g = groups.get(key)!
    const collapsed = opts.collapsed.has(key)
    items.push({ type: 'group', key, label: g.label, count: g.rows.length, collapsed })
    if (!collapsed) ordered(g.rows).forEach(({ row, depth }) => items.push({ type: 'row', row, depth }))
  })
  return items
}

/** Текущая выборка по порядку показа — пред./след. карточки ходят по ней. */
export function flatRows(items: RegistryItem[]): RequirementRow[] {
  return items.filter((i): i is Extract<RegistryItem, { type: 'row' }> => i.type === 'row').map((i) => i.row)
}
