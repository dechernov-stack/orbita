// Ввод ссылок справочником (МВП-П1 §2.2): поиск по ID и имени, недавние
// сверху; значение выбирается из списка — свободный текстовый ввод ID умер.
// «Создать и связать» здесь нет намеренно: создание — только явной кнопкой
// там, где оно законно (ловушка 3).
import { useEffect, useRef, useState } from 'react'

export interface RefOption { id: string; title?: string }

/** Недавние — память сессии, общая на все пикеры (последний — первым). */
const RECENTS: string[] = []
function remember(id: string) {
  const i = RECENTS.indexOf(id)
  if (i >= 0) RECENTS.splice(i, 1)
  RECENTS.unshift(id)
  if (RECENTS.length > 7) RECENTS.pop()
}

export function RefPicker({ value, onChange, options, placeholder, width, clearable }: {
  value: string
  onChange: (id: string) => void
  options: RefOption[] | null
  placeholder?: string
  width?: number
  /** Пустое значение законно — строка «очистить» в списке. */
  clearable?: boolean
}) {
  const [open, setOpen] = useState(false)
  const [q, setQ] = useState('')
  const rootRef = useRef<HTMLSpanElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (!open) return
    setQ('')
    inputRef.current?.focus()
    const away = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', away)
    return () => document.removeEventListener('mousedown', away)
  }, [open])

  const all = options ?? []
  const needle = q.trim().toLowerCase()
  const matches = needle
    ? all.filter((o) => o.id.toLowerCase().includes(needle) ||
        (o.title ?? '').toLowerCase().includes(needle))
    : all
  const recent = needle ? [] : RECENTS
    .map((id) => all.find((o) => o.id === id))
    .filter(Boolean) as RefOption[]
  const shown = matches.slice(0, 50)
  const current = all.find((o) => o.id === value)

  const pick = (id: string) => {
    remember(id)
    onChange(id)
    setOpen(false)
  }

  const row = (o: RefOption) => (
    <button key={o.id} type="button" role="option" className="rp-row"
      aria-selected={o.id === value}
      onClick={() => pick(o.id)}>
      <span className="mono">{o.id}</span>
      <span className="rp-nm">{o.title ?? ''}</span>
    </button>
  )

  return (
    <span className="osel rp" ref={rootRef} style={width ? { width } : undefined}>
      <button type="button" className="osel__btn" onClick={() => setOpen((v) => !v)}
        title={current ? `${current.id}${current.title ? ` · ${current.title}` : ''}` : (placeholder ?? 'выбрать…')}>
        {value
          ? <><span className="mono">{value}</span>{current?.title ? ` · ${current.title}` : ''}</>
          : <span className="secondary">{placeholder ?? 'выбрать из справочника…'}</span>}
      </button>
      {open && (
        <span className="osel__pop rp-pop" role="listbox">
          <input ref={inputRef} className="rp-q" value={q}
            placeholder="поиск по id и имени"
            onChange={(e) => setQ(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Escape') setOpen(false)
              if (e.key === 'Enter' && shown.length === 1) pick(shown[0].id)
            }} />
          {clearable && value && (
            <button type="button" className="rp-row secondary" onClick={() => { onChange(''); setOpen(false) }}>
              — очистить (пусто — законно)
            </button>
          )}
          {recent.length > 0 && (
            <>
              <span className="rp-sec">недавние</span>
              {recent.map(row)}
              <span className="rp-sec">все</span>
            </>
          )}
          {shown.map(row)}
          {matches.length > shown.length && (
            <span className="rp-sec">ещё {matches.length - shown.length} — уточните поиск</span>
          )}
          {all.length === 0 && <span className="rp-sec">объектов вида в проекте нет</span>}
          {all.length > 0 && matches.length === 0 && <span className="rp-sec">ничего не найдено</span>}
        </span>
      )}
    </span>
  )
}
