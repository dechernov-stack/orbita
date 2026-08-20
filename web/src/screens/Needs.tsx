// Экран 1 — нужды стейкхолдеров (Ш1 мастера).
//
// Разрыв трассировки (нужда без сервисов) определён сервером: клиент только
// показывает признак, а не решает, что считать разрывом.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { NeedRow } from '../api/types'
import { StatusDot } from '../ui/parts'

export function Needs() {
  const [rows, setRows] = useState<NeedRow[] | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [onlyOrphans, setOnlyOrphans] = useState(false)

  useEffect(() => {
    api.needs().then(setRows).catch((e) => setError(String(e)))
  }, [])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!rows) return <div className="empty">Загрузка…</div>

  const shown = onlyOrphans ? rows.filter((r) => r.services.length === 0) : rows
  const need = rows.find((r) => r.id === selected)

  return (
    <div className="split">
      <div className="pane">
        <div style={{ padding: '8px 8px 0' }}>
          <button className="tab" aria-selected={onlyOrphans} onClick={() => setOnlyOrphans((v) => !v)}>
            Без сервисов
          </button>
        </div>
        <table>
          <thead>
            <tr>
              <th style={{ width: 100 }}>ID</th>
              <th>Формулировка</th>
              <th style={{ width: 200 }}>Стейкхолдер</th>
              <th style={{ width: 110 }}>Роль</th>
              <th style={{ width: 90 }}>Приоритет</th>
              <th style={{ width: 160 }}>Сервисы</th>
              <th style={{ width: 90 }}>Статус</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((row) => (
              <tr key={row.id} aria-selected={row.id === selected} onClick={() => setSelected(row.id)}>
                <td>
                  <span className="id">{row.id}</span>
                </td>
                <td>
                  <span className="truncate">{row.statement}</span>
                </td>
                <td>
                  <span className="truncate">{row.stakeholder}</span>
                </td>
                <td className="secondary">{row.role}</td>
                <td className="num">{row.priority}</td>
                <td>
                  {row.services.length === 0 ? (
                    <span className="warn">△ нет сервисов</span>
                  ) : (
                    row.services.map((s) => (
                      <span key={s} className="chip">
                        {s}
                      </span>
                    ))
                  )}
                </td>
                <td>
                  <StatusDot status={row.status} />
                  <span className="secondary">{row.status}</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <aside className="pane pane--side">
        {need ? (
          <div>
            <h2 style={{ fontSize: 15, margin: '0 0 4px' }}>
              <span className="id">{need.id}</span>
            </h2>
            <p style={{ marginTop: 0 }}>{need.statement}</p>
            <div className="card">
              <h3>Стейкхолдер</h3>
              <div>
                <div className="field">
                  <label>Кто</label>
                  {need.stakeholder}
                </div>
                <div className="field">
                  <label>Роль</label>
                  {need.role}
                </div>
                <div className="field">
                  <label>Приоритет</label>
                  <span className="mono">{need.priority}</span>
                </div>
              </div>
            </div>
            <div className="card">
              <h3>Трассировка вниз</h3>
              <div>
                {need.services.length === 0 && (
                  <span className="warn">△ нужда не порождает сервисов</span>
                )}
                {need.services.map((s) => (
                  <span key={s} className="chip">
                    {s}
                  </span>
                ))}
              </div>
            </div>
          </div>
        ) : (
          <div className="secondary">Выберите нужду</div>
        )}
      </aside>
    </div>
  )
}
