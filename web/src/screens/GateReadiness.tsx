// Готовность к точке (блок D, дизайн: экран readiness) — главный экран
// системы: что мешает пройти ближайшую точку, поимённо, с переходом в
// операцию, где это чинится. Прохождение — результат проверки, а не кнопка
// (ADR-029): «Запросить прохождение» лишь просит сервер проверить ещё раз.
import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, type GateIssuesView, type OperationRow } from '../api/client'
import { edit, EditRejected } from '../api/edit'
import { currentProject } from '../api/project'
import { useSession } from '../ui/session'

export function GateReadiness({ onGo }: { onGo: (screen: string) => void }) {
  const { author } = useSession()
  const project = currentProject()
  const [view, setView] = useState<GateIssuesView | null>(null)
  const [ops, setOps] = useState<OperationRow[]>([])
  const [activeReturn, setActiveReturn] = useState<{ gate: string; reason: string; to: string[] } | null>(null)
  const [rationale, setRationale] = useState('')
  const [report, setReport] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    if (!project) return
    api.operations()
      .then(async (o) => {
        setOps(o.operations)
        if (!o.next_gate) { setView(null); return }
        setView(await api.gateIssues(o.next_gate))
      })
      .catch((e) => setError(String(e)))
    // действующий возврат — из объекта проекта
    edit.object(project)
      .then((p) => {
        const doc = p.doc as { return?: { gate: string; reason: string; to: string[] } } | undefined
        setActiveReturn(doc?.return ?? null)
      })
      .catch(() => setActiveReturn(null))
  }, [project])

  useEffect(load, [load])

  if (!project) return <div className="empty">Выберите проект на портфеле.</div>
  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>

  const pass = async () => {
    if (!view) return
    setReport(null)
    try {
      const r = await api.gatePass(view.gate, author, rationale)
      setReport(`Точка ${view.gate} пройдена: решение ${r.decision}` +
        (r.next_gate ? `, ближайшая — ${r.next_gate}` : ', вехи исчерпаны'))
      setRationale('')
    } catch (e) {
      setReport(e instanceof ApiError || e instanceof EditRejected ? String(e.message) : String(e))
    }
    load()
  }

  const requestReturn = async () => {
    if (!view) return
    const reason = window.prompt('Причина возврата (заключение обзора):')
    if (!reason) return
    try {
      await api.gateReturn(view.gate, author, reason, [])
      setReport('Возврат записан: движение ограничено до снятия')
    } catch (e) {
      setReport(String(e))
    }
    load()
  }

  const resolveReturn = async () => {
    const note = window.prompt('Как снята причина возврата:')
    if (!note) return
    try {
      await api.gateReturnResolve(author, note)
      setReport('Возврат снят')
    } catch (e) {
      setReport(String(e))
    }
    load()
  }

  const fixers = (issue: string): OperationRow | undefined => {
    const code = issue.split(':')[0]
    return ops.find((o) => o.code === code && o.screen)
  }

  return (
    <>
      <div className="toolbar">
        <h2>Готовность к точке</h2>
        {view && <span className="mono" style={{ fontWeight: 600 }}>{view.gate}</span>}
        {view && (
          <span className={`count ${view.ready ? 'count--ready' : ''}`}
            style={{ color: view.ready ? 'var(--status-baseline)' : undefined }}>
            {view.ready ? 'готово' : `не закрыто: ${view.issues.length}`}
          </span>
        )}
        <div className="grow" />
        {activeReturn ? (
          <button className="btn" onClick={resolveReturn}>Снять возврат</button>
        ) : (
          view && <button className="btn" onClick={requestReturn}>Возврат по заключению</button>
        )}
      </div>
      <div className="workarea" style={{ padding: 14 }}>
        {report && <div className="notice">{report}</div>}
        {activeReturn && (
          <div className="notice notice--blocked">
            Действует возврат от точки <b className="mono">{activeReturn.gate}</b> в операции{' '}
            <span className="mono">{activeReturn.to.join(', ')}</span>: {activeReturn.reason}.
            Прохождение точек ограничено до снятия причины.
          </div>
        )}
        {!view ? (
          <div className="empty">Все вехи проекта пройдены.</div>
        ) : view.ready ? (
          <div className="card" style={{ maxWidth: 640 }}>
            <h3>Точка {view.gate} готова к прохождению</h3>
            <div>
              <p className="secondary">
                Перечень незакрытого пуст. Прохождение фиксируется решением —
                укажите основание.
              </p>
              <div className="field">
                <label>Основание решения</label>
                <input style={{ width: '100%' }} value={rationale}
                  onChange={(e) => setRationale(e.target.value)}
                  placeholder="комплект рассмотрен, замечания устранимы" />
              </div>
              <button className="btn btn--primary" disabled={!author || !rationale.trim() || !!activeReturn}
                onClick={pass}
                title={author ? '' : 'представьтесь в шапке'}>
                Запросить прохождение
              </button>
            </div>
          </div>
        ) : (
          <div className="card">
            <h3>Что мешает пройти {view.gate} — {view.issues.length}</h3>
            <div>
              {view.issues.map((issue, i) => {
                const op = fixers(issue)
                return (
                  <div key={i} className="issue">
                    <span className="wrap" style={{ flex: 1 }}>{issue}</span>
                    {op && (
                      <button className="btn" onClick={() => onGo(op.screen!)}>
                        {op.code} →
                      </button>
                    )}
                  </div>
                )
              })}
            </div>
          </div>
        )}
      </div>
    </>
  )
}
