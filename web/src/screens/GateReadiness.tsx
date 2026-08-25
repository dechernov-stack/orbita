// Готовность к точке (блок D, дизайн: экран readiness) — главный экран
// системы: что мешает пройти ближайшую точку, поимённо, с переходом в
// операцию, где это чинится. Прохождение — результат проверки, а не кнопка
// (ADR-029): «Запросить прохождение» лишь просит сервер проверить ещё раз.
import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, type GateIssuesView, type OperationRow } from '../api/client'
import { edit, EditRejected } from '../api/edit'
import { requestDocTemplate, requestObject, screenOfObject } from '../api/intent'
import { currentProject } from '../api/project'
import { useSession } from '../ui/session'

/** Ярлык реестра по префиксу id — для сводных кнопок «открыть целиком». */
const PREFIX_LABEL: Record<string, string> = {
  RQ: 'Требования', SV: 'Сервисы', ND: 'Нужды', MG: 'Цели', CO: 'ConOps',
  TL: 'Технологии', RSK: 'Риски', CM: 'Элементы', IF: 'Интерфейсы',
  AL: 'Альтернативы', CE: 'Стоимость', OD: 'ODA', WB: 'WBS', RF: 'Замечания',
}

export function GateReadiness({ onGo }: { onGo: (screen: string) => void }) {
  const { author } = useSession()
  const project = currentProject()
  const [view, setView] = useState<GateIssuesView | null>(null)
  const [ops, setOps] = useState<OperationRow[]>([])
  const [activeReturn, setActiveReturn] = useState<{ gate: string; reason: string; to: string[] } | null>(null)
  const [rationale, setRationale] = useState('')
  const [report, setReport] = useState<string | null>(null)
  const [acting, setActing] = useState(false)
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

  // Свежесть перечня (находка второго захода): инженер уходит чинить
  // названное, возвращается — перечень обязан пересчитаться. Возврат фокуса
  // окну и вкладке — сигнал «я вернулся».
  useEffect(() => {
    const onFocus = () => load()
    window.addEventListener('focus', onFocus)
    document.addEventListener('visibilitychange', onFocus)
    return () => {
      window.removeEventListener('focus', onFocus)
      document.removeEventListener('visibilitychange', onFocus)
    }
  }, [load])

  if (!project) return <div className="empty">Выберите проект на портфеле.</div>
  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>

  const pass = async () => {
    if (!view || acting) return
    setReport(null)
    setActing(true)
    try {
      const r = await api.gatePass(view.gate, author, rationale)
      setReport(`Точка ${view.gate} пройдена: решение ${r.decision}` +
        (r.next_gate ? `, ближайшая — ${r.next_gate}` : ', вехи исчерпаны'))
      setRationale('')
    } catch (e) {
      setReport(e instanceof ApiError || e instanceof EditRejected ? String(e.message) : String(e))
    } finally {
      setActing(false)
    }
    load()
  }

  const requestReturn = async () => {
    if (!view || acting) return
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

  /**
   * Куда ведёт строка незакрытого (п. 7а списка после MCR): невыпущенный
   * документ — на экран «Документы» с открытым шаблоном; строка, называющая
   * объект, — в его реестр, в инспектор. Прежде такие строки были
   * некликабельны, и инженер искал место руками.
   */
  const jump = (issue: string): { title: string; go: () => void } | null => {
    const tpl = issue.match(/шаблон ([a-z_]+)/)
    if (tpl) {
      return {
        title: 'открыть документ',
        go: () => { requestDocTemplate(tpl[1]); onGo('docs') },
      }
    }
    const obj = issue.match(/([A-Z]{2,3}-[0-9]{4})/)
    if (obj) {
      const screen = screenOfObject(obj[1])
      if (screen) {
        return {
          title: `открыть ${obj[1]}`,
          go: () => { requestObject(obj[1]); onGo(screen) },
        }
      }
    }
    return null
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
        <button className="btn" onClick={load} title="пересчитать перечень незакрытого">
          Обновить
        </button>
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
              <button className="btn btn--primary"
                disabled={!author || !rationale.trim() || !!activeReturn || acting}
                onClick={pass}
                title={author ? '' : 'представьтесь в шапке'}>
                {acting ? 'Проверка…' : 'Запросить прохождение'}
              </button>
            </div>
          </div>
        ) : (
          <div className="card">
            <h3>Что мешает пройти {view.gate} — {view.issues.length}</h3>
            <div>
              {/* Сводные переходы (находка второго захода: «открывай сразу
                  полную матрицу») — когда незакрытого по виду много, идти
                  по одной строке мучительно: реестр целиком, с массовым
                  переводом и «Базировать все», в один клик. */}
              {(() => {
                const byScreen = new Map<string, { label: string; count: number }>()
                view.issues.forEach((issue) => {
                  const m = issue.match(/([A-Z]{2,3})-[0-9]{4}/)
                  if (!m) return
                  const screen = screenOfObject(`${m[1]}-0000`)
                  if (!screen) return
                  const cur = byScreen.get(screen) ?? { label: PREFIX_LABEL[m[1]] ?? m[1], count: 0 }
                  cur.count += 1
                  byScreen.set(screen, cur)
                })
                if (byScreen.size === 0) return null
                return (
                  <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 8 }}>
                    <span className="secondary">Открыть реестр целиком:</span>
                    {Array.from(byScreen.entries()).map(([screen, g]) => (
                      <button key={screen} className="btn" onClick={() => onGo(screen)}
                        title="реестр с массовым переводом статусов">
                        {g.label} · {g.count} →
                      </button>
                    ))}
                  </div>
                )
              })()}
              {view.issues.map((issue, i) => {
                const op = fixers(issue)
                const j = op ? null : jump(issue)
                const go = op ? () => onGo(op.screen!) : j?.go
                return (
                  <div key={i} className="issue"
                    onClick={go}
                    style={go ? { cursor: 'pointer' } : undefined}
                    title={op ? `открыть операцию ${op.code}` : j?.title}>
                    <span className="wrap" style={{ flex: 1 }}>{issue}</span>
                    {op && (
                      <button className="btn" onClick={(e) => { e.stopPropagation(); onGo(op.screen!) }}>
                        {op.code} →
                      </button>
                    )}
                    {j && (
                      <button className="btn" onClick={(e) => { e.stopPropagation(); j.go() }} title={j.title}>
                        →
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
