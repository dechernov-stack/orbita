// Экран 9 — готовность к контрольной точке (Ш7 мастера).
//
// Что базировать и до какого статуса — решает сервер по реестру ворот.
// Клиент показывает разрывы, а не вычисляет их.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { ReadinessView } from '../api/types'

const GATES = ['MCR', 'SRR', 'SDR', 'PDR']

export function Readiness() {
  const [gate, setGate] = useState('SRR')
  const [view, setView] = useState<ReadinessView | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setView(null)
    api.readiness(gate).then(setView).catch((e) => setError(String(e)))
  }, [gate])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>

  return (
    <div className="split">
      <div className="pane" style={{ padding: 16 }}>
        <h2 style={{ fontSize: 15, marginTop: 0 }}>Готовность к контрольной точке</h2>
        <div className="tabs" style={{ marginBottom: 12 }}>
          {GATES.map((g) => (
            <button key={g} className="tab" aria-selected={g === gate} onClick={() => setGate(g)}>
              {g}
            </button>
          ))}
        </div>

        {!view ? (
          <div className="secondary">Загрузка…</div>
        ) : (
          <>
            <div style={{ display: 'flex', gap: 24, marginBottom: 16 }}>
              <div>
                <div className="secondary">Объектов готово</div>
                <div className="mono" style={{ fontSize: 24 }}>
                  {view.readyObjects} / {view.totalObjects}
                </div>
              </div>
              <div>
                <div className="secondary">Разрывов</div>
                <div className={`mono${view.gaps.length > 0 ? ' warn' : ''}`} style={{ fontSize: 24 }}>
                  {view.gaps.length}
                </div>
              </div>
            </div>

            {view.ready ? (
              <div className="card">
                <h3>Готово</h3>
                <div>Все объекты дошли до статуса, требуемого точкой {view.gate}.</div>
              </div>
            ) : (
              <table>
                <thead>
                  <tr>
                    <th style={{ width: 120 }}>Объект</th>
                    <th style={{ width: 140 }}>Статус сейчас</th>
                    <th style={{ width: 140 }}>Требуется</th>
                    <th>Что сделать</th>
                  </tr>
                </thead>
                <tbody>
                  {view.gaps.map((gap) => (
                    <tr key={gap.id}>
                      <td>
                        <span className="id">{gap.id}</span>
                      </td>
                      <td className="secondary">{gap.actual}</td>
                      <td className="mono">{gap.required}</td>
                      <td className="secondary">
                        перевести из «{gap.actual}» в «{gap.required}»
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </>
        )}
      </div>

      <aside className="pane pane--side">
        <div className="card">
          <h3>Что означает точка</h3>
          <div className="secondary">
            Реестр ворот задаёт, до какого статуса должен дойти каждый вид объекта к контрольной
            точке. Требования к SDR строже, чем к SRR: список разрывов растёт не потому, что
            модель ухудшилась, а потому, что планка выше.
          </div>
        </div>
      </aside>
    </div>
  )
}
