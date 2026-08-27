// Компонент даты оболочки (reference-date-input, круг 3 §2): закрывает
// последний нативный контрол. Ручной ввод дд.мм.гггг равноправен календарю;
// пустая дата законна; календарь открывается ОТ ОПОРНОЙ даты (правило
// круга 2), дни раньше опоры недоступны; отказ порядка — тем же текстом,
// что серверный (одно правило).
import { useEffect, useRef, useState } from 'react'

const MONTHS = ['Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь',
  'Июль', 'Август', 'Сентябрь', 'Октябрь', 'Ноябрь', 'Декабрь']
const DOW = ['пн', 'вт', 'ср', 'чт', 'пт', 'сб', 'вс']

function toDisplay(iso: string): string {
  const m = iso.match(/^(\d{4})-(\d{2})-(\d{2})$/)
  return m ? `${m[3]}.${m[2]}.${m[1]}` : ''
}

function toIso(display: string): string | null {
  const m = display.match(/^(\d{2})\.(\d{2})\.(\d{4})$/)
  if (!m) return null
  const iso = `${m[3]}-${m[2]}-${m[1]}`
  const d = new Date(`${iso}T00:00:00Z`)
  return Number.isNaN(d.getTime()) ? null : iso
}

function shiftDays(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

export function DateInput({ iso, onChange, anchor, anchorName, name, width }: {
  /** Значение ISO ГГГГ-ММ-ДД; пустая строка — «дата не задана» (законно). */
  iso: string
  onChange: (iso: string) => void
  /** Опорная дата ISO: раньше неё нельзя (предыдущая заданная веха). */
  anchor?: string
  /** Имя опоры в родительном падеже — для текста отказа порядка. */
  anchorName?: string
  /** Имя своей точки — для текста отказа порядка. */
  name?: string
  width?: number
}) {
  const [text, setText] = useState(toDisplay(iso))
  const [open, setOpen] = useState(false)
  const [orderFail, setOrderFail] = useState<string | null>(null)
  const [month, setMonth] = useState(() => (iso || anchor || new Date().toISOString().slice(0, 10)).slice(0, 7))
  const rootRef = useRef<HTMLSpanElement>(null)

  useEffect(() => { setText(toDisplay(iso)) }, [iso])
  // календарь открывается ОТ ОПОРНОЙ даты (правило круга 2): месяц
  // пересчитывается при каждом открытии — опора могла появиться позже
  useEffect(() => {
    if (open) setMonth((iso || anchor || new Date().toISOString().slice(0, 10)).slice(0, 7))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])
  useEffect(() => {
    if (!open) return
    const away = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', away)
    return () => document.removeEventListener('mousedown', away)
  }, [open])

  const refuseBefore = (candidate: string): boolean => {
    if (anchor && candidate < anchor) {
      setOrderFail(
        `${name ?? 'Дата'} не может быть раньше ${anchorName ?? 'предыдущей точки'} (${toDisplay(anchor)}) — даты идут по порядку точек.`,
      )
      return true
    }
    setOrderFail(null)
    return false
  }

  const commit = (candidate: string) => {
    if (refuseBefore(candidate)) return
    onChange(candidate)
    setMonth(candidate.slice(0, 7))
  }

  const manual = (raw: string) => {
    setText(raw)
    if (raw === '') { setOrderFail(null); onChange(''); return }
    const parsed = toIso(raw)
    if (parsed) commit(parsed)
  }

  const keys = (e: React.KeyboardEvent) => {
    const base = iso || anchor || new Date().toISOString().slice(0, 10)
    if (e.key === 'Escape') setOpen(false)
    else if (e.key === 'Enter') setOpen(false)
    else if (e.key === 'ArrowUp') { e.preventDefault(); commit(shiftDays(base, 1)) }
    else if (e.key === 'ArrowDown') { e.preventDefault(); commit(shiftDays(base, -1)) }
    else if (e.key === 'PageUp') { e.preventDefault(); commit(shiftDays(base, 31)) }
    else if (e.key === 'PageDown') { e.preventDefault(); commit(shiftDays(base, -31)) }
  }

  const monthShift = (delta: number) => {
    const [y, m] = month.split('-').map(Number)
    const d = new Date(Date.UTC(y, m - 1 + delta, 1))
    setMonth(d.toISOString().slice(0, 7))
  }

  // сетка месяца: с понедельника недели первого дня по воскресенье последней
  const grid = (() => {
    const first = `${month}-01`
    const fd = new Date(`${first}T00:00:00Z`)
    const lead = (fd.getUTCDay() + 6) % 7
    const start = shiftDays(first, -lead)
    const out: Array<{ day: string; inMonth: boolean }> = []
    let cur = start
    for (let i = 0; i < 42; i++) {
      out.push({ day: cur, inMonth: cur.slice(0, 7) === month })
      cur = shiftDays(cur, 1)
    }
    return out
  })()

  const today = new Date().toISOString().slice(0, 10)

  return (
    <span className="dinput__root" ref={rootRef} style={width ? { width } : undefined}>
      <span className={`dinput${open ? ' focus' : ''}${orderFail ? ' err' : ''}`}>
        <input
          value={text}
          placeholder="дд.мм.гггг"
          onChange={(e) => manual(e.target.value)}
          onKeyDown={keys}
          onFocus={() => setOpen(true)}
        />
        {iso && (
          <button type="button" className="dinput__clr" title="очистить — «дата не задана» законна"
            onClick={() => { setOrderFail(null); onChange('') }}>
            ✕
          </button>
        )}
        <svg viewBox="0 0 24 24" onClick={() => setOpen((v) => !v)}>
          <rect x="4" y="6" width="16" height="14" rx="2" /><path d="M4 10h16M8 4v4M16 4v4" />
        </svg>
      </span>
      {orderFail && <span className="dinput__errline">{orderFail}</span>}
      {open && (
        <span className="dinput__pop">
          <span className="dinput__mhead">
            <button type="button" className="dinput__mbtn" onClick={() => monthShift(-1)}>‹</button>
            <b>{MONTHS[Number(month.slice(5, 7)) - 1]} {month.slice(0, 4)}</b>
            <button type="button" className="dinput__mbtn" onClick={() => monthShift(1)}>›</button>
          </span>
          <span className="dinput__grid">
            {DOW.map((d) => <span key={d} className="dinput__dow">{d}</span>)}
            {grid.map(({ day, inMonth }) => {
              const disabled = Boolean(anchor && day < anchor)
              const cls = ['dinput__d']
              if (!inMonth) cls.push('out')
              if (disabled) cls.push('dis')
              if (day === anchor) cls.push('anchor')
              if (day === iso) cls.push('sel')
              if (day === today) cls.push('today')
              return (
                <button
                  key={day}
                  type="button"
                  className={cls.join(' ')}
                  title={day === anchor ? `${anchorName ?? 'опора'} — опора` : undefined}
                  disabled={disabled}
                  onClick={() => { commit(day); setOpen(false) }}
                >
                  {Number(day.slice(8, 10))}
                </button>
              )
            })}
          </span>
          <span className="dinput__pfoot">
            <button type="button" className="rr-assign" onClick={() => { commit(today); setOpen(false) }}>Сегодня</button>
            <span>серый — опора; зачёркнутые — раньше опоры</span>
          </span>
        </span>
      )}
    </span>
  )
}
