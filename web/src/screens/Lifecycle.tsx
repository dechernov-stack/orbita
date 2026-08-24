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

const DAY = 86_400_000

/**
 * Лёгкая шкала времени по плановым датам точек (список после MCR, п. 1):
 * маркеры пропорционально календарю, длительности промежутков в днях,
 * просроченная и непройденная точка — красным, «сегодня» — риской.
 * Не планировщик: перетаскиваний нет, даты правятся в паспорте проекта.
 */
function GateTimeline({ gates }: { gates: GateRow[] }) {
  const dated = gates.filter((g): g is GateRow & { due: string } => !!g.due)
  if (dated.length < 2) {
    return (
      <div className="secondary" style={{ padding: '2px 14px 10px', fontSize: 12 }}>
        Шкала времени строится по плановым датам точек — задайте их в паспорте
        проекта (Контроль → «Паспорт проекта»).
      </div>
    )
  }
  const t = (s: string) => new Date(s).getTime()
  const start = t(dated[0].due)
  const span = Math.max(t(dated[dated.length - 1].due) - start, DAY)
  const pos = (s: string) => ((t(s) - start) / span) * 100
  const today = Date.now()
  const todayPos = ((today - start) / span) * 100

  return (
    <div style={{ padding: '4px 24px 6px' }}>
      <div style={{ position: 'relative', height: 64 }}>
        <div style={{ position: 'absolute', top: 26, left: 0, right: 0, height: 2, background: 'var(--border, #d5d9e0)' }} />
        {todayPos >= 0 && todayPos <= 100 && (
          <div title={`сегодня: ${new Date(today).toISOString().slice(0, 10)}`}
            style={{ position: 'absolute', top: 16, bottom: 18, left: `${todayPos}%`, width: 0, borderLeft: '1px dashed var(--status-approved, #7a6b2f)' }} />
        )}
        {/* длительности промежутков — дней между соседними датированными точками */}
        {dated.slice(1).map((g, i) => {
          const prev = dated[i]
          const days = Math.round((t(g.due) - t(prev.due)) / DAY)
          const mid = (pos(prev.due) + pos(g.due)) / 2
          return (
            <span key={`${prev.gate}-${g.gate}`} className="secondary"
              style={{ position: 'absolute', top: 6, left: `${mid}%`, transform: 'translateX(-50%)', fontSize: 10.5, whiteSpace: 'nowrap' }}>
              {days} дн.
            </span>
          )
        })}
        {dated.map((g, i) => {
          const overdue = !g.held && t(g.due) < today
          const color = g.held
            ? 'var(--status-baseline, #2f7a3f)'
            : overdue
              ? 'var(--status-error, #b3261e)'
              : 'var(--border-strong, #8a94a6)'
          // Крайние подписи прижаты внутрь, иначе половина текста уходит за край;
          // кружок при этом остаётся ровно на своей позиции по времени.
          const first = i === 0
          const last = i === dated.length - 1
          return (
            <div key={g.gate}
              title={`${g.gate} — ${g.due}${g.held ? ' · пройдена' : overdue ? ' · просрочена' : ''}`}
              style={{
                position: 'absolute', top: 20, left: `${pos(g.due)}%`,
                transform: `translateX(${first ? '0%' : last ? '-100%' : '-50%'})`,
                textAlign: first ? 'left' : last ? 'right' : 'center',
              }}>
              <div style={{
                width: 13, height: 13, borderRadius: '50%',
                margin: first ? '0 auto 0 0' : last ? '0 0 0 auto' : '0 auto',
                background: g.held ? color : '#fff', border: `3px solid ${color}`,
              }} />
              <div className="mono" style={{ fontSize: 10.5, fontWeight: 600, marginTop: 2, color: overdue ? color : undefined }}>{g.gate}</div>
              <div className="secondary" style={{ fontSize: 10 }}>{g.due.slice(5)}</div>
            </div>
          )
        })}
      </div>
    </div>
  )
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
        <GateTimeline gates={gates} />
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
