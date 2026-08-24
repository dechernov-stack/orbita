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
 * Связка между соседними точками (второй заход прогона, замечание к п. 1):
 * длительность стоит МЕЖДУ карточками, а не на отдельной шкале — прежняя
 * шкала жила своей жизнью под лентой и связи точек не показывала. Обе даты
 * есть — стрелка несёт число дней; какой-то нет — честное «даты нет».
 */
function GateLink({ from, to }: { from: GateRow; to: GateRow }) {
  const days = from.due && to.due
    ? Math.round((new Date(to.due).getTime() - new Date(from.due).getTime()) / DAY)
    : null
  return (
    <div className="gatelink" title={days == null
      ? 'длительность появится, когда у обеих точек будут плановые даты (паспорт проекта)'
      : `${from.gate} → ${to.gate}: ${days} дн.`}>
      <span className={days == null ? 'secondary' : ''} style={{ fontSize: 10.5, whiteSpace: 'nowrap' }}>
        {days == null ? 'даты нет' : `${days} дн.`}
      </span>
      <span className="gatelink__arrow">→</span>
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
        {/* Линейность без мастера (список после MCR, п. 3): спина процесса
            сама называет следующий незакрытый шаг и ведёт на его рабочее
            место — свобода ходить по экранам при этом не отнимается.
            Неизмеримые операции пропускаются: кнопка, вечно зовущая к шагу,
            который никогда не станет «выполнен», — не подсказка, а капкан
            (второй заход прогона). */}
        {(() => {
          const next = ops.operations.find(
            (o) => o.state !== 'Done' && o.state !== 'NotMeasurable' && o.screen,
          )
          if (!next) return null
          return (
            <button className="btn" onClick={() => onGo(next.screen!)}
              title={`${next.name} — ${next.executor}`}>
              следующий шаг: {next.code} {next.name.length > 34 ? `${next.name.slice(0, 34)}…` : next.name} →
            </button>
          )
        })()}
        <button className="btn btn--primary" onClick={() => onGo('readiness')}>Готовность к точке</button>
      </div>
      <div className="workarea">
        <div className="gatestrip">
          {gates.map((g, i) => {
            const overdue = !g.held && !!g.due && new Date(g.due).getTime() < Date.now()
            return [
              i > 0 ? <GateLink key={`l${i}`} from={gates[i - 1]} to={g} /> : null,
              <div key={g.gate}
                className={`gatecard ${g.held ? 'gatecard--held' : g.gate === ops.next_gate ? 'gatecard--next' : ''}`}>
                <div className="mono" style={{ fontWeight: 600 }}>{g.gate}</div>
                <div className="secondary" style={{ fontSize: 11.5 }}>
                  {g.held ? 'пройдена' : g.gate === ops.next_gate ? 'ближайшая' : 'впереди'}
                </div>
                {g.due ? (
                  <div className="mono" style={{ fontSize: 11, color: overdue ? 'var(--status-error, #b3261e)' : undefined }}>
                    {g.due}{overdue ? ' · просрочена' : ''}
                  </div>
                ) : (
                  <div className="secondary" style={{ fontSize: 10.5 }}>дата не задана</div>
                )}
              </div>,
            ]
          })}
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
