// Вкладки (шип 5 §1.1) — единственное второе меню экрана.
//
// Строка под заголовком раздела: подчёркивание у текущей, слово чёрным,
// число приглушённым, слева — маркер здоровья (кружок: пустой — в порядке,
// полузаливка янтарём — есть долг, заливка красным — держит точку; слово —
// в подсказке). Не больше семи вкладок; восьмая и дальше — эксперт-вкладки,
// серым за разделителем до включения эксперт-режима. Выбор помнится на
// раздел. Клавиатура ← →, Home, End. `role="tablist"`.
import { useCallback, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'

/** Здоровье вкладки: в порядке · есть долг · держит точку; null — маркера нет. */
export type Здоровье = 'ok' | 'debt' | 'block' | null

export interface Вкладка<K extends string = string> {
  key: K
  /** Слово вкладки — существительное раздела, не глагол. */
  word: string
  /** Число приглушённо: «18», «20 из 29». */
  count?: string | number | null
  health?: Здоровье
  /** Подсказка: что здесь и, при долге, слово долга («3 стороны без нужд»). ≤ 160 знаков. */
  hint: string
  /** Эксперт-вкладка: серым за разделителем, пока эксперт-режим выключен. */
  expert?: boolean
}

/** Сколько обычных вкладок в строке: восьмая и дальше уходят в эксперт-режим. */
export const ВКЛАДОК_НЕ_БОЛЬШЕ = 7

/** Слово маркера здоровья — для подсказки и чтения экрана. */
export const СЛОВО_ЗДОРОВЬЯ: Record<Exclude<Здоровье, null>, string> = {
  ok: 'в порядке',
  debt: 'есть долг',
  block: 'держит точку',
}

/** Раскладка: обычные (не больше семи) и эксперт-вкладки — лишние обычные уходят к эксперт-вкладкам. */
export function разложить<K extends string>(items: Вкладка<K>[]): { обычные: Вкладка<K>[]; эксперт: Вкладка<K>[] } {
  const обычные = items.filter((в) => !в.expert)
  const эксперт = items.filter((в) => в.expert)
  return {
    обычные: обычные.slice(0, ВКЛАДОК_НЕ_БОЛЬШЕ),
    эксперт: [...обычные.slice(ВКЛАДОК_НЕ_БОЛЬШЕ).map((в) => ({ ...в, expert: true })), ...эксперт],
  }
}

/** Куда перейти клавишей: ← → по кругу, Home — первая, End — последняя; иначе null. */
export function следующаяВкладка(ключи: string[], текущая: string, клавиша: string): string | null {
  if (ключи.length === 0) return null
  const найдена = ключи.indexOf(текущая)
  const i = найдена < 0 ? 0 : найдена
  if (клавиша === 'ArrowRight') return ключи[(i + 1) % ключи.length]
  if (клавиша === 'ArrowLeft') return ключи[(i - 1 + ключи.length) % ключи.length]
  if (клавиша === 'Home') return ключи[0]
  if (клавиша === 'End') return ключи[ключи.length - 1]
  return null
}

/** Помнить вкладку на раздел: localStorage, а без него — до перезагрузки. */
export function useВкладка<K extends string>(раздел: string, умолчание: K, допустимые: readonly K[]): [K, (к: K) => void] {
  const ключ = `orbita.v2.tab.${раздел}`
  const [текущая, setТекущая] = useState<K>(() => {
    try {
      const было = localStorage.getItem(ключ) as K | null
      return было && допустимые.includes(было) ? было : умолчание
    } catch { return умолчание }
  })
  const выбрать = useCallback((к: K) => {
    setТекущая(к)
    try { localStorage.setItem(ключ, к) } catch { /* браузер без хранилища — выбор живёт до перезагрузки */ }
  }, [ключ])
  return [допустимые.includes(текущая) ? текущая : умолчание, выбрать]
}

/** Маркер здоровья: кружок; заливка — состояние; слово — рядом в подсказке. */
export function Маркер({ health, title }: { health: Здоровье; title?: string }) {
  if (!health) return null
  return <i className={`v2-hm v2-hm--${health}`} role="img" aria-label={title ?? СЛОВО_ЗДОРОВЬЯ[health]} title={title ?? СЛОВО_ЗДОРОВЬЯ[health]} />
}

export function Вкладки<K extends string>({ items, current, onChange, label, expert = false, tail }: {
  items: Вкладка<K>[]
  current: K
  onChange: (к: K) => void
  /** Имя строки для чтения экрана: «постановка», «поле знаний». */
  label: string
  /** Эксперт-режим включён: эксперт-вкладки кликабельны. */
  expert?: boolean
  /** Хвост строки справа (редко: переключатель вида). */
  tail?: ReactNode
}) {
  const { обычные, эксперт } = разложить(items)
  const доступные = [...обычные, ...(expert ? эксперт : [])]
  const ссылки = useRef<Record<string, HTMLButtonElement | null>>({})
  const клавиша = (e: KeyboardEvent<HTMLDivElement>) => {
    const к = следующаяВкладка(доступные.map((в) => в.key), current, e.key) as K | null
    if (к === null) return
    e.preventDefault()
    onChange(к)
    ссылки.current[к]?.focus()
  }
  const вкладка = (в: Вкладка<K>, серая: boolean) => {
    const выбрана = в.key === current
    const подсказка = серая ? `${в.hint} — откроется в эксперт-режиме (меню учётки)` : в.hint
    return (
      <button key={в.key} ref={(у) => { ссылки.current[в.key] = у }} type="button" role="tab"
        className={серая ? 'v2-tab v2-tab--expert' : 'v2-tab'}
        aria-selected={выбрана} tabIndex={выбрана ? 0 : -1} disabled={серая}
        title={подсказка}
        onClick={() => onChange(в.key)}>
        <Маркер health={в.health ?? null} title={в.health ? `${СЛОВО_ЗДОРОВЬЯ[в.health]}: ${в.hint}` : undefined} />
        <b>{в.word}</b>
        {в.count !== undefined && в.count !== null && в.count !== '' && <span className="v2-tab__n">{в.count}</span>}
      </button>
    )
  }
  return (
    <div className="v2-tabs" role="tablist" aria-label={label} onKeyDown={клавиша}>
      {обычные.map((в) => вкладка(в, false))}
      {эксперт.length > 0 && <span className="v2-tabs__sep" role="separator" />}
      {эксперт.map((в) => вкладка(в, !expert))}
      {tail && <span className="v2-tabs__tail">{tail}</span>}
    </div>
  )
}
