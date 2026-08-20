// Экран 11 — спецификация элемента: работа «от компонента», а не от требования.
//
// Источник каждого требования и вид декомпозиции приходят с сервера: клиент не
// решает, производное требование или распределённое, — он рисует то, что решила
// модель (ADR-019).
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { ComponentSpecification } from '../api/types'
import { BudgetGauge, Condition, StatusDot } from '../ui/parts'

export function ComponentSpec({ componentId }: { componentId: string }) {
  const [spec, setSpec] = useState<ComponentSpecification | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.componentSpecification(componentId).then(setSpec).catch((e) => setError(String(e)))
  }, [componentId])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!spec) return <div className="empty">Загрузка…</div>

  return (
    <div className="split">
      <div className="pane">
        <h2 style={{ fontSize: 15, margin: '12px 16px 8px' }}>
          Спецификация элемента <span className="id">{spec.componentId}</span>
        </h2>
        <table>
          <thead>
            <tr>
              <th style={{ width: 100 }}>ID</th>
              <th>Требование</th>
              <th style={{ width: 140 }}>Условие</th>
              <th style={{ width: 160 }}>Источник</th>
              <th style={{ width: 180 }}>V&amp;V</th>
              <th style={{ width: 90 }}>Статус</th>
            </tr>
          </thead>
          <tbody>
            {spec.rows.map((row) => (
              <tr key={row.id}>
                <td>
                  <span className="id">{row.id}</span>
                </td>
                <td>
                  <span className="truncate">{row.statement}</span>
                </td>
                <td>
                  <Condition condition={row.condition} />
                </td>
                <td>
                  {row.source ? (
                    <span
                      className={`chip${row.derivationKind === 'derived' ? ' chip--derived' : ''}`}
                      title={row.derivationKind === 'derived' ? 'производное' : 'распределённое'}
                    >
                      {row.source}
                    </span>
                  ) : (
                    <span className="secondary">—</span>
                  )}
                  {row.derivationKind === 'derived' && (
                    <span className="secondary"> производное</span>
                  )}
                </td>
                <td>
                  <span className="secondary">{row.verificationState}</span>{' '}
                  <span className="mono">
                    {row.eventsDone} из {row.eventsTotal}
                  </span>
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
        <div className="card">
          <h3>Бюджеты</h3>
          <div>
            {Object.keys(spec.budgets).length === 0 && (
              <span className="secondary">свёрток на этом элементе нет</span>
            )}
            {Object.entries(spec.budgets).map(([name, bar]) => (
              <div key={name} className="field">
                <label>{name}</label>
                <BudgetGauge bar={bar} />
              </div>
            ))}
          </div>
        </div>
      </aside>
    </div>
  )
}
