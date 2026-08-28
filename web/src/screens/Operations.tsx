// Порядок работы к точке (МВП-П1 §1.5, переделка мёртвого экрана операций):
// последовательные операции — цепочкой уровней, параллельные — рядом В ОДНОМ
// уровне; порядок ТОЛЬКО данными фазы (входы-предшественники операций), не
// рисунком. На операции: состояние выхода, её задания с исполнителями,
// выход-артефакт ссылкой к месту. Мёртвая ссылка запрещена как класс:
// строка либо ведёт к месту, либо несёт тихую подпись.
import { useCallback, useEffect, useState } from 'react'
import { api, type OperationRow, type OperationsView } from '../api/client'
import { requestDocTemplate } from '../api/intent'

const STATE_LABEL: Record<string, string> = {
  Done: 'выполнена',
  InProgress: 'в работе',
  NotStarted: 'не начата',
  NotMeasurable: 'нечем измерить',
}

type TaskRow = Awaited<ReturnType<typeof api.myTasks>>['tasks'][number]

/** Уровни порядка: 0 — без входов; дальше — за самым поздним входом. */
function levelsOf(ops: OperationRow[]): OperationRow[][] {
  const byCode = new Map(ops.map((o) => [o.code, o]))
  const level = new Map<string, number>()
  const walk = (o: OperationRow, seen: Set<string>): number => {
    const cached = level.get(o.code)
    if (cached !== undefined) return cached
    if (seen.has(o.code)) return 0 // цикл в данных — не зависаем
    seen.add(o.code)
    const ins = (o.inputs ?? []).map((c) => byCode.get(c)).filter(Boolean) as OperationRow[]
    const lv = ins.length === 0 ? 0 : 1 + ins.reduce((m, i) => {
      const l = walk(i, seen)
      return l > m ? l : m
    }, 0)
    level.set(o.code, lv)
    return lv
  }
  ops.forEach((o) => walk(o, new Set()))
  const out: OperationRow[][] = []
  ops.forEach((o) => {
    const lv = level.get(o.code) ?? 0
    while (out.length <= lv) out.push([])
    out[lv].push(o)
  })
  return out.filter((row) => row.length > 0)
}

export function Operations({ project, onGo }: {
  project: string
  onGo: (screen: string) => void
}) {
  const [ops, setOps] = useState<OperationsView | null>(null)
  const [tasks, setTasks] = useState<TaskRow[]>([])
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    api.operations().then(setOps).catch((e) => setError(String(e)))
    api.myTasks().then((v) => setTasks(v.tasks)).catch(() => setTasks([]))
  }, [])
  useEffect(load, [load, project])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!ops) return <div className="empty">Загрузка…</div>

  const rows = levelsOf(ops.operations)
  const tasksOf = (code: string) =>
    tasks.filter((t) => t.gap_ref === `op:${code}` && t.state !== 'done')

  return (
    <>
      <div className="toolbar">
        <h2>Порядок работы</h2>
        <span className="secondary">
          фаза {ops.phase === 'phase_a' ? 'Phase A' : 'Pre-Phase A'}
          {ops.next_gate && <> · ближайшая точка <b className="mono">{ops.next_gate}</b></>}
          {' '}· порядок — данными фазы: рядом — параллельные, ниже — за входами
        </span>
      </div>
      <div className="workarea" style={{ padding: '12px 16px', overflow: 'auto' }}>
        {rows.map((row, i) => (
          <div key={i} style={{ display: 'flex', alignItems: 'stretch', gap: 8, marginBottom: 6 }}>
            <span className="secondary mono" title="уровень порядка: всё в строке может идти параллельно"
              style={{ width: 20, paddingTop: 10, flex: 'none' }}>
              {i + 1}
            </span>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, flex: 1 }}>
              {row.map((o) => {
                const its = tasksOf(o.code)
                const open = o.state === 'NotStarted' || o.state === 'InProgress'
                return (
                  <div key={o.code} className="card" style={{ margin: 0, minWidth: 260, flex: '1 1 260px' }}>
                    <div style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '6px 10px' }}>
                      <span className={`ops__state ops__state--${o.state} ${o.returned_to ? 'ops__state--returned' : ''}`}
                        title={o.returned_to ? 'цель действующего возврата' : STATE_LABEL[o.state] ?? o.state} />
                      <span className="mono" style={{ flex: 'none' }}>{o.code}</span>
                      <span title={`${o.name} — ${o.executor}`}
                        style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {o.name}
                      </span>
                    </div>
                    <div className="secondary" style={{ padding: '0 10px 6px', fontSize: 11.5, display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                      <span title="исполнитель по регламенту">{o.executor}</span>
                      {o.gate && <span className="mono" title="точка операции">{o.gate}</span>}
                      {open && (
                        <span className="warn" title={o.state === 'NotStarted'
                          ? `выход не создан (${o.required_status ? `нужен статус ${o.required_status}` : 'объектов нет'})`
                          : `выход не достиг статуса ${o.required_status}`}>
                          разрыв выхода
                        </span>
                      )}
                      {its.length > 0 && (
                        <span title={`задания: ${its.map((t) => t.assignee).join(', ')}`}>
                          задания · {its.length} ({[...new Set(its.map((t) => t.assignee))].join(', ')})
                        </span>
                      )}
                      <span style={{ flex: 1 }} />
                      {o.screen ? (
                        <button className="rr-assign" title={`выход: ${(o.docs ?? []).join(', ') || 'рабочее место операции'}`}
                          onClick={() => {
                            if (o.screen === 'docs' && o.templates?.length) requestDocTemplate(o.templates[0])
                            onGo(o.screen!)
                          }}>
                          к месту →
                        </button>
                      ) : (
                        <span className="secondary" title="рабочего места у операции ещё нет — мёртвых ссылок не рисуем">
                          место появится с волной моделирования
                        </span>
                      )}
                    </div>
                  </div>
                )
              })}
            </div>
          </div>
        ))}
      </div>
    </>
  )
}
