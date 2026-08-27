// Операции фазы — временный дом (ответ по О-10 §1): старый экран КАК ЕСТЬ,
// не перекрашен; доступен пунктом «Операции» в панели «Контроль». «Дом
// операций» (мастер-механика движения по регламенту) придёт своим брифом —
// позиция конвейера после кольца контроля.
import { useEffect, useState } from 'react'
import { api, type OperationsView } from '../api/client'
import { requestDocTemplate } from '../api/intent'

const STATE_LABEL: Record<string, string> = {
  Done: 'выполнена',
  InProgress: 'в работе',
  NotStarted: 'не начата',
  NotMeasurable: 'нечем измерить',
}

export function Operations({ project, onGo }: {
  project: string
  onGo: (screen: string) => void
}) {
  const [ops, setOps] = useState<OperationsView | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.operations()
      .then(setOps)
      .catch((e) => setError(String(e)))
  }, [project])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!ops) return <div className="empty">Загрузка…</div>

  const columns: OperationsView['operations'][number][][] = [[], [], []]
  ops.operations.forEach((o, i) => columns[i % 3].push(o))

  return (
    <>
      <div className="toolbar">
        <h2>Операции</h2>
        <span className="secondary">
          фаза {ops.phase === 'phase_a' ? 'Phase A' : 'Pre-Phase A'}
          {ops.next_gate && <> · ближайшая точка <b className="mono">{ops.next_gate}</b></>}
        </span>
        <div className="grow" />
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
      </div>
      <div className="workarea">
        <div className="ops__group" style={{ padding: '10px 14px 6px' }}>
          Операции фазы — {ops.operations.length}
        </div>
        <div className="opsmap">
          {columns.map((col, ci) => (
            <div key={ci}>
              {col.map((o) => (
                <button key={o.code} className="opsmap__row" style={{ width: '100%' }}
                  title={`${o.name} — ${o.executor}`}
                  onClick={() => {
                    if (!o.screen) return
                    // документная операция открывает СВОЙ шаблон
                    if (o.screen === 'docs' && o.templates?.length) requestDocTemplate(o.templates[0])
                    onGo(o.screen)
                  }}>
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
