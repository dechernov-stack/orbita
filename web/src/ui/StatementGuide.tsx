// Ф-12: полоса-проводник постановки (паттерн Ф-08.2, но на сквозную цепочку
// проекта). Владелец: «цели и нужды есть, а дальше маршрут не строится».
//
// Сделанное гаснет счётчиком, первое несделанное — приглашение с переходом
// к месту действия. Состояние цепочки считает СЕРВЕР: здесь только показ.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { StatementPathView } from '../api/types'

export function StatementGuide({ onGo, compact }: {
  /** Переход к месту действия: экран, а при генерации — с преднастроенным видом. */
  onGo?: (screen: string, kind?: string) => void
  /** Сжатый вид для шапки: одна строка приглашения. */
  compact?: boolean
}) {
  const [path, setPath] = useState<StatementPathView | null>(null)

  useEffect(() => {
    api.statementPath().then(setPath).catch(() => setPath(null))
  }, [])

  if (!path) return null

  if (compact) {
    if (path.complete) return null
    const next = path.next
    if (!next) return null
    return (
      <button className="rr-assign" onClick={() => onGo?.(next.screen, next.kind)}
        title={`${next.why} — открыть место действия`}>
        постановка: {next.invitation} →
      </button>
    )
  }

  return (
    <div className="card">
      <h3>Путь постановки</h3>
      <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center' }}>
        {path.links.map((link, i) => (
          <span key={link.key} style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
            {i > 0 && <span className="secondary">→</span>}
            {link.done ? (
              <span className="secondary" title={`${link.title}: сделано`}>
                ✓ {link.title}
                {link.count > 1 && <span className="mono"> · {link.count}</span>}
              </span>
            ) : (
              <button className="rr-assign" onClick={() => onGo?.(link.screen, link.kind)}
                title={`${link.why} — открыть место действия`}>
                {link.invitation} →
              </button>
            )}
          </span>
        ))}
      </div>
      <p className="secondary" style={{ margin: '6px 0 0' }}>{path.summary}</p>
    </div>
  )
}
