// Деревья реестра требований и матрица «требование × носитель».
import { useMemo } from 'react'
import { type RequirementRow } from '../api'

/** Два дерева: по носителю (кто несёт) и по источнику (откуда выведено). */
export function Деревья({ строки, вид, документы = [] }: {
  строки: RequirementRow[]
  вид: 'carrier' | 'source' | 'document'
  /** Коды материалов проекта: по ним источник узнаётся документом. */
  документы?: string[]
}) {
  const группы = useMemo(() => {
    const карта = new Map<string, RequirementRow[]>()
    строки.forEach((т) => {
      const ключи = вид === 'carrier'
        ? [т.carrier ?? 'без носителя']
        // «По документам» — источники-материалы: из чего требование написано;
        // какие коды материалы, говорят ДАННЫЕ проекта, а не форма кода.
        // «По иерархии» — все источники, включая цели и нужды.
        : вид === 'document'
          ? (т.sources.filter((и) => документы.includes(и)).length > 0
            ? т.sources.filter((и) => документы.includes(и))
            : ['без документа-источника'])
          : (т.sources.length > 0 ? т.sources : ['без источника'])
      ключи.forEach((к) => карта.set(к, [...(карта.get(к) ?? []), т]))
    })
    return [...карта.entries()].sort((a, b) => a[0].localeCompare(b[0]))
  }, [строки, вид, документы])

  if (группы.length === 0) return null

  return (
    <div className="v2-card">
      <div className="v2-card__head">
        <span className="v2-card__title">
          Дерево {вид === 'carrier' ? 'по носителю' : вид === 'document' ? 'по документам' : 'по источнику'}
        </span>
        <span className="v2-card__count">{группы.length}</span>
      </div>
      <ul className="v2-tree">
        {группы.map(([ключ, дети]) => (
          <li key={ключ}>
            <span className="v2-tree__node">{ключ}</span>
            <span className="v2-card__count">{дети.length}</span>
            <ul>
              {дети.map((т) => (
                <li key={т.code}><span className="v2-dim">{т.code}</span> {т.title}</li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** Матрица «требование × носитель»: кто что несёт, одной клеткой. */
export function МатрицаНосителей({ строки }: { строки: RequirementRow[] }) {
  const носители = [...new Set(строки.map((т) => т.carrier ?? '—'))].sort((a, b) => a.localeCompare(b, 'ru'))
  return (
    <table className="v2-table">
      <thead>
        <tr><th>Требование</th>{носители.map((н) => <th key={н}>{н === '—' ? 'без носителя' : н}</th>)}</tr>
      </thead>
      <tbody>
        {строки.map((т) => (
          <tr key={т.code}>
            <td><span className="v2-mono">{т.code}</span> {т.title}</td>
            {носители.map((н) => (
              <td key={н} title={(т.carrier ?? '—') === н ? `${т.code} несёт ${н === '—' ? 'никто' : н}` : ''}>
                {(т.carrier ?? '—') === н ? '✓' : ''}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  )
}
