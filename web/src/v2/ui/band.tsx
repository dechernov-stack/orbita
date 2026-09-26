// Полоса группы (шип 5 §1.2) — группировка ВНЕ таблицы.
//
// Группа таблицы — своя полоса над своей таблицей, а не строка с colSpan
// внутри tbody: раскрыватель ▸/▾, предмет жирно, счётчики словами
// приглушённо, справа действия группы пиктограммами. Колонки внутри группы
// предмет не повторяют — он один раз в полосе. «Раскрыть все / свернуть
// все» — над первой полосой; единственная группа раскрыта сразу.
import { useCallback, useMemo, useState, type ReactNode } from 'react'
import { ИконКнопка } from './iconbutton'

export function Полоса({ open, onToggle, subject, counts, actions, children, id }: {
  open: boolean
  onToggle: () => void
  subject: ReactNode
  /** Счётчики словами: «фактов 12 · нерешённых 3 · противоречий 1». */
  counts?: string
  /** Действия группы — пиктограммами (ИконКнопка). */
  actions?: ReactNode
  children?: ReactNode
  id?: string
}) {
  return (
    <section className="v2-bandgroup" id={id}>
      <div className={open ? 'v2-band' : 'v2-band v2-band--closed'}>
        <ИконКнопка икон={open ? 'свернуть-полосу' : 'раскрыть-полосу'} слово={open ? 'свернуть группу' : 'раскрыть группу'}
          aria-expanded={open} onClick={onToggle} />
        <b className="v2-band__subject">{subject}</b>
        {counts && <span className="v2-band__n">{counts}</span>}
        <span className="v2-band__sp" />
        {actions}
      </div>
      {open && children}
    </section>
  )
}

/**
 * Какие полосы раскрыты. Единственная группа раскрыта сразу; остальные —
 * свёрнуты, пока человек не раскроет. «Раскрыть все» и «свернуть все» —
 * одной кнопкой над первой полосой.
 */
/** Раскрыта ли полоса: единственная группа раскрыта всегда, прочие — по выбору человека. */
export function раскрыта(ключи: string[], открытые: Set<string>, ключ: string): boolean {
  return ключи.length === 1 || открытые.has(ключ)
}

export function useПолосы(ключи: string[], раскрытыИзначально: string[] = []) {
  const [открытые, setОткрытые] = useState<Set<string>>(() => new Set(раскрытыИзначально))
  const открыта = useCallback((к: string) => раскрыта(ключи, открытые, к), [ключи, открытые])
  const переключить = useCallback((к: string) => setОткрытые((было) => {
    const стало = new Set(было)
    if (стало.has(к)) стало.delete(к); else стало.add(к)
    return стало
  }), [])
  /** Раскрыть полосу, не переключая: переход «к записи» открывает её группу. */
  const раскрыть = useCallback((к: string) => setОткрытые((было) => (было.has(к) ? было : new Set([...было, к]))), [])
  const все = useMemo(() => ключи.length > 0 && ключи.every((к) => открытые.has(к)), [ключи, открытые])
  const всеРазом = useCallback(() => setОткрытые(все ? new Set() : new Set(ключи)), [все, ключи])
  return { открыта, переключить, раскрыть, все, всеРазом, число: ключи.length }
}

/** Строка над первой полосой: «раскрыть все (N)» / «свернуть все». */
export function РаскрытьВсе({ все, число, onClick }: { все: boolean; число: number; onClick: () => void }) {
  if (число < 2) return null
  return (
    <button type="button" className="v2-link" onClick={onClick}
      title={все ? 'свернуть все группы' : `раскрыть все группы: ${число}`}>
      {все ? 'свернуть все' : `раскрыть все (${число})`}
    </button>
  )
}
