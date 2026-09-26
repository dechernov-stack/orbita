// Массовый приём (шип 5 §1.4) — вынесен из приёма предложений в общее.
//
// Колонка отметок, диапазон Shift-кликом, клавиатура (↑ ↓ — строка, Space —
// отметить, Shift+↑↓ — диапазон, Enter — карточка, A · R · L — принять ·
// отклонить · отложить, Esc — снять выбор), «умные наборы» (все решённые ·
// все без замечаний · по группе) и липкая нижняя панель «выбрано N —
// действия». Мера: сто строк — не больше пяти действий, без мыши.
import { useCallback, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { ИконКнопка } from './iconbutton'
import type { Пиктограмма } from '../icons'

export interface Набор {
  key: string
  /** Слово набора: «все решённые», «без замечаний», «группа Минтранс». */
  word: string
  keys: string[]
}

export interface МассовоеДействие {
  key: string
  икон: Пиктограмма
  слово: string
  /** Клавиша действия (A · R · L), если есть. */
  клавиша?: string
  run: (ключи: string[]) => void
  disabled?: boolean
}

/**
 * Отметить строку: без Shift — переключить одну; с Shift — добавить диапазон
 * от якоря (последней отмеченной) до этой. Возвращает новый выбор и якорь.
 */
export function отметитьСтроку(ключи: string[], выбраны: Set<string>, якорь: number, ключ: string, диапазон: boolean): { выбраны: Set<string>; якорь: number } {
  const i = ключи.indexOf(ключ)
  const стало = new Set(выбраны)
  if (диапазон && якорь >= 0 && i >= 0) {
    const [от, до] = якорь < i ? [якорь, i] : [i, якорь]
    for (let j = от; j <= до; j += 1) стало.add(ключи[j])
  } else if (стало.has(ключ)) стало.delete(ключ); else стало.add(ключ)
  return { выбраны: стало, якорь: i }
}

/** Выбор строк: отметки, курсор, диапазон, клавиатура. Ключ строки — код записи. */
export function useМассово(ключи: string[], опции: {
  onOpen?: (ключ: string) => void
  действия?: МассовоеДействие[]
} = {}) {
  const [выбраны, setВыбраны] = useState<Set<string>>(new Set())
  const [курсор, setКурсор] = useState<number>(-1)
  const якорь = useRef<number>(-1)

  const индекс = useCallback((к: string) => ключи.indexOf(к), [ключи])

  /** Отметить строку; с Shift — диапазон от последней отмеченной. */
  const отметить = useCallback((к: string, диапазон = false) => {
    const прежний = якорь.current
    setВыбраны((было) => отметитьСтроку(ключи, было, прежний, к, диапазон).выбраны)
    якорь.current = индекс(к)
    setКурсор(индекс(к))
  }, [индекс, ключи])

  const выбрать = useCallback((набор: string[]) => setВыбраны(new Set(набор.filter((к) => ключи.includes(к)))), [ключи])
  const снять = useCallback(() => { setВыбраны(new Set()); якорь.current = -1 }, [])
  const всеВыбраны = ключи.length > 0 && ключи.every((к) => выбраны.has(к))
  const всеРазом = useCallback(() => (всеВыбраны ? снять() : выбрать(ключи)), [всеВыбраны, снять, выбрать, ключи])

  const клавиша = useCallback((e: KeyboardEvent<HTMLElement>) => {
    const цель = e.target as HTMLElement
    if (цель.closest('input:not([type=checkbox]), textarea, select')) return
    const n = ключи.length
    if (n === 0) return
    const сейчас = курсор < 0 ? 0 : курсор
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      // Курсор по строкам: не выше первой и не ниже последней.
      const шаг = e.key === 'ArrowDown' ? сейчас + 1 : сейчас - 1
      const j = шаг < 0 ? 0 : шаг > n - 1 ? n - 1 : шаг
      if (e.shiftKey) отметить(ключи[j], true)
      setКурсор(j)
      return
    }
    if (e.key === ' ') { e.preventDefault(); отметить(ключи[сейчас], e.shiftKey); return }
    if (e.key === 'Escape') { снять(); return }
    if (e.key === 'Enter' && опции.onOpen) { e.preventDefault(); опции.onOpen(ключи[сейчас]); return }
    const буква = e.key.toUpperCase()
    const действие = опции.действия?.find((д) => д.клавиша === буква && !д.disabled)
    if (действие) {
      e.preventDefault()
      const цели = выбраны.size > 0 ? ключи.filter((к) => выбраны.has(к)) : [ключи[сейчас]]
      действие.run(цели)
    }
  }, [ключи, курсор, отметить, снять, опции, выбраны])

  const выбранныеПоПорядку = useMemo(() => ключи.filter((к) => выбраны.has(к)), [ключи, выбраны])
  return { выбраны, выбранные: выбранныеПоПорядку, отметить, выбрать, снять, всеВыбраны, всеРазом, курсор, setКурсор, клавиша }
}

/** Отметка строки: чекбокс с именем для чтения экрана; Shift-клик — диапазон. */
export function Отметка({ ключ, выбрана, onToggle }: { ключ: string; выбрана: boolean; onToggle: (ключ: string, диапазон: boolean) => void }) {
  return (
    <input type="checkbox" className="v2-mark" checked={выбрана} aria-label={`выбрать ${ключ}`}
      onChange={() => undefined}
      onClick={(e) => onToggle(ключ, e.shiftKey)} />
  )
}

/** Липкая нижняя панель: «выбрано N», действия пиктограммой и словом, наборы, подсказка клавиш. */
export function ПанельМассово({ выбранные, действия, наборы, onНабор, onСнять, подсказка }: {
  /** Ключи выбранных строк по порядку таблицы. */
  выбранные: string[]
  действия: МассовоеДействие[]
  наборы?: Набор[]
  onНабор?: (набор: Набор) => void
  onСнять?: () => void
  подсказка?: ReactNode
}) {
  const n = выбранные.length
  const клавиши = действия.filter((д) => д.клавиша).map((д) => `${д.клавиша} ${д.слово}`).join(' · ')
  return (
    <div className="v2-mass" role="toolbar" aria-label="действия с выбранными">
      <b>выбрано {n}</b>
      {n === 0 && наборы && наборы.some((н) => н.keys.length > 0) && onНабор && (
        <span className="v2-mass__sets">
          {наборы.filter((н) => н.keys.length > 0).map((н) => (
            <button key={н.key} type="button" className="v2-chip" title={`выбрать: ${н.word}`}
              onClick={() => onНабор(н)}>{н.word} {н.keys.length}</button>
          ))}
        </span>
      )}
      {n > 0 && действия.map((д) => (
        <ИконКнопка key={д.key} икон={д.икон} слово={д.слово} сословом disabled={д.disabled} className="v2-btn"
          onClick={() => д.run(выбранные)} />
      ))}
      {n > 0 && onСнять && <button type="button" className="v2-link" onClick={onСнять} title="снять выбор (Esc)">снять выбор</button>}
      <span className="v2-mass__k">{подсказка ?? `↑ ↓ строка · Space отметить · Shift диапазон${клавиши ? ` · ${клавиши}` : ''} · Esc снять выбор`}</span>
    </div>
  )
}
