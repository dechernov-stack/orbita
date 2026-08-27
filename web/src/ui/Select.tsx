// Компонент выбора оболочки (круг 3 §2): нативные select в продуктовых
// экранах запрещены — правило дизайн-системы. Кнопка + поповер списком;
// выбор одним нажатием, Esc и клик мимо закрывают.
import { useEffect, useRef, useState } from 'react'

export interface SelectOption {
  /** Ключ пункта (не величина модели — выбор, а не расчёт). */
  key: string
  title: string
}

export function Select({ value, options, onChange, placeholder, prefix, width }: {
  value: string
  options: SelectOption[]
  onChange: (value: string) => void
  placeholder?: string
  /** Постоянная приставка в кнопке: «Группировка: ‹значение› ▾». */
  prefix?: string
  width?: number
}) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLSpanElement>(null)

  useEffect(() => {
    if (!open) return
    const away = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false)
    }
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', away)
    document.addEventListener('keydown', key)
    return () => {
      document.removeEventListener('mousedown', away)
      document.removeEventListener('keydown', key)
    }
  }, [open])

  const current = options.find((o) => o.key === value)
  return (
    <span className="osel" ref={rootRef} style={width ? { width } : undefined}>
      <button type="button" className="osel__btn" onClick={() => setOpen((v) => !v)}>
        {prefix}{current ? current.title : (placeholder ?? '—')} ▾
      </button>
      {open && (
        <span className="osel__pop" role="listbox">
          {options.map((o) => (
            <button
              key={o.key}
              type="button"
              role="option"
              aria-selected={o.key === value}
              className={`osel__opt${o.key === value ? ' on' : ''}`}
              onClick={() => { onChange(o.key); setOpen(false) }}
            >
              {o.title}
            </button>
          ))}
        </span>
      )}
    </span>
  )
}
