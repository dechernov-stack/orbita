// Жизненный цикл проекта (блок D, дизайн: экран lifecycle): лента шести
// контрольных точек и карта операций текущей фазы в три колонки. Состояние
// операций считает сервер (ADR-029 п. 6) — клиент только показывает и ведёт
// на рабочее место операции.
import { useEffect, useState } from 'react'
import { api, type OperationsView } from '../api/client'

interface GateRow { gate: string; due: string | null; held: boolean }

const STATE_LABEL: Record<string, string> = {
  Done: 'выполнена',
  InProgress: 'в работе',
  NotStarted: 'не начата',
  NotMeasurable: 'нечем измерить',
}

export function Lifecycle({ project, onGo }: { project: string; onGo: (screen: string) => void }) {
  const [gates, setGates] = useState<GateRow[] | null>(null)
  const [ops, setOps] = useState<OperationsView | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.gates()
      .then((g) => setGates((g as unknown as { gates: GateRow[] }).gates))
      .catch((e) => setError(String(e)))
    api.operations()
      .then(setOps)
      .catch((e) => setError(String(e)))
  }, [project])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!gates || !ops) return <div className="empty">Загрузка…</div>

  const columns: OperationsView['operations'][number][][] = [[], [], []]
  ops.operations.forEach((o, i) => columns[i % 3].push(o))

  return (
    <>
      <div className="toolbar">
        <h2>Жизненный цикл</h2>
        <span className="secondary">
          фаза {ops.phase === 'phase_a' ? 'Phase A' : 'Pre-Phase A'}
          {ops.next_gate && <> · ближайшая точка <b className="mono">{ops.next_gate}</b></>}
        </span>
        <div className="grow" />
        <button className="btn btn--primary" onClick={() => onGo('readiness')}>Готовность к точке</button>
      </div>
      <div className="workarea">
        <div className="gatestrip">
          {gates.map((g) => (
            <div key={g.gate}
              className={`gatecard ${g.held ? 'gatecard--held' : g.gate === ops.next_gate ? 'gatecard--next' : ''}`}>
              <div className="mono" style={{ fontWeight: 600 }}>{g.gate}</div>
              <div className="secondary" style={{ fontSize: 11.5 }}>
                {g.held ? 'пройдена' : g.gate === ops.next_gate ? 'ближайшая' : 'впереди'}
              </div>
              {g.due && <div className="mono" style={{ fontSize: 11 }}>{g.due}</div>}
            </div>
          ))}
        </div>
        <div className="ops__group" style={{ padding: '0 14px 6px' }}>
          Операции фазы — {ops.operations.length}
        </div>
        <div className="opsmap">
          {columns.map((col, ci) => (
            <div key={ci}>
              {col.map((o) => (
                <button key={o.code} className="opsmap__row" style={{ width: '100%' }}
                  title={`${o.name} — ${o.executor}`}
                  onClick={() => o.screen && onGo(o.screen)}>
                  <span className={`ops__state ops__state--${o.state} ${o.returned_to ? 'ops__state--returned' : ''}`} />
                  <span className="mono" style={{ minWidth: 30 }}>{o.code}</span>
                  <span className="name">{o.name}</span>
                  <span className="secondary" style={{ marginLeft: 'auto', fontSize: 11 }}>
                    {o.returned_to ? 'возврат' : STATE_LABEL[o.state] ?? o.state}
                  </span>
                </button>
              ))}
            </div>
          ))}
        </div>
      </div>
    </>
  )
}
