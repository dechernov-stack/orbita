// Подсказка при наведении (процесс-задача §2.3). Нативный `title` остаётся
// у простых мест, но там, где расшифровка ДЛИННАЯ или показывается часто,
// он плох: секунда задержки, одна строка, обрезание системой и полное
// молчание при фокусе с клавиатуры.
//
// Здесь: подсказка появляется сразу, переносится по строкам, читается с
// клавиатуры (focus/blur) и не перекрывает содержимое строки. Правило
// задачи — «элемент без текста обязан нести подсказку» — держит сторож
// tools/validate_tooltips.py; компонента даёт этой подсказке вид.
import { useId, useState } from 'react'
import type { ReactNode } from 'react'

export function Tooltip({ text, children, side = 'top', className }: {
  /** Расшифровка. Пустая строка запрещена: подсказка без текста — обман. */
  text: string
  children: ReactNode
  side?: 'top' | 'bottom'
  className?: string
}) {
  const [open, setOpen] = useState(false)
  const id = useId()
  return (
    <span
      className={`tip${className ? ` ${className}` : ''}`}
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
      onFocus={() => setOpen(true)}
      onBlur={() => setOpen(false)}
      tabIndex={0}
      aria-describedby={open ? id : undefined}
    >
      {children}
      {open && (
        <span className={`tip__box tip__box--${side}`} id={id} role="tooltip">
          {text}
        </span>
      )}
    </span>
  )
}

/**
 * Тихий дефис: пустое место, которое ЗАКОННО пусто. Причина обязательна —
 * инженер обязан понимать, почему здесь ничего нет и появится ли оно.
 */
export function Muted({ why, children = '—' }: { why: string; children?: ReactNode }) {
  return (
    <Tooltip text={why}>
      <span className="secondary">{children}</span>
    </Tooltip>
  )
}

/**
 * Обрезанный текст: в ячейке — сокращение, в подсказке — полный текст.
 * Без этого длинная формулировка требования читается только открытием
 * карточки, а в реестре превращается в многоточие без содержания.
 */
export function Clipped({ text, limit = 120 }: { text: string; limit?: number }) {
  if (text.length <= limit) return <>{text}</>
  return (
    <Tooltip text={text}>
      <span>{text.slice(0, limit)}…</span>
    </Tooltip>
  )
}
