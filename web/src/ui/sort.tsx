// Сортировка заголовком (МВП-П1 §2.4, паттерн П-Б): один компонент на все
// таблицы — клик сортирует, повтор — реверс, стрелка видима. Клиентская,
// по загруженным данным.
import { useMemo, useState } from 'react'

export interface SortState { key: string; dir: 'asc' | 'desc' }

export function useSort<T>(
  rows: T[],
  accessors: Record<string, (row: T) => string | number>,
): { sorted: T[]; sort: SortState | null; toggle: (key: string) => void } {
  const [sort, setSort] = useState<SortState | null>(null)
  const toggle = (key: string) =>
    setSort((cur) => (cur?.key === key
      ? (cur.dir === 'asc' ? { key, dir: 'desc' } : null)
      : { key, dir: 'asc' }))
  const sorted = useMemo(() => {
    if (!sort) return rows
    const get = accessors[sort.key]
    if (!get) return rows
    const copy = [...rows]
    copy.sort((a, b) => {
      const x = get(a)
      const y = get(b)
      const cmp = typeof x === 'number' && typeof y === 'number'
        ? x - y
        : String(x).localeCompare(String(y), 'ru')
      return sort.dir === 'asc' ? cmp : -cmp
    })
    return copy
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, sort])
  return { sorted, sort, toggle }
}

/** Заголовок-сортировка: третий клик снимает сортировку (исходный порядок). */
export function SortTh({ label, sortKey, sort, onToggle, width }: {
  label: string
  sortKey: string
  sort: SortState | null
  onToggle: (key: string) => void
  width?: number
}) {
  const active = sort?.key === sortKey
  return (
    <th style={{ width, cursor: 'pointer', userSelect: 'none' }}
      title="клик — сортировка, повтор — обратный порядок"
      onClick={() => onToggle(sortKey)}>
      {label}{active && (sort!.dir === 'desc' ? ' ↓' : ' ↑')}
    </th>
  )
}
